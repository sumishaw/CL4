package com.example.nihongolens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/**
 * LiveCaptionReader v6 — KEY FIX: use windows list, NOT rootInActiveWindow
 *
 * rootInActiveWindow returns the FOCUSED window (Termux, browser etc.)
 * We must iterate windows list and find the com.google.android.as window specifically.
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG = "LiveCaptionReader"

        private val LIVE_CAPTION_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.tts",
        )

        private const val TRANSLATE_URL   = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT = 2_000
        private const val READ_TIMEOUT    = 12_000

        // 500ms debounce — short enough to catch rapid dialogue,
        // long enough for Live Captions to finish word-correction on each line
        private const val DEBOUNCE_MS     = 500L

        // Force-send after this long even if Live Captions keeps updating
        // Prevents infinite deferral during fast continuous speech
        private const val MAX_WAIT_MS     = 3_000L

        @Volatile var isRunning  = false
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope         = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob:    Job? = null
    private var forceJob:      Job? = null   // fires after MAX_WAIT_MS regardless of updates
    private var translateJob:  Job? = null
    private var lastSentText   = ""
    private var lastHindiOut   = ""
    private var lastDetectedLang = ""        // track language switches
    // Queue size 2 only — CT2 takes ~500ms-2s per translation.
    // Deep queue means stale translations arrive long after speech moved on.
    // We always clear and replace with newest so CT2 only ever translates
    // the most recent content, never a backlog of outdated sentences.
    private val translateQueue = LinkedBlockingQueue<String>(2)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance  = this
        isRunning = true

        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            )
            info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            info.flags               = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
            // Do NOT set packageNames — we need events from ALL packages
            // (video player, browser etc.) because TYPE_WINDOWS_CHANGED fires
            // with the foreground app's package, not com.google.android.as
            info.packageNames = null
        }

        startTranslateWorker()
        // Clear cached state from previous session
        lastSentText            = ""
        lastHindiOut            = ""
        lastTranslatedSentence  = ""
        lastDetectedLang        = ""
        lastRawCaption          = ""
        lastSentText2           = ""
        captionWasVisible       = false
        SpeechCaptureService.latestHindi   = ""
        SpeechCaptureService.latestEnglish = ""
        Log.i(TAG, "LiveCaptionReader v6 connected")
        scope.launch(Dispatchers.Main) {
            MainActivity.instance?.onLiveCaptionReaderConnected()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return

        // CRITICAL: Do NOT filter by event.packageName here.
        // TYPE_WINDOWS_CHANGED fires with the foreground app's package (video player),
        // not com.google.android.as — filtering by package causes us to miss most events.
        // We scan the windows list ourselves inside readFromCaptionWindow() to find
        // the Live Captions window regardless of which package triggered the event.
        val sendText = readFromCaptionWindow() ?: return
        Log.d(TAG, "Caption: $sendText")
        scheduleTranslation(sendText)
    }

    private var lastTranslatedSentence = ""

    private var lastRawCaption    = ""
    private var lastSentText2     = ""
    private var captionWasVisible = false

    private fun readFromCaptionWindow(): String? {
        val allWindows = try { windows } catch (_: Exception) { return null }

        // Find Live Captions window
        var captionRoot: android.view.accessibility.AccessibilityNodeInfo? = null
        if (!allWindows.isNullOrEmpty()) {
            for (window in allWindows) {
                val root = try { window.root } catch (_: Exception) { continue } ?: continue
                if (root.packageName?.toString() in LIVE_CAPTION_PACKAGES) {
                    captionRoot = root; break
                }
                root.recycle()
            }
        }

        // Window gone → silence → reset everything
        if (captionRoot == null) {
            if (captionWasVisible) {
                captionWasVisible = false
                lastRawCaption    = ""
                lastSentText2     = ""
                Log.d(TAG, "LC gone — reset")
            }
            return null
        }

        // Collect text
        val nodes = mutableListOf<String>()
        collectAllText(captionRoot, nodes)
        captionRoot.recycle()

        val fullText = nodes
            .filter { isValidCaption(it) }
            .filter { !isStaticUiLabel(it) }
            .maxByOrNull { it.length }
            ?.trim() ?: return null

        // Fresh session after silence
        if (!captionWasVisible) {
            captionWasVisible = true
            lastRawCaption    = ""
            lastSentText2     = ""
            Log.d(TAG, "LC appeared — fresh session")
        }

        // No raw change at all
        if (fullText == lastRawCaption) return null
        lastRawCaption = fullText

        // Extract the genuinely NEW portion since last read.
        // Live Captions appends within a session — new content is always a suffix.
        val newPart: String
        if (fullText.startsWith(lastSentText2) && lastSentText2.isNotEmpty()) {
            // Pure append — extract only what's new
            newPart = fullText.substring(lastSentText2.length).trim()
        } else {
            // LC corrected earlier words or reset — send last ~150 chars for context
            newPart = fullText.takeLast(150).trim()
        }

        if (newPart.length < 3) return null
        if (newPart == lastSentText2) return null
        lastSentText2 = fullText  // track full text, not just tail
        return newPart
    }


    private fun collectAllText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotBlank()) out.add(text)
        for (i in 0 until node.childCount) collectAllText(node.getChild(i), out)
    }

    private fun isStaticUiLabel(text: String): Boolean {
        val lower = text.lowercase()
        // Drop Live Captions UI locale strings e.g. "English (United States)"
        // These always match the pattern: "Word (Word)" and are short
        if (text.matches(Regex("[A-Za-zÀ-ÿ ]+\\([A-Za-zÀ-ÿ ]+\\)")) && text.length < 60) return true
        if (lower.contains("united states") || lower.contains("united kingdom")) return true
        if (lower.contains("simplified") || lower.contains("traditional")) return true
        // Drop single words with no space — Live Captions UI buttons, not captions
        if (!text.contains(" ") && text.length < 15) return true
        return false
    }

    private fun isValidCaption(text: String): Boolean {
        if (text.length < 3 || text.length > 400) return false
        val letters = text.count { it.isLetter() }
        if (letters < text.length * 0.35) return false
        if (text.contains("http") || text.contains("www.")) return false
        if (text.contains("com.android") || text.contains("com.google")) return false
        // Reject locale strings like "English (United States)"
        if (text.matches(Regex(".*\\(.*\\).*")) && text.length < 50) return false
        return true
    }

    private fun scheduleTranslation(sendText: String) {
        // Detect language switch from the send text script
        val scriptNow = detectScript(sendText)
        if (scriptNow != lastDetectedLang && lastDetectedLang.isNotEmpty()) {
            lastSentText           = ""
            lastTranslatedSentence = ""
            lastHindiOut           = ""
            lastRawCaption         = ""
            lastSentText2          = ""
            captionWasVisible      = false
        }
        lastDetectedLang = scriptNow

        // Debounce: cancel and restart 500ms timer.
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            val toSend = lastSentText2.ifBlank { sendText }
            enqueueForTranslation(toSend)
        }

        // Force-send: guarantee output every MAX_WAIT_MS during continuous speech.
        if (forceJob == null || forceJob?.isActive == false) {
            forceJob = scope.launch {
                delay(MAX_WAIT_MS)
                pendingJob?.cancel()
                val toSend = lastSentText2.ifBlank { sendText }
                enqueueForTranslation(toSend)
            }
        }
    }

    private fun enqueueForTranslation(text: String) {
        forceJob?.cancel()
        forceJob = null
        if (text.isBlank()) return
        // Always clear stale items and insert the freshest text.
        // Never translate a backlog — subtitle must match current speech.
        translateQueue.clear()
        translateQueue.offer(text)
        lastSentText = text
    }

    /** Coarse script detection for language-switch tracking only. */
    private fun detectScript(text: String): String {
        for (c in text) {
            val cp = ord(c)
            if (cp in 0x3040..0x30FF) return "ja"
            if (cp in 0x4E00..0x9FFF) return "zh"
            if (cp in 0xAC00..0xD7AF) return "ko"
            if (cp in 0x0600..0x06FF) return "ar"
            if (cp in 0x0400..0x04FF) return "ru"
            if (cp in 0x0900..0x097F) return "hi"
        }
        return "latin"
    }

    private fun ord(c: Char) = c.code

    private fun startTranslateWorker() {
        translateJob = scope.launch {
            while (isActive) {
                val text = withContext(Dispatchers.IO) {
                    try { translateQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { null }
                } ?: continue

                val hindi = translate(text) ?: continue
                if (hindi.isBlank()) continue
                // Don't block on lastHindiOut equality — short repeated lines
                // (e.g. "हाँ।" "ठीक है।") are valid subtitles in rapid dialogue

                Log.i(TAG, "✓ ${text.take(40)} → ${hindi.take(40)}")
                SpeechCaptureService.latestHindi   = hindi
                SpeechCaptureService.latestEnglish = text

                withContext(Dispatchers.Main) {
                    OverlayService.updateText(text, hindi)
                    MainActivity.instance?.onTranslation(text, hindi, hindi)
                }
            }
        }
    }

    private fun translate(text: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(TRANSLATE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput       = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT
            val body = """{"text":${JSONObject.quote(text)},"src":"auto","tgt":"hi"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONObject(resp).optString("text", "").trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Translate error: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() { Log.w(TAG, "Interrupted") }

    override fun onDestroy() {
        isRunning = false; instance = null
        pendingJob?.cancel(); forceJob?.cancel()
        translateJob?.cancel(); scope.cancel()
        super.onDestroy()
    }
}
