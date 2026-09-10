package io.github.rvbcrs.wallmode

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.PatternMatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.io.File

/** Opt-in native checks. Browser intents are intercepted; no donation page or payment is opened. */
internal object SupportInstrumentationChecks {
    fun run(instrumentation: Instrumentation) {
        fun onMain(block: () -> Unit) {
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        val prefs = KioskPreferences(instrumentation.targetContext)
        val before = prefs.load()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as SettingsActivity
        var dialog: AlertDialog? = null
        val lockState = activity.getSystemService(ActivityManager::class.java).lockTaskModeState
        val locked = lockState != ActivityManager.LOCK_TASK_MODE_NONE
        try {
            onMain { activity.findViewById<View>(R.id.nav_system).performClick() }
            instrumentation.awaitNextDraw(activity.window.decorView)
            onMain {
                val card = activity.findViewById<View>(R.id.support_card)
                val section = activity.findViewById<ViewGroup>(R.id.section_system)
                check(section.getChildAt(0) === card) { "Support must lead the System tab" }
                listOf(R.id.btn_support_coffee, R.id.btn_support_github).forEach { id ->
                    val button = card.findViewById<TextView>(id)
                    check(button.hasOnClickListeners() && button.height >= 48 * button.resources.displayMetrics.density)
                    check(button.width <= card.width && button.layout.lineCount == 1) { "Support button is clipped" }
                    val visible = android.graphics.Rect()
                    check(button.getGlobalVisibleRect(visible) && visible.height() == button.height) {
                        "Support button requires scrolling"
                    }
                }
                // Render only the support card, never the protected dashboard or settings fields.
                val fixture = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                card.draw(Canvas(fixture))
                File(instrumentation.targetContext.getExternalFilesDir(null), "support-fixture-check.png")
                    .outputStream().use { fixture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                fixture.recycle()
            }
            listOf(R.string.support_coffee to SupportLinks.COFFEE,
                R.string.support_github to SupportLinks.GITHUB).forEach { (label, url) ->
                val uri = Uri.parse(url)
                val filter = IntentFilter(Intent.ACTION_VIEW).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addDataScheme(uri.scheme)
                    addDataAuthority(uri.host, null)
                    addDataPath(uri.path, PatternMatcher.PATTERN_LITERAL)
                }
                val monitor = instrumentation.addMonitor(filter, null, true)
                try {
                    onMain { dialog = activity.showSupportLink(label, url) }
                    instrumentation.waitForIdleSync()
                    onMain {
                        val shown = checkNotNull(dialog)
                        val message = shown.findViewById<TextView>(android.R.id.message)!!
                        check(url in message.text && message.isTextSelectable)
                        check(shown.getButton(AlertDialog.BUTTON_NEUTRAL).text == activity.getString(R.string.support_copy_link))
                        shown.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                        if (locked) {
                            check(monitor.hits == 0 && shown.isShowing)
                            check(message.text.toString() == activity.getString(R.string.support_link_kiosk, url))
                        } else {
                            check(monitor.hits == 1 && !shown.isShowing) { "Support URL did not use external intent" }
                        }
                        shown.dismiss()
                    }
                } finally { instrumentation.removeMonitor(monitor) }
            }
            if (!locked) {
                val missing = "wallmode-test-missing-browser://support"
                onMain { dialog = activity.showSupportLink(R.string.support_coffee, missing) }
                instrumentation.waitForIdleSync()
                onMain {
                    val shown = checkNotNull(dialog)
                    shown.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    val message = shown.findViewById<TextView>(android.R.id.message)!!
                    check(shown.isShowing && message.isTextSelectable)
                    check(message.text.toString() == activity.getString(R.string.support_link_unavailable, missing))
                }
            }
            onMain {
                check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                check(activity.getSystemService(ActivityManager::class.java).lockTaskModeState == lockState)
                check(prefs.load() == before) { "Support changed dashboard or settings" }
            }
        } finally {
            onMain { dialog?.dismiss(); activity.finish() }
        }
    }
}
