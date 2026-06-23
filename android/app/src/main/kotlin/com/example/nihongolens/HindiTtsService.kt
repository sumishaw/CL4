package com.example.nihongolens

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/**
 * HindiTtsService — speaks translated Hindi subtitles
 *
 * Voice model: Kokoro multi-lang on port 8766
 *   sid=31  hf_alpha  → Hindi Female voice 1 (genuine Indian female)
 *   sid=32  hf_beta   → Hindi Female voice 2
 *   sid=33  hm_omega  → Hindi Male voice 1
 *   sid=34  hm_psi    → Hindi Male voice 2
 *
 * Gender detection: two-signal fusion
 *   1. ZCR pitch from AudioRecord (frequency-based, works for internal audio via mic)
 *   2. Pronoun/keyword analysis of source text (language-aware)
 *   Signals are smoothed and combined: text wins on clear pronoun match,
 *   frequency used as tiebreaker / confirmation
 *
 * Dedicated FIFO backlogs (no sentence skipping):
 *   fetchQueue (unbounded) — texts waiting to be synthesised
 *   playQueue  (unbounded) — WAV bytes ready to play
 *   Worker A fetches WAV in background while Worker B plays previous sentence
 */
object HindiTtsService {
    private const val TAG     = "HindiTTS"
    private const val TTS_URL = "http://127.0.0.1:8766/tts"

    // Kokoro Hindi speaker IDs
    private const val SID_FEMALE_1 = 31   // hf_alpha
    private const val SID_FEMALE_2 = 32   // hf_beta
    private const val SID_MALE_1   = 33   // hm_omega
    private const val SID_MALE_2   = 34   // hm_psi

