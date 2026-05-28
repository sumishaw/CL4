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
 * LiveCaptionReader — reads ONLY Android Live Captions text.
 *
 * KEY FIX: Only processes events from Live Captions packages.
 * All other apps (YouTube comments, Chrome, Claude.ai etc.) are ignored.
 *
 * Live Captions package on this device (Lenovo Tab P12 / Android 15):
 *   com.google.android.as  (Android System Intelligence)
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG = "LiveCaptionReader"

        // ONLY these packages — everything else is ignored
        private val LIVE_CAPTION_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.accessibility.caption",
            "com.google.android.accessibility.captions",
        )

        // View IDs used by Live Captions text view
        private val CAPTION_VIEW_IDS = listOf(
            "caption_text",
            "captiontext",
            "live_caption_text",
            "transcript_text",
            "captionwindow",
            "caption_window",
        )

        private const val TRANSLATE_URL   = "http://127.0.0.1:8765/translate_text"
        private const val CONNECT_TIMEOUT = 2_000
        private const val READ_TIMEOUT    = 12_000
        private const val DEBOUNCE_MS     = 400L

        @Volatile var isRunning       = false
        @Volatile var lastCaptionText = ""
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingJob: Job? = null
    private var lastSentText = ""
    private var lastHindiOut = ""
    private val translateQueue = LinkedBlockingQueue<String>(4)
    private var translateJob:  Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance  = this
        isRunning = true

        // CRITICAL: restrict to ONLY Live Captions packages
        // This prevents reading text from YouTube, Chrome, Claude, etc.
        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            )
            info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            info.flags               = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            // ONLY monitor Live Captions packages — not all apps
            info.packageNames        = LIVE_CAPTION_PACKAGES.toTypedArray()
        }

        startTranslateWorker()
        Log.i(TAG, "LiveCaptionReader connected — monitoring: $LIVE_CAPTION_PACKAGES")
        scope.launch(Dispatchers.Main) {
            MainActivity.instance?.onLiveCaptionReaderConnected()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return

        // Double-check package — safety guard
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in LIVE_CAPTION_PACKAGES) {
            // Not a Live Captions event — ignore completely
            return
        }

        val text = extractCaptionText(event) ?: return
        val clean = text.trim()

        // Must be plausible speech — not single chars, not too long
        if (clean.length < 2 || clean.length > 300) return
        if (clean == lastCaptionText) return

        lastCaptionText = clean
        scheduleTranslation(clean)
    }

    // ── Text extraction — only from Live Captions nodes ──────────────────────

    private fun extractCaptionText(event: AccessibilityEvent): String? {
        // Strategy 1: event text directly
        val evText = event.text
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.length > 1 }
            ?.joinToString(" ")
        if (!evText.isNullOrBlank()) return evText

        // Strategy 2: walk tree but ONLY look for known caption view IDs
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return null
        return findCaptionNode(root)?.text?.toString()?.trim()
    }

    private fun findCaptionNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text   = node.text?.toString()?.trim() ?: ""

        // Only match known Live Captions view IDs
        if (CAPTION_VIEW_IDS.any { viewId.contains(it) } && text.isNotBlank()) {
            return node
        }

        for (i in 0 until node.childCount) {
            val found = findCaptionNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    // ── Debounce + queue ──────────────────────────────────────────────────────

    private fun scheduleTranslation(text: String) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            if (text == lastCaptionText && text != lastSentText) {
                lastSentText = text
                if (translateQueue.size >= 4) translateQueue.poll()
                translateQueue.offer(text)
            }
        }
    }

    private fun startTranslateWorker() {
        translateJob = scope.launch {
            while (isActive) {
                val text = withContext(Dispatchers.IO) {
                    try { translateQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { null }
                } ?: continue

                val hindi = translate(text) ?: continue
                if (hindi.isBlank() || hindi == lastHindiOut) continue
                lastHindiOut = hindi

                Log.d(TAG, "[$text] → [$hindi]")
                SpeechCaptureService.latestHindi   = hindi
                SpeechCaptureService.latestEnglish = text

                withContext(Dispatchers.Main) {
                    OverlayService.updateText(text, hindi)
                    MainActivity.instance?.onTranslation(text, hindi, hindi)
                }
            }
        }
    }

    // ── HTTP translation ──────────────────────────────────────────────────────

    private fun translate(text: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(TRANSLATE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput       = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT

            val body = """{"text":${JSONObject.quote(text)},"src":"en","tgt":"hi"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != 200) {
                Log.w(TAG, "Server ${conn.responseCode}")
                return null
            }
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
        isRunning = false
        instance  = null
        pendingJob?.cancel()
        translateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
