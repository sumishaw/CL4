package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicLong

/**
 * OverlayService — Progressive word-by-word Hindi subtitle overlay
 *
 * Display model:
 *  - Each translated sentence streams word by word onto the overlay
 *  - Words appear at WORD_INTERVAL_MS pace (natural reading speed)
 *  - Overlay has 2 lines max — fills line 1, then line 2
 *  - When both lines full OR sentence complete → hold for HOLD_MS then clear
 *  - Next sentence in FIFO queue starts immediately after clear
 *  - FIFO + token: no drops, no duplicates, no stale items after LC gone
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""
        @Volatile private var pushCallback:  ((String, String) -> Unit)? = null
        @Volatile private var clearCallback: (() -> Unit)?               = null

        fun updateText(original: String, hindi: String) {
            latestOriginal = original; latestHindi = hindi
            pushCallback?.invoke(original, hindi)
        }
        fun clearQueue() { clearCallback?.invoke() }
    }

    // Timing
    private val WORD_INTERVAL_MS = 300L   // ms between each word appearing
    private val HOLD_MS          = 2_000L // hold completed subtitle before clearing
    private val SILENCE_MS       = 8_000L // fade after speech ends

    // FIFO + token
    private val tokenCounter  = AtomicLong(0)
    private var expectedToken = 0L
    data class Item(val token: Long, val words: List<String>)
    private val queue = ArrayDeque<Item>()

    // Progressive display state
    private var currentWords   = listOf<String>()  // words of current sentence
    private var wordIndex      = 0                  // next word to show
    private var displayedText  = ""                 // text currently on screen
    private var isProgressing  = false              // word-by-word ticker running
    private var currentToken   = -1L

    private var wordRunnable:    Runnable? = null
    private var holdRunnable:    Runnable? = null
    private var silenceRunnable: Runnable? = null

    private var windowManager: WindowManager?              = null
    private var line1View:     TextView?                   = null
    private var line2View:     TextView?                   = null
    private var containerView: View?                       = null
    private var overlayView:   View?                       = null
    private var params:        WindowManager.LayoutParams? = null
    private val handler        = Handler(Looper.getMainLooper())

    @Volatile private var running   = true
    @Volatile private var viewAdded = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.post { if (running) buildOverlay() }
        pushCallback  = { _, hindi -> handler.post { onPush(hindi) } }
        clearCallback = { handler.post { onClear() } }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        pushCallback = null; clearCallback = null
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── Push / Clear ──────────────────────────────────────────────────────────

    private fun onPush(hindi: String) {
        if (hindi.isBlank()) return
        val words = hindi.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return

        val token = tokenCounter.incrementAndGet()
        queue.addLast(Item(token, words))
        reschedSilence()

        if (!isProgressing) startNext()
    }

    private fun onClear() {
        // Invalidate all pending — tokens below expectedToken are silently skipped
        expectedToken = tokenCounter.get() + 1
        queue.clear()
        cancelAll()
        isProgressing = false
        currentToken  = -1L
        clearDisplay()
    }

    // ── Progressive display ───────────────────────────────────────────────────

    private fun startNext() {
        cancelAll()

        // Drain stale
        while (queue.isNotEmpty() && queue.first().token < expectedToken)
            queue.removeFirst()

        if (queue.isEmpty()) {
            isProgressing = false
            return
        }

        val item = queue.removeFirst()
        if (item.token < expectedToken) { startNext(); return }

        currentWords  = item.words
        currentToken  = item.token
        wordIndex     = 0
        isProgressing = true
        displayedText = ""

        clearDisplay()
        tickWord()
    }

    private fun tickWord() {
        wordRunnable = null
        if (!running) return
        if (currentToken < expectedToken) { isProgressing = false; clearDisplay(); return }

        if (wordIndex >= currentWords.size) {
            // Sentence complete — hold then advance
            scheduleHold()
            return
        }

        // Append next word
        displayedText = if (displayedText.isEmpty()) currentWords[wordIndex]
                        else "$displayedText ${currentWords[wordIndex]}"
        wordIndex++

        updateDisplay(displayedText)

        // Check if 2 lines are filled (estimate by char count)
        // Approx 20 Hindi chars per line at 20sp on a tablet
        if (displayedText.length >= 42 && wordIndex < currentWords.size) {
            // Lines full but more words remain — hold current, then show rest as new block
            val remaining = currentWords.subList(wordIndex, currentWords.size)
            if (remaining.isNotEmpty()) {
                val cap = currentToken
                holdRunnable = Runnable {
                    holdRunnable = null
                    if (!running || cap < expectedToken) { startNext(); return@Runnable }
                    // Push remaining words as a continuation item at front of display
                    currentWords  = remaining
                    wordIndex     = 0
                    displayedText = ""
                    clearDisplay()
                    tickWord()
                }
                handler.postDelayed(holdRunnable!!, HOLD_MS)
                return
            }
        }

        // Schedule next word
        wordRunnable = Runnable { tickWord() }
        handler.postDelayed(wordRunnable!!, WORD_INTERVAL_MS)
    }

    private fun scheduleHold() {
        val cap = currentToken
        holdRunnable = Runnable {
            holdRunnable = null
            if (!running) return@Runnable
            if (cap < expectedToken) { isProgressing = false; clearDisplay(); startNext(); return@Runnable }
            clearDisplay()
            startNext()   // move to next queued sentence
        }
        handler.postDelayed(holdRunnable!!, HOLD_MS)
    }

    // ── Display ───────────────────────────────────────────────────────────────

    private fun updateDisplay(text: String) {
        val tv1 = line1View ?: return
        val tv2 = line2View ?: return

        // Split text across 2 lines at natural word boundary near midpoint
        val words = text.split(" ")
        if (words.size <= 3) {
            tv1.text = text
            tv2.text = ""
        } else {
            val mid = words.size / 2
            tv1.text = words.take(mid).joinToString(" ")
            tv2.text = words.drop(mid).joinToString(" ")
        }

        // Fade in container if not visible
        if ((containerView?.alpha ?: 0f) < 0.5f) {
            containerView?.animate()?.cancel()
            containerView?.alpha = 0f
            containerView?.animate()?.alpha(1f)?.setDuration(150)?.start()
        }
    }

    private fun clearDisplay() {
        line1View?.text = ""
        line2View?.text = ""
        containerView?.animate()?.cancel()
        containerView?.alpha = 0f
        displayedText = ""
    }

    private fun cancelAll() {
        wordRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable?.let { handler.removeCallbacks(it) }
        wordRunnable = null; holdRunnable = null
    }

    private fun reschedSilence() {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            if (!running || queue.isNotEmpty() || isProgressing) return@Runnable
            clearDisplay()
        }
        handler.postDelayed(silenceRunnable!!, SILENCE_MS)
    }

    // ── Overlay window ────────────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val sw = resources.displayMetrics.widthPixels

            // Two separate TextViews stacked — line 1 top, line 2 bottom
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background  = android.graphics.drawable.GradientDrawable().apply {
                    shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(190, 0, 0, 0))
                }
                setPadding(dp(14), dp(10), dp(14), dp(10))
                alpha = 0f
            }

            fun makeTextView() = TextView(this).apply {
                typeface  = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.WHITE)
                setShadowLayer(10f, 0f, 2f, Color.BLACK)
                maxLines  = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text      = ""
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val tv1 = makeTextView()
            val tv2 = makeTextView().apply {
                setPadding(0, dp(4), 0, 0)
            }

            container.addView(tv1)
            container.addView(tv2)

            line1View     = tv1
            line2View     = tv2
            containerView = container
            overlayView   = container

            params = WindowManager.LayoutParams(
                (sw * 0.92).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(90) }

            // Draggable
            var sx = 0f; var sy = 0f; var ix = 0; var iy = 0
            container.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ix = p.x; iy = p.y }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = ix + (ev.rawX - sx).toInt()
                        p.y = iy - (ev.rawY - sy).toInt()
                        if (viewAdded) try { windowManager?.updateViewLayout(overlayView, p) }
                        catch (_: Exception) {}
                    }
                }
                true
            }

            windowManager?.addView(overlayView, params)
            viewAdded = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "build: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            NotificationChannel(CHANNEL_ID, "Caption Lens Overlay", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Hindi subtitle overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true).build()
}
