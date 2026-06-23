package com.example.nihongolens

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * HindiTtsService — Zero-latency Hindi TTS using Android built-in engine.
 *
 * Why Android TTS instead of Kokoro HTTP:
 *  - Zero network latency — speaks instantly, no WAV download
 *  - No gap between sentences — engine queues utterances internally
 *  - Runs on-device, fully offline
 *  - Hindi voice available via Google TTS (install hi_IN voice pack)
 *
 * Gender detection via AudioRecord FFT:
 *  - Tablet mic picks up speaker audio from tablet speakers
 *  - FFT on 100ms windows → find dominant frequency peak
 *  - Male fundamental: 85-165 Hz  → sid=33 (hm_omega)
 *  - Female fundamental: 165-350 Hz → sid=31 (hf_alpha)
 *  - Smoothed over 8 windows, paused during TTS (avoid self-detection)
 *
 * Subtitle sync: subtitle updates immediately on translation.
 * TTS speaks at 1.5x default speed, adjustable 1x-4x.
 */
object HindiTtsService {

    private const val TAG = "HindiTTS"

    enum class Gender  { AUTO, MALE, FEMALE }
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CURIOUS }

    @JvmField @Volatile var enabled           = false
    @JvmField @Volatile var selectedGender    = Gender.AUTO
    @Volatile var ttsSpeedMultiplier          = 1.5f
    @Volatile var detectedGender              = Gender.MALE
    @Volatile var isSpeaking                  = false

    private var tts: TextToSpeech?  = null
    private var ttsReady            = false
    private var ctx: Context?       = null
    private val mainHandler         = Handler(Looper.getMainLooper())
    private val scope               = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cachedHindiVoices   = listOf<android.speech.tts.Voice>()

    // Dedup
    private var lastSpokenNorm = ""
    private var uttId          = 0

    // Gender
    private val genderHistory  = ArrayDeque<Gender>()
    private val GENDER_HIST    = 8
    private var genderJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        ctx = context.applicationContext
        initTts(context)
        startGenderDetector()
    }

    private fun initTts(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("hi", "IN"))
                if (res == TextToSpeech.LANG_MISSING_DATA ||
                    res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Hindi voice missing — using default with pitch adjustment")
                    tts?.setLanguage(Locale.ENGLISH)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?)  {
                        isSpeaking = true
                    }
                    override fun onDone(id: String?)   {
                        // Check if more utterances queued — if not, set grace period
                        // Grace period prevents mic from picking up echo of last words
                        if (tts?.isSpeaking == false) {
                            isSpeaking = false
                            speakingUntilMs = System.currentTimeMillis() + 2_000L
                        }
                        // If more queued, isSpeaking stays true until all done
                    }
                    override fun onError(id: String?)  {
                        isSpeaking = false
                        speakingUntilMs = System.currentTimeMillis() + 1_000L
                    }
                })
                ttsReady = true

                // Select specific Hindi voices:
                // Voice I   = mature female, Voice II  = young female
                // Voice III = mature male,   Voice IV  = young male
                // We use Voice II (young female) and Voice IV (young male)
                val allVoices = tts?.voices ?: emptySet()
                val hindiVoices = allVoices
                    .filter { it.locale.language == "hi" && !it.isNetworkConnectionRequired }
                    .sortedBy { it.name }
                Log.d(TAG, "Hindi voices available: ${hindiVoices.map { it.name }}")
                // Cache all hindi voices for dynamic switching in speak()
                cachedHindiVoices = hindiVoices
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    @Volatile private var speakingUntilMs = 0L   // grace period after TTS ends

    fun isSuppressed() = isSpeaking || System.currentTimeMillis() < speakingUntilMs

    // ── Speak ─────────────────────────────────────────────────────────────────

    fun speak(hindi: String) {
        if (!enabled || !ttsReady || hindi.isBlank()) return
        val n = hindi.trim().replace(Regex("\\s+"), " ")
        if (n == lastSpokenNorm) return
        lastSpokenNorm = n

        val engine = tts ?: return
        val emotion = detectEmotion(hindi)
        val speed = (emotionSpeed(emotion) * ttsSpeedMultiplier).coerceIn(0.5f, 4.0f)
        val gender = if (selectedGender == Gender.AUTO) detectedGender else selectedGender
        val pitch  = if (gender == Gender.FEMALE) 0.80f else 1.00f

        // Select specific voice by name:
        // Voice I   = mature female  (index 0)
        // Voice II  = young female   (index 1) ← use for FEMALE
        // Voice III = mature male    (index 2)
        // Voice IV  = young male     (index 3) ← use for MALE
        if (cachedHindiVoices.isNotEmpty()) {
            val targetName = when (gender) {
                Gender.FEMALE -> "Voice II"    // young female
                else          -> "Voice IV"    // young male
            }
            val voice = cachedHindiVoices.firstOrNull { it.name.contains(targetName) }
                ?: cachedHindiVoices.firstOrNull { it.name.contains(
                    if (gender == Gender.FEMALE) "Voice I" else "Voice III") }
                ?: cachedHindiVoices.first()
            engine.voice = voice
            Log.d(TAG, "Voice: ${voice.name} gender=$gender")
        } else {
            engine.setPitch(pitch)
        }

        engine.setSpeechRate(speed)
        engine.setPitch(pitch)

        val id = "utt_${uttId++}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
            // STREAM_NOTIFICATION = 5 — excluded from Live Captions audio capture
            // Live Captions only captures STREAM_MUSIC (3) and STREAM_GAME
            putString(TextToSpeech.Engine.KEY_PARAM_STREAM, "5")
        }

        // Mark as speaking BEFORE queuing — ensures isSuppressed() is true
        // immediately so LiveCaptionReader blocks before TTS even starts
        isSpeaking = true

        engine.speak(hindi, TextToSpeech.QUEUE_ADD, params, id)
        Log.d(TAG, "TTS speak speed=$speed pitch=$pitch gender=$gender '${hindi.take(30)}'")
    }

    fun stopCurrent() {
        tts?.stop()
        isSpeaking = false
        lastSpokenNorm = ""
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) { tts?.stop(); isSpeaking = false; lastSpokenNorm = "" }
    }

    fun setGender(g: Gender) {
        selectedGender = g
        if (g != Gender.AUTO) { genderJob?.cancel(); genderJob = null }
        else startGenderDetector()
    }

    fun setSpeedMultiplier(m: Float) { ttsSpeedMultiplier = m.coerceIn(0.5f, 4.0f) }

    fun destroy() {
        genderJob?.cancel()
        tts?.stop(); tts?.shutdown(); tts = null
        ttsReady = false
        scope.cancel()
    }

    // ── Gender detection via FFT on mic audio ─────────────────────────────────
    // Tablet mic picks up audio from tablet speakers.
    // FFT reveals fundamental frequency of the speaker's voice.
    // Gender detection via GenderAnalyzer — uses internal audio (MediaProjection), not mic.
    // GenderAnalyzer.feed() is called by SpeechCaptureService with raw PCM from the video.
    // HindiTtsService.detectedGender is updated directly by GenderAnalyzer when AUTO mode.
    private fun startGenderDetector() {
        Log.d(TAG, "Gender detection: passive — GenderAnalyzer receives PCM from SpeechCaptureService")
    }


    // ── Emotion ───────────────────────────────────────────────────────────────

    private fun detectEmotion(text: String): Emotion {
        val t = text.trim()
        if (t.endsWith("!") || t.endsWith("！")) return Emotion.EXCITED
        if (t.endsWith("?") || t.endsWith("？")) return Emotion.CURIOUS
        val l = t.lowercase()
        if (listOf("दुखी","उदास","sad","cry","sorry").any    { l.contains(it) }) return Emotion.SAD
        if (listOf("गुस्सा","angry","hate","damn").any        { l.contains(it) }) return Emotion.ANGRY
        if (listOf("वाह","wow","amazing","खुश","love").any    { l.contains(it) }) return Emotion.HAPPY
        return Emotion.NEUTRAL
    }

    private fun emotionSpeed(e: Emotion) = when (e) {
        Emotion.EXCITED -> 1.10f
        Emotion.HAPPY   -> 1.05f
        Emotion.CURIOUS -> 0.97f
        Emotion.SAD     -> 0.88f
        Emotion.ANGRY   -> 1.08f
        Emotion.NEUTRAL -> 1.00f
    }
}
