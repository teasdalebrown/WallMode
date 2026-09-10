package io.github.rvbcrs.wallmode

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

/** A native notice above the current page, without changing its display or idle state. */
internal class BannerOverlay(
    private val root: FrameLayout,
    private val onAction: (noticeId: String, actionId: String) -> Boolean
) {
    private val handler = Handler(Looper.getMainLooper())
    private val card = LayoutInflater.from(root.context)
        .inflate(R.layout.view_notification_banner, root, false)
    private val level = card.findViewById<TextView>(R.id.banner_level)
    private val title = card.findViewById<TextView>(R.id.banner_title)
    private val message = card.findViewById<TextView>(R.id.banner_message)
    private val error = card.findViewById<TextView>(R.id.banner_error)
    private val action = card.findViewById<MaterialButton>(R.id.banner_action)
    private val scroll = card.findViewById<ScrollView>(R.id.banner_scroll)
    private val body = card.findViewById<View>(R.id.banner_body)
    private var timeout: Runnable? = null
    private var tone: ToneGenerator? = null
    private var toneRelease: Runnable? = null
    private var atBottom = true
    internal var currentNotice: BannerNotice? = null
        private set
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (currentNotice != null) updateBounds()
    }

    init {
        root.addView(card)
        root.addOnLayoutChangeListener(layoutListener)
        card.findViewById<ImageButton>(R.id.banner_close).setOnClickListener { dismiss() }
    }

    fun show(notice: BannerNotice, corner: AdminButtonCorner) {
        cancelTouch()
        timeout?.let(handler::removeCallbacks)
        stopTone()
        card.animate().withEndAction(null).cancel()
        currentNotice = notice
        atBottom = corner == AdminButtonCorner.TOP_LEFT || corner == AdminButtonCorner.TOP_RIGHT
        val (label, accent) = when (notice.level) {
            BannerLevel.INFO -> R.string.banner_level_info to Color.rgb(100, 219, 230)
            BannerLevel.SUCCESS -> R.string.banner_level_success to Color.rgb(118, 229, 174)
            BannerLevel.WARNING -> R.string.banner_level_warning to Color.rgb(255, 208, 137)
            BannerLevel.ERROR -> R.string.banner_level_error to Color.rgb(255, 157, 164)
        }
        level.setText(label)
        level.setTextColor(accent)
        card.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(22, 47, 57), Color.rgb(11, 21, 30))
        ).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.argb(130, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        card.clipToOutline = true
        title.text = notice.title
        title.visibility = if (notice.title.isBlank()) View.GONE else View.VISIBLE
        message.text = notice.message
        error.visibility = View.GONE
        action.backgroundTintList = ColorStateList.valueOf(accent)
        action.text = notice.action?.label.orEmpty()
        action.visibility = if (notice.action == null) View.GONE else View.VISIBLE
        action.isEnabled = true
        action.setOnClickListener {
            val selected = notice.action ?: return@setOnClickListener
            if (currentNotice !== notice) return@setOnClickListener
            action.isEnabled = false
            val sent = onAction(notice.id, selected.id)
            if (currentNotice !== notice) return@setOnClickListener
            if (sent) {
                dismiss(notice.id)
            } else {
                action.isEnabled = true
                error.visibility = View.VISIBLE
                updateBounds()
                scroll.post { if (currentNotice === notice) scroll.smoothScrollTo(0, error.top) }
            }
        }
        scroll.scrollTo(0, 0)
        updateBounds()
        card.visibility = View.VISIBLE
        card.bringToFront()
        card.alpha = 0f
        card.translationY = dp(if (atBottom) 12 else -12).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(180)
            .setInterpolator(DecelerateInterpolator()).start()
        timeout = Runnable { if (currentNotice === notice) dismiss(notice.id) }
            .also { handler.postDelayed(it, notice.seconds * 1_000L) }
        if (notice.sound) playTone()
    }

    fun dismiss(id: String? = null) {
        if (id != null && currentNotice?.id != id) return
        cancelTouch()
        timeout?.let(handler::removeCallbacks)
        timeout = null
        currentNotice = null
        card.animate().withEndAction(null).cancel()
        card.visibility = View.GONE
        action.setOnClickListener(null)
        stopTone()
    }

    fun containsTouch(rawX: Float, rawY: Float): Boolean {
        if (currentNotice == null || card.visibility != View.VISIBLE) return false
        val location = IntArray(2)
        card.getLocationOnScreen(location)
        return rawX >= location[0] && rawX < location[0] + card.width &&
            rawY >= location[1] && rawY < location[1] + card.height
    }

    fun dispose() {
        dismiss()
        handler.removeCallbacksAndMessages(null)
        root.removeOnLayoutChangeListener(layoutListener)
        root.removeView(card)
    }

    private fun updateBounds() {
        if (root.width == 0 || root.height == 0) return
        val margin = dp(16)
        val width = minOf(dp(540), root.width - margin * 2).coerceAtLeast(1)
        val gravity = Gravity.CENTER_HORIZONTAL or if (atBottom) Gravity.BOTTOM else Gravity.TOP
        val params = card.layoutParams as FrameLayout.LayoutParams
        if (params.width != width || params.gravity != gravity || params.topMargin != margin) {
            card.layoutParams = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, gravity)
                .apply { setMargins(margin, margin, margin, margin) }
        }
        body.measure(
            View.MeasureSpec.makeMeasureSpec((width - dp(32)).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        // Leave the opposite 96dp admin zone free even on the ThinkSmart's short landscape screen.
        val maxHeight = minOf(dp(240), root.height - dp(128))
        val fixedHeight = dp(64 + if (action.visibility == View.VISIBLE) 56 else 0)
        val bodyHeight = minOf(body.measuredHeight, (maxHeight - fixedHeight).coerceAtLeast(1))
        if (scroll.layoutParams.height != bodyHeight) {
            scroll.layoutParams = scroll.layoutParams.apply { height = bodyHeight }
        }
    }

    private fun playTone() {
        try {
            val player = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone = player
            player.startTone(ToneGenerator.TONE_PROP_BEEP, 160)
            toneRelease = Runnable { stopTone() }.also { handler.postDelayed(it, 220) }
        } catch (_: RuntimeException) {
            // Audio is optional: a device without an available output still shows the notice.
            stopTone()
        }
    }

    private fun cancelTouch() {
        // A finger held on an old button must not activate its replacement on release.
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        card.dispatchTouchEvent(cancel)
        cancel.recycle()
        card.cancelPendingInputEvents()
    }

    private fun stopTone() {
        toneRelease?.let(handler::removeCallbacks)
        toneRelease = null
        tone?.release()
        tone = null
    }

    private fun dp(value: Int): Int = (value * root.resources.displayMetrics.density).roundToInt()
}
