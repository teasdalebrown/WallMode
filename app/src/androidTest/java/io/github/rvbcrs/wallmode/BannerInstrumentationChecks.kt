package io.github.rvbcrs.wallmode

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import java.io.File

/** Runs only with -e bannerUi true. Uses a fake reply callback; never sends an HA action. */
internal object BannerInstrumentationChecks {
    fun run(instrumentation: Instrumentation) {
        fun onMain(block: () -> Unit) {
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        val settingsBefore = KioskPreferences(instrumentation.targetContext).load()
        lateinit var overlay: BannerOverlay
        lateinit var root: FrameLayout
        var accept = false
        val replies = mutableListOf<Pair<String, String>>()
        val notice = BannerNotice.validated("banner-test", "Wasmachine", "De was is klaar.",
            level = "success", action = ActionCardAction("confirm", "Bevestigen"))!!
        fun layout() {
            val density = root.resources.displayMetrics.density
            root.measure(View.MeasureSpec.makeMeasureSpec((492 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((328 * density).toInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
        onMain {
            root = FrameLayout(ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_WallMode))
            overlay = BannerOverlay(root) { id, action -> replies += id to action; accept }
            layout()
        }
        try {
            onMain {
                AdminButtonCorner.entries.forEach { corner ->
                    overlay.show(notice.copy(message = "Lange tekst ".repeat(25)), corner)
                    layout()
                    val card = root.findViewById<View>(R.id.notification_banner)
                    val close = root.findViewById<View>(R.id.banner_close)
                    val density = root.resources.displayMetrics.density
                    check(close.width >= 48 * density && close.height >= 48 * density)
                    check(card.left >= 0 && card.right <= root.width && card.bottom <= root.height)
                    if (corner == AdminButtonCorner.TOP_LEFT || corner == AdminButtonCorner.TOP_RIGHT) {
                        check(card.top >= 96 * density) { "Banner covers top admin zone" }
                    } else check(card.bottom <= root.height - 96 * density) { "Banner covers bottom admin zone" }
                }
                BannerLevel.entries.forEach { level ->
                    overlay.show(notice.copy(level = level), AdminButtonCorner.TOP_RIGHT)
                    check(root.findViewById<android.widget.TextView>(R.id.banner_level).text.isNotBlank())
                }
                overlay.show(notice, AdminButtonCorner.TOP_RIGHT)
                layout()
                // Render only these detached fixture views, never the FLAG_SECURE dashboard.
                root.setBackgroundColor(android.graphics.Color.rgb(5, 10, 14))
                val fixture = android.graphics.Bitmap.createBitmap(root.width, root.height,
                    android.graphics.Bitmap.Config.ARGB_8888)
                val banner = root.findViewById<View>(R.id.notification_banner)
                banner.animate().cancel()
                banner.alpha = 1f
                banner.translationY = 0f
                root.draw(android.graphics.Canvas(fixture))
                File(instrumentation.targetContext.getExternalFilesDir(null), "banner-fixture-check.png")
                    .outputStream().use { fixture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                fixture.recycle()
                val card = root.findViewById<View>(R.id.notification_banner)
                val button = root.findViewById<View>(R.id.banner_action)
                val pressedAt = SystemClock.uptimeMillis()
                val held = MotionEvent.obtain(pressedAt, pressedAt, MotionEvent.ACTION_DOWN,
                    button.left + button.width / 2f, button.top + button.height / 2f, 0)
                card.dispatchTouchEvent(held)
                held.recycle()
                overlay.show(notice.copy(id = "replacement"), AdminButtonCorner.TOP_RIGHT)
                check(!button.isPressed) { "Replacement retained old button press" }
                val released = MotionEvent.obtain(pressedAt, pressedAt + 10, MotionEvent.ACTION_UP,
                    button.left + button.width / 2f, button.top + button.height / 2f, 0)
                card.dispatchTouchEvent(released)
                released.recycle()
                check(replies.isEmpty()) { "Held touch answered replacement" }
                overlay.show(notice, AdminButtonCorner.TOP_RIGHT)
                overlay.dismiss("another-notice")
                check(overlay.currentNotice === notice)
                root.findViewById<View>(R.id.banner_action).performClick()
                check(overlay.currentNotice === notice && root.findViewById<View>(R.id.banner_error).visibility == View.VISIBLE)
                accept = true
                root.findViewById<View>(R.id.banner_action).performClick()
                check(overlay.currentNotice == null && replies == listOf("banner-test" to "confirm", "banner-test" to "confirm"))
                overlay.show(notice.copy(seconds = 3), AdminButtonCorner.TOP_RIGHT)
            }
            SystemClock.sleep(1100)
            onMain { overlay.show(notice.copy(seconds = 4), AdminButtonCorner.TOP_RIGHT) }
            SystemClock.sleep(2100)
            onMain { check(overlay.currentNotice != null) { "Old timeout dismissed replacement" } }
            SystemClock.sleep(2100)
            onMain { check(overlay.currentNotice == null) { "Banner failed to expire" } }
        } finally { onMain { overlay.dispose() } }

        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.awaitNextDraw(activity.window.decorView)
            lateinit var beforeUrl: String
            var beforeBrightness = 0f
            onMain {
                beforeUrl = activity.findViewById<WebView>(R.id.web_view).url.orEmpty()
                beforeBrightness = activity.window.attributes.screenBrightness
                activity.handleMqttCommand(WallModeMqttCommand.ShowBanner(notice.copy(sound = true)))
                check(activity.findViewById<WebView>(R.id.web_view).url.orEmpty() == beforeUrl)
                check(activity.window.attributes.screenBrightness == beforeBrightness)
            }
            instrumentation.awaitNextDraw(activity.window.decorView)
            SystemClock.sleep(350)
            onMain {
                // Dim after layout, then dispatch a real close gesture through MainActivity.
                // The banner must consume only its own touch, without waking the display behind it.
                activity.handleMqttCommand(WallModeMqttCommand.SetDisplayMode(MqttContract.DISPLAY_DIM))
                val dimBrightness = activity.window.attributes.screenBrightness
                val close = activity.findViewById<View>(R.id.banner_close)
                val xy = IntArray(2)
                close.getLocationOnScreen(xy)
                val bounds = android.graphics.Rect()
                check(close.isShown && close.width > 0 && close.height > 0 &&
                    activity.findViewById<View>(R.id.notification_banner).getGlobalVisibleRect(bounds) &&
                    bounds.contains(xy[0] + close.width / 2, xy[1] + close.height / 2)) {
                    "Banner close is not a visible touch target"
                }
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val touch = MotionEvent.obtain(down, down + 10, action,
                        xy[0] + close.width / 2f, xy[1] + close.height / 2f, 0)
                    activity.dispatchTouchEvent(touch)
                    touch.recycle()
                }
                check(activity.window.attributes.screenBrightness == dimBrightness) { "Banner close woke display" }
            }
            // Android posts the click callback after the UP event; let that callback run.
            instrumentation.waitForIdleSync()
            onMain {
                check(activity.findViewById<View>(R.id.notification_banner).visibility == View.GONE)
                check(activity.findViewById<WebView>(R.id.web_view).url.orEmpty() == beforeUrl) { "Banner navigated dashboard" }
                activity.handleMqttCommand(WallModeMqttCommand.SetDisplayMode(MqttContract.DISPLAY_SCREENSAVER))
                val ambient = activity.findViewById<View>(R.id.ambient_screensaver)
                val ambientBrightness = activity.window.attributes.screenBrightness
                activity.handleMqttCommand(WallModeMqttCommand.ShowBanner(notice.copy(action = null)))
                check(ambient.visibility == View.VISIBLE && activity.window.attributes.screenBrightness == ambientBrightness)
                activity.handleMqttCommand(WallModeMqttCommand.ClearBanner("banner-test"))
                check(ambient.visibility == View.VISIBLE && activity.window.attributes.screenBrightness == ambientBrightness)
            }
        } finally {
            onMain {
                activity.handleMqttCommand(WallModeMqttCommand.ClearBanner("banner-test"))
                activity.handleMqttCommand(WallModeMqttCommand.SetDisplayMode(MqttContract.DISPLAY_DASHBOARD))
            }
        }
        check(KioskPreferences(instrumentation.targetContext).load() == settingsBefore) { "UI test changed saved settings" }
    }
}