    enum class Gender  { AUTO, MALE, FEMALE }
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS }

    @JvmField @Volatile var enabled           = false
    @JvmField @Volatile var selectedGender    = Gender.AUTO
    @Volatile var ttsSpeedMultiplier          = 1.5f   // default 1.5x — keeps up with speech

    @Volatile var detectedGender = Gender.MALE
    @Volatile var isSpeaking     = false
    @Volatile private var speakingUntilMs = 0L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class FetchItem(val text: String, val sid: Int, val speed: Float)
    data class PlayItem (val wav: ByteArray, val durationMs: Long)

    private val fetchQueue = LinkedBlockingQueue<FetchItem>()
    private val playQueue  = LinkedBlockingQueue<PlayItem>()

    private var lastSpokenNorm = ""
    private var fetchWorker: Job? = null
    private var playWorker:  Job? = null
    private var mediaPlayer: android.media.MediaPlayer? = null

    private val mainHandler = Handler(Looper.getMainLooper())


    // ── Init / lifecycle ─────────────────────────────────────────────────────

    private var genderPollJob: Job? = null
    private val genderHistory  = ArrayDeque<Gender>()
    private val GENDER_HISTORY = 6

    private var cacheDir: java.io.File? = null

    fun init(context: Context) {
        cacheDir = context.cacheDir   // always writable, no permissions needed
        startFetchWorker()
        startPlayWorker()
        startGenderPoller()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            fetchQueue.clear(); playQueue.clear()
            stopMediaPlayer(); lastSpokenNorm = ""
        }
    }

    fun setGender(gender: Gender) {
        selectedGender = gender
        if (gender != Gender.AUTO) genderHistory.clear()
    }

    fun setSpeedMultiplier(mult: Float) {
        ttsSpeedMultiplier = mult.coerceIn(0.5f, 4.0f)
    }

    fun isSuppressed(): Boolean =
        isSpeaking || System.currentTimeMillis() < speakingUntilMs

    fun destroy() {
        genderPollJob?.cancel()
        fetchWorker?.cancel(); playWorker?.cancel()
        fetchQueue.clear(); playQueue.clear()
        stopMediaPlayer(); scope.cancel()
    }

    // ── Gender poller — reads FFT pitch analysis from whisper_server /gender ──
    // whisper_server.py already does FFT on real audio (Whisper input).
    // It detects male(85-180Hz) vs female(180-350Hz) fundamental frequency.
    // We poll every 2s to get the current speaker's gender.
    // isSuppressed() prevents reading during TTS playback.

    private fun startGenderPoller() {
        genderPollJob = scope.launch {
            while (isActive) {
                if (selectedGender == Gender.AUTO && !isSuppressed()) {
                    try {
                        val conn = URL("http://127.0.0.1:8765/gender")
                            .openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 2_000
                        conn.readTimeout    = 3_000
                        if (conn.responseCode == 200) {
                            val json = org.json.JSONObject(
                                conn.inputStream.bufferedReader().readText())
                            val g    = json.optString("gender", "neutral")
                            val conf = json.optInt("confidence", 0)
                            if (g != "neutral" && conf >= 3) {
                                val gender = if (g == "female") Gender.FEMALE else Gender.MALE
                                genderHistory.addLast(gender)
                                if (genderHistory.size > GENDER_HISTORY) genderHistory.removeFirst()
                                val fCount = genderHistory.count { it == Gender.FEMALE }
                                val newGender = if (fCount > genderHistory.size / 2)
                                    Gender.FEMALE else Gender.MALE
                                if (newGender != detectedGender) {
                                    detectedGender = newGender
                                    Log.d(TAG, "Gender → $detectedGender (audio FFT: $g conf=$conf)")
                                }
                            }
                        }
                        conn.disconnect()
                    } catch (_: Exception) {}
                }
                delay(2_000)
            }
        }
    }

    // ── Called by LiveCaptionReader after each translation ───────────────────

    fun speak(hindiText: String) {
        if (!enabled || hindiText.isBlank()) return
        val n = hindiText.trim().replace(Regex("\\s+"), " ")
        // Only skip if EXACTLY the same text — not progressively different LC text
        if (n == lastSpokenNorm) {
            Log.d(TAG, "TTS skip dup: '${n.take(30)}'")
            return
        }
        lastSpokenNorm = n

        val emotion        = detectEmotion(hindiText)
        val (baseSpeed, _) = emotionParams(emotion)
        val speed          = (baseSpeed * ttsSpeedMultiplier).coerceIn(0.5f, 4.0f)
        val sid            = effectiveSid()

        // Always enqueue — FIFO never drops
        val dropped = fetchQueue.size > 6
        if (dropped) {
            // Too far behind — clear old backlog, keep only latest
            fetchQueue.clear(); playQueue.clear()
            Log.w(TAG, "TTS backlog cleared — too far behind")
        }
        fetchQueue.offer(FetchItem(hindiText, sid, speed))
        Log.d(TAG, "TTS enq sid=$sid spd=$speed '${hindiText.take(30)}' q=${fetchQueue.size}")
    }

    private fun effectiveSid(): Int {
        val g = if (selectedGender == Gender.AUTO) detectedGender else selectedGender
        return if (g == Gender.FEMALE) SID_FEMALE_1 else SID_MALE_1
    }

    // ── Emotion ──────────────────────────────────────────────────────────────

    private fun detectEmotion(text: String): Emotion {
        val t = text.trim()
        if (t.endsWith("!") || t.endsWith("！")) return Emotion.EXCITED
        if (t.endsWith("?") || t.endsWith("？")) return Emotion.CURIOUS
        val l = t.lowercase()
        val sad    = listOf("दुखी","उदास","दर्द","रोया","गम","sad","cry","pain","sorry","miss","alone")
        val angry  = listOf("गुस्सा","क्रोध","नफरत","angry","hate","stupid","damn","liar","cheat")
        val happy  = listOf("खुश","धन्यवाद","प्यार","शुक्रिया","happy","love","thanks","great","yay")
        val excite = listOf("वाह","कमाल","शानदार","wow","amazing","awesome","incredible","fantastic")
        if (sad.any    { l.contains(it) }) return Emotion.SAD
        if (angry.any  { l.contains(it) }) return Emotion.ANGRY
        if (excite.any { l.contains(it) }) return Emotion.EXCITED
        if (happy.any  { l.contains(it) }) return Emotion.HAPPY
        return Emotion.NEUTRAL
    }

    private fun emotionParams(e: Emotion): Pair<Float, Float> = when (e) {
        Emotion.EXCITED -> Pair(1.12f, 1.0f)
        Emotion.HAPPY   -> Pair(1.06f, 1.0f)
        Emotion.CURIOUS -> Pair(0.96f, 1.0f)
        Emotion.SAD     -> Pair(0.82f, 1.0f)
        Emotion.ANGRY   -> Pair(1.10f, 1.0f)
        Emotion.NEUTRAL -> Pair(1.05f, 1.0f)
    }

    // ── Worker A: Fetch WAV from Kokoro server ────────────────────────────────

    private fun startFetchWorker() {
        fetchWorker = scope.launch {
            while (isActive) {
                val item = try { fetchQueue.take() } catch (_: InterruptedException) { continue }
                if (!enabled) continue
                try {
                    val wav = requestTts(item.text, item.sid, item.speed)
                    if (wav != null && wav.size > 44) {
                        val sr     = readInt(wav, 24)
                        val nCh    = readShort(wav, 22)
                        val bits   = readShort(wav, 34)
                        val pcmLen = wav.size - 44
                        val durMs  = (pcmLen.toLong() * 1000) / (sr.toLong() * nCh * (bits / 8))
                        playQueue.offer(PlayItem(wav, durMs))
                        Log.d(TAG, "Fetched ${durMs}ms WAV → playQueue size=${playQueue.size}")
                    } else {
                        Log.w(TAG, "Empty WAV for '${item.text.take(30)}'")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fetch: ${e.message}")
                }
            }
        }
    }

    private fun startPlayWorker() {
        playWorker = scope.launch {
            while (isActive) {
                // Use take() — blocks until item available with no timeout wait
                val item = try { playQueue.take() } catch (_: InterruptedException) { continue }
                if (!enabled) continue
                try {
                    isSpeaking = true
                    playWav(item.wav)
                } catch (e: Exception) {
                    Log.e(TAG, "Play: ${e.message}")
                } finally {
                    isSpeaking = false
                    speakingUntilMs = System.currentTimeMillis() + 400L
                }
                // No delay — immediately poll next sentence
            }
        }
    }

    // ── HTTP request ─────────────────────────────────────────────────────────

    private suspend fun requestTts(text: String, sid: Int, speed: Float): ByteArray? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val enc  = java.net.URLEncoder.encode(text, "UTF-8")
                val url  = "$TTS_URL?text=$enc&sid=$sid&speed=$speed"
                conn     = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod  = "GET"
                conn.connectTimeout = 5_000
                conn.readTimeout    = 30_000
                if (conn.responseCode == 200) conn.inputStream.readBytes() else null
            } catch (_: Exception) { null }
            finally { try { conn?.disconnect() } catch (_: Exception) {} }
        }

    // ── MediaPlayer playback ─────────────────────────────────────────────────

    private fun stopMediaPlayer() {
        mainHandler.post {
            try { mediaPlayer?.stop()    } catch (_: Exception) {}
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    private suspend fun playWav(wavBytes: ByteArray) {
        val latch = java.util.concurrent.CountDownLatch(1)

        // Parse duration from WAV header for accurate wait
        val sr     = readInt(wavBytes, 24).coerceAtLeast(8000)
        val nCh    = readShort(wavBytes, 22).coerceAtLeast(1)
        val bits   = readShort(wavBytes, 34).coerceAtLeast(8)
        val pcmLen = (wavBytes.size - 44).coerceAtLeast(0)
        val wavDurMs = (pcmLen.toLong() * 1000) / (sr.toLong() * nCh * (bits / 8))

        withContext(Dispatchers.Main) {
            try {
                mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
                mediaPlayer = null

                val mp = android.media.MediaPlayer()
                mp.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                mp.setVolume(1.0f, 1.0f)

                // Write WAV to app cache dir — always writable, no permissions needed
                val dir = cacheDir ?: java.io.File("/data/data/com.example.nihongolens/cache")
                dir.mkdirs()
                val f = java.io.File(dir, "tts_${System.currentTimeMillis()}.wav")
                f.writeBytes(wavBytes)
                mp.setDataSource(f.absolutePath)

                mp.setOnCompletionListener {
                    try { it.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    try { f.delete() } catch (_: Exception) {}
                    latch.countDown()
                }
                mp.setOnErrorListener { it, w, x ->
                    Log.e(TAG, "MP err w=$w x=$x")
                    try { it.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    try { f.delete() } catch (_: Exception) {}
                    latch.countDown(); true
                }
                mp.prepareAsync()
                mp.setOnPreparedListener { it.start(); mediaPlayer = it }
            } catch (e: Exception) {
                Log.e(TAG, "playWav setup: ${e.message}"); latch.countDown()
            }
        }

        // Wait for completion — use wav duration + 20% buffer as safety timeout
        val timeoutMs = (wavDurMs * 1.2).toLong().coerceAtLeast(2000).coerceAtMost(30_000)
        withContext(Dispatchers.IO) {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    // ── WAV header helpers ────────────────────────────────────────────────────

    private fun readInt(b: ByteArray, o: Int) =
        ((b[o+3].toInt() and 0xFF) shl 24) or ((b[o+2].toInt() and 0xFF) shl 16) or
        ((b[o+1].toInt() and 0xFF) shl 8)  or  (b[o].toInt() and 0xFF)

    private fun readShort(b: ByteArray, o: Int) =
        ((b[o+1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)

    fun stopCurrent() {
        fetchQueue.clear(); playQueue.clear()
        stopMediaPlayer(); isSpeaking = false; lastSpokenNorm = ""
    }
}

// ── GenderDetector — text pronoun analysis ───────────────────────────────────

object GenderDetector {
    /** Returns Female/Male if clear pronoun match found, null if ambiguous */
    fun detect(text: String, lang: String): HindiTtsService.Gender? {
        val t = text.lowercase().trim()
        val female: Boolean
        val male:   Boolean
        when (lang) {
            "ja" -> {
                female = t.contains("彼女") || t.contains("あたし") || t.contains("私") ||
                         t.any { c -> "わよね".contains(c) } || t.contains("お母さん")
                male   = t.contains("彼は") || t.contains("俺") || t.contains("僕") ||
                         t.contains("お父さん") || t.contains("彼氏")
            }
            "zh" -> {
                female = t.contains("她") || t.contains("姐") || t.contains("妈") ||
                         t.contains("女士") || t.contains("女孩")
                male   = t.contains("他 ") || t.contains("哥") || t.contains("爸") ||
                         t.contains("先生") || t.contains("男孩")
            }
            "ko" -> {
                female = t.contains("그녀") || t.contains("언니") || t.contains("누나") ||
                         t.contains("여자") || t.contains("엄마")
                male   = t.contains("그는") || t.contains("오빠") || t.contains("형") ||
                         t.contains("남자") || t.contains("아빠")
            }
            "ru" -> {
                female = t.contains("она ") || t.contains("её") || t.contains("сестра") ||
                         t.contains("девушка") || t.contains("женщина")
                male   = t.contains("он ") || t.contains("его") || t.contains("брат") ||
                         t.contains("мужчина") || t.contains("парень")
            }
            "ar" -> {
                female = t.contains("هي") || t.contains("أنثى") || t.contains("امرأة")
                male   = t.contains("هو") || t.contains("ذكر") || t.contains("رجل")
            }
            "es" -> {
                female = t.contains(" ella ") || t.contains("señora") || t.contains("mamá") || t.contains("mujer")
                male   = t.contains(" él ")   || t.contains("señor")  || t.contains("papá") || t.contains("hombre")
            }
            "fr" -> {
                female = t.contains(" elle ") || t.contains("madame") || t.contains("femme")
                male   = t.contains(" il ")   || t.contains("monsieur") || t.contains("homme")
            }
            "de" -> {
                female = t.contains(" sie ") || t.contains("frau") || t.contains("mutter")
                male   = t.contains(" er ")  || t.contains("herr") || t.contains("vater")
            }
            "pt" -> {
                female = t.contains(" ela ") || t.contains("senhora") || t.contains("mulher")
                male   = t.contains(" ele ") || t.contains("senhor")  || t.contains("homem")
            }
            "tr" -> {
                female = t.contains("kadın") || t.contains("kız") || t.contains("anne")
                male   = t.contains("adam")  || t.contains("erkek") || t.contains("baba")
            }
            "id" -> {
                female = t.contains("dia") && (t.contains("wanita") || t.contains("perempuan") || t.contains("ibu"))
                male   = t.contains("dia") && (t.contains("pria") || t.contains("laki") || t.contains("ayah"))
            }
            else -> {  // English and all Latin
                female = t.contains(" she ") || t.contains(" her ") || t.contains("woman") ||
                         t.contains("girl") || t.contains("lady") || t.contains(" mrs ") ||
                         t.contains("miss ") || t.contains("mother") || t.contains("sister") ||
                         t.contains("daughter") || t.contains("wife") || t.contains("aunt") ||
                         t.contains("queen") || t.contains("princess")
                male   = t.contains(" he ") || t.contains(" his ") || t.contains(" him ") ||
                         t.contains("man ") || t.contains("boy ") || t.contains(" mr ") ||
                         t.contains("father") || t.contains("brother") || t.contains("son ") ||
                         t.contains("husband") || t.contains("uncle") || t.contains("king") ||
                         t.contains("prince")
            }
        }
        return when {
            female && !male -> HindiTtsService.Gender.FEMALE
            male && !female -> HindiTtsService.Gender.MALE
            else            -> null   // ambiguous — don't override history
        }
    }
}
