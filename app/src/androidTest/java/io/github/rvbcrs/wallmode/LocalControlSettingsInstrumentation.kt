package io.github.rvbcrs.wallmode

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Html
import android.view.View
import androidx.core.view.doOnPreDraw
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Idle queues do not guarantee that Android has laid out the next UI frame. */
internal fun Instrumentation.awaitNextDraw(view: View) {
    val ready = CountDownLatch(1)
    runOnMainSync {
        view.doOnPreDraw { ready.countDown() }
        view.invalidate()
    }
    check(ready.await(10, TimeUnit.SECONDS)) { "View did not draw" }
}

/** Real HTTP/Android preferences smoke test; deliberately does not exercise MainActivity callbacks. */
class LocalControlSettingsInstrumentation : Instrumentation() {
    private var designSnapshots = false
    private var bannerUi = false
    private var supportUi = false
    private var presenceUi = false
    private var presencePermissionDenied = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        designSnapshots = arguments?.getString("designSnapshots").toBoolean()
        bannerUi = arguments?.getString("bannerUi").toBoolean()
        supportUi = arguments?.getString("supportUi").toBoolean()
        presenceUi = arguments?.getString("presenceUi").toBoolean()
        presencePermissionDenied = arguments?.getString("presencePermissionDenied").toBoolean()
        start()
    }

    override fun onStart() {
        val storageName = "wallmode_http_test_${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                super.getSharedPreferences(storageName, Context.MODE_PRIVATE)
        }
        val prefs = KioskPreferences(isolated)
        var server: LocalControlServer? = null
        val results = Bundle()
        var resultCode = Activity.RESULT_CANCELED
        var checkpoint = "startup"
        try {
            checkpoint = "native-admin-session"
            checkNativeAdminSession(targetContext)
            val password = UUID.randomUUID().toString()
            val secret = "fixture-secret-${UUID.randomUUID()}"
            val share = "http://192.168.0.247:2283/share/${"s".repeat(64)}"
            prefs.setAdminPassword(password)
            prefs.setMqttPassword(secret)
            prefs.save(prefs.load().copy(ambientImmichShareUrl = share,
                ambientWeatherLocation = "Test & <check> \"station\""))
            server = LocalControlServer(prefs, callbacks(prefs, secret), 0)
            server.startSafely().getOrThrow()
            val base = "http://127.0.0.1:${server.activePort()}"
            val sections = listOf("dashboard", "display", "browser", "device", "system")
            sections.forEach {
                check("/action/settings/$it" !in request(base, "/$it").body) { "Anonymous settings exposed" }
            }
            check(request(base, "/api/status").code == 401) { "Anonymous API allowed" }
            val login = request(base, "/login", mapOf("password" to password))
            check(login.code in 300..399 && login.cookie.isNotEmpty()) { "Login failed" }
            val cookie = login.cookie
            val previewDirectory = if (designSnapshots) {
                File(checkNotNull(targetContext.getExternalFilesDir(null)), "browser-design-preview").also {
                    check(it.isDirectory || it.mkdirs()) { "Snapshot directory unavailable" }
                }
            } else null
            checkpoint = "mqtt-notify-discovery"
            val discovery = checkMqttNotification(prefs.load())
            previewDirectory?.resolve("mqtt-discovery.json")?.writeText(discovery)
            val initialTheme = prefs.load().themeMode
            listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM).forEach { theme ->
                prefs.save(prefs.load().copy(themeMode = theme))
                val pages = if (theme == ThemeMode.DARK) {
                    listOf("overview", "actions") + sections + "security"
                } else listOf("display", "system")
                (pages + "login").forEach { page ->
                    checkpoint = "aurora-${theme.name}-$page"
                    val response = request(base, "/$page", cookie = if (page == "login") "" else cookie)
                    check(response.code == 200 && "no-store" in response.cache) { "Design page unavailable or cached" }
                    check(secret !in response.body) { "Design page exposed stored password" }
                    checkAuroraPage(response.body, if (page == "login") null else "/$page", theme)
                    // Fixture preferences only; no real device settings, passwords or session cookies are exported.
                    previewDirectory?.resolve("$page-${theme.name.lowercase()}.html")?.writeText(response.body)
                }
            }
            prefs.save(prefs.load().copy(themeMode = initialTheme))
            fun form(section: String): MutableMap<String, String> {
                val page = request(base, "/$section", cookie = cookie)
                check(page.code == 200 && "no-store" in page.cache) { "Settings page unavailable or cached" }
                check(secret !in page.body) { "Stored password exposed" }
                val html = Regex("<form\\b[^>]*action=\"/action/settings/$section\"[^>]*>(.*?)</form>",
                    RegexOption.DOT_MATCHES_ALL).find(page.body)?.groupValues?.get(1)
                    ?: error("Settings form missing")
                LocalControlSettings.fieldNames(section).forEach {
                    check("name=\"$it\"" in html) { "Settings field missing" }
                }
                return formValues(html).also {
                    check(!it["csrf_token"].isNullOrEmpty() && !it["_revision"].isNullOrEmpty()) { "Form guards missing" }
                }
            }
            sections.forEach { section ->
                val before = prefs.load()
                val response = request(base, "/action/settings/$section", form(section), cookie)
                checkpoint = "roundtrip-$section-http${response.code}"
                if (response.code != 200) {
                    checkpoint += ": " + runCatching { JSONObject(response.body).optString("message") }
                        .getOrDefault(response.body).replace(secret, "[redacted]").replace(share, "[redacted]").take(200)
                }
                check(response.code == 200 && JSONObject(response.body).getBoolean("ok")) { "Form roundtrip failed" }
                check(prefs.load() == before) { "Form roundtrip changed settings" }
            }
            fun rejected(section: String, changes: Map<String, String>, code: Int = 400) {
                checkpoint = "invalid-$section-${changes.keys.joinToString("-")}"
                val before = prefs.load()
                val response = request(base, "/action/settings/$section", form(section).apply { putAll(changes) }, cookie)
                check(response.code == code && !JSONObject(response.body).getBoolean("ok")) { "Invalid settings accepted" }
                check(prefs.load() == before) { "Rejected settings changed preferences" }
            }
            rejected("dashboard", mapOf("reloadIntervalSeconds" to "-1"))
            rejected("browser", mapOf("browserEngine" to "INVALID"))
            rejected("dashboard", mapOf("homeAssistantUrl" to "javascript:alert(1)"))
            rejected("dashboard", mapOf("scheduleProfilesEnabled" to "on", "homeStartHour" to "1", "wallStartHour" to "1"))
            val guarded = form("display")
            checkpoint = "request-guards"
            val beforeGuards = prefs.load()
            listOf(guarded - "csrf_token", guarded + ("csrf_token" to "wrong")).forEach {
                check(request(base, "/action/settings/display", it, cookie).code == 403) { "CSRF guard failed" }
            }
            check(request(base, "/action/settings/display?unexpected=value", guarded, cookie).code == 400) { "Query accepted" }
            check(request(base, "/action/settings/missing", guarded, cookie).code == 404) { "Unknown section accepted" }
            check(request(base, "/missing", cookie = cookie).code == 404) { "Unknown page accepted" }
            check(prefs.load() == beforeGuards) { "Guard failure changed preferences" }
            check(request(base, "/action/settings/display", guarded + ("ambientPhotoIntervalSeconds" to "60"), cookie).code == 200)
            val afterSave = prefs.load()
            checkpoint = "stale-save"
            check(afterSave.ambientPhotoIntervalSeconds == 60) { "Changed value not saved" }
            val stale = request(base, "/action/settings/display", guarded, cookie)
            check(stale.code == 400 && !JSONObject(stale.body).getBoolean("ok") && prefs.load() == afterSave) { "Stale form accepted" }
            val unexpected = request(base, "/action/settings/display", form("display") + ("_fail" to "yes"), cookie)
            check(unexpected.code == 500 && !JSONObject(unexpected.body).getBoolean("ok") && secret !in unexpected.body) { "Unsafe error exposed" }
            val status = request(base, "/api/status", cookie = cookie)
            check(status.code == 200 && secret !in status.body && share !in status.body) { "Status exposed private settings" }
            check(secret !in prefs.exportSettingsJson() && share !in prefs.exportSettingsJson()) { "Export exposed credentials" }
            if (bannerUi) {
                checkpoint = "banner-ui"
                BannerInstrumentationChecks.run(this)
            }
            if (supportUi) {
                checkpoint = "support-ui"
                SupportInstrumentationChecks.run(this)
            }
            if (presenceUi || presencePermissionDenied) {
                checkpoint = "presence-ui"
                PresenceInstrumentationChecks.run(this, presencePermissionDenied)
            }
            results.putString("stream", "PASS: MQTT notify/event discovery, announcement/banner parsing, Aurora Rail pages, three themes, authenticated forms, five roundtrips, CSRF, validation, stale revisions and secret redaction.\n" +
                "PASS: native admin unlock, preference-store isolation and password-change invalidation; browser support links.\n" +
                (if (bannerUi) "PASS: banner rendering, replacement, expiry, action controls and MainActivity display preservation.\n" else "") +
                (if (supportUi) "PASS: native support layout, external links, no-browser fallback and settings/kiosk preservation.\n" else "") +
                (if (presencePermissionDenied) "PASS: camera denial preserves proximity and does not re-prompt after resume or recreation.\n"
                    else if (presenceUi) "PASS: awake camera analysis, motion idle reset, screensaver expiry, wake cooldown and disabled/paused presence.\n" else "") +
                (previewDirectory?.let { "Design snapshots: ${it.absolutePath}\n" } ?: ""))
            resultCode = Activity.RESULT_OK
        } catch (error: Throwable) {
            // Keep credentials and posted values out of instrumentation output even on failure.
            val location = error.stackTrace.firstOrNull {
                it.className.startsWith("io.github.rvbcrs.wallmode.")
            }
            results.putString("stream", "FAIL: $checkpoint; ${error.javaClass.simpleName}; ${location?.className}:${location?.lineNumber}\n")
        } finally {
            server?.stopSafely()
            isolated.getSharedPreferences(storageName, Context.MODE_PRIVATE).edit().commit()
            targetContext.deleteSharedPreferences(storageName)
        }
        finish(resultCode, results)
    }

    private fun checkMqttNotification(settings: KioskSettings): String {
        // Construct discovery without connecting to any broker or speaking on the user's tablet.
        val manager = MqttManager("abc123", settings.copy(mqttBrokerHost = "127.0.0.1",
            mqttBrokerPort = 1883, mqttUseTls = false, mqttUsername = ""), "", "test", {}, {}, {})
        try {
            val payload = manager.buildDiscoveryPayload()
            val root = JSONObject(payload)
            val notify = root.getJSONObject("components").getJSONObject("announce")
            check(notify.getString("platform") == "notify")
            check(notify.getString("unique_id") == "wallmode_abc123_announce")
            check(notify.getString("command_topic") == "wallmode/abc123/command/announce")
            check(notify.getString("command_template") == MqttContract.ANNOUNCEMENT_COMMAND_TEMPLATE)
            check(notify.getInt("qos") == 1 && !notify.getBoolean("retain"))
            check(root.getString("availability_topic") == "wallmode/abc123/availability")
            val event = root.getJSONObject("components").getJSONObject("action_response")
            check(event.getString("platform") == "event")
            check(event.getString("unique_id") == "wallmode_abc123_action_response")
            check(event.getString("state_topic") == "wallmode/abc123/event/action")
            check(event.getInt("qos") == 1 && event.getString("device_class") == "button")
            check(event.getJSONArray("event_types").toString() == "[\"action\"]")
            val reply = JSONObject(MqttContract.actionResponsePayload("test-card", "yes", UUID.randomUUID().toString())!!)
            check(reply.getString("event_type") == "action" && reply.getString("card_id") == "test-card")
            check(reply.getString("action_id") == "yes" && reply.getString("event_id").isNotEmpty())
            listOf("De was is klaar", "Deur \"open\" \\ café ☀", "{\"text\":\"literal JSON\"}").forEach { message ->
                val command = JSONObject().put("text", message).toString()
                check(MqttContract.parseCommand("announce", command, false) ==
                    WallModeMqttCommand.Announce(message, 80))
                check(MqttContract.parseCommand("announce", command, true) == null)
            }
            listOf("", "x".repeat(256), "two\nlines").forEach { invalid ->
                check(MqttContract.parseCommand("announce", JSONObject().put("text", invalid).toString(), false) == null)
            }
            checkBannerParsing()
            return payload
        } finally { manager.stop() }
    }

    private fun checkBannerParsing() {
        val base = JSONObject().put("action", "show").put("id", "banner-test").put("message", "Was \"klaar\" \\ café ☀")
        val notice = (MqttContract.parseCommand("banner", base.toString(), false) as WallModeMqttCommand.ShowBanner).notice
        check(notice.message == base.getString("message") && notice.seconds == 10 && !notice.sound && notice.action == null)
        val full = JSONObject(base.toString()).put("title", "Wasmachine").put("ttl", 3).put("level", "success")
            .put("sound", true).put("button", JSONObject().put("id", "confirm").put("label", "Ok"))
        val fullNotice = (MqttContract.parseCommand("banner", full.toString(), false) as WallModeMqttCommand.ShowBanner).notice
        check(fullNotice.level == BannerLevel.SUCCESS && fullNotice.sound && fullNotice.action == ActionCardAction("confirm", "Ok"))
        check(MqttContract.parseCommand("banner", full.toString(), true) == null)
        val clear = """{"action":"clear","id":"banner-test"}"""
        check(MqttContract.parseCommand("banner", clear, false) == WallModeMqttCommand.ClearBanner("banner-test"))
        check(MqttContract.parseCommand("banner", clear, true) == null)
        listOf("ttl" to 3.5, "ttl" to "10", "ttl" to 0, "ttl" to 121, "ttl" to 2147483648L,
            "sound" to "true", "sound" to 1, "level" to "unknown", "level" to JSONObject.NULL,
            "message" to "", "message" to "bad\ntext", "message" to true, "message" to "x".repeat(301),
            "id" to "bad/id", "title" to "x".repeat(81), "button" to "confirm",
            "button" to JSONObject().put("id", "ok"),
            "button" to JSONObject().put("id", "ok").put("label", "x".repeat(25))).forEach { (key, value) ->
            check(MqttContract.parseCommand("banner", JSONObject(base.toString()).put(key, value).toString(), false) == null) {
                "Invalid banner field accepted: $key"
            }
        }
        listOf("not JSON", "[]", "{", "{}", "x".repeat(2049)).forEach {
            check(MqttContract.parseCommand("banner", it, false) == null)
        }
    }

    private fun checkAuroraPage(html: String, activePath: String?, theme: ThemeMode) {
        val css = Regex("<style>(.*?)</style>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: error("Shared style missing")
        check("<svg" in html) { "Inline icons missing" }
        check(!Regex("<link\\b[^>]*rel=[\"']stylesheet[\"']", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            "External stylesheet added"
        }
        check(!Regex("@import|url\\(\\s*[\"']?(?:https?:)?//", RegexOption.IGNORE_CASE).containsMatchIn(css)) {
            "External style or font added"
        }
        val expectedColor = if (theme == ThemeMode.LIGHT) "#EAF2F4" else "#050A0E"
        check(css.contains(expectedColor, ignoreCase = true)) { "Native theme palette missing" }
        val expectedScheme = if (theme == ThemeMode.LIGHT) "light" else "dark"
        check(Regex("color-scheme:\\s*$expectedScheme\\s*;").containsMatchIn(css)) { "Native control theme incorrect" }
        check(("prefers-color-scheme" in css) == (theme == ThemeMode.SYSTEM)) { "System theme selection incorrect" }
        if (activePath == null) {
            check("class=\"login-page\"" in html) { "Aurora login layout missing" }
            check("<label for=\"admin-password\">Admin password</label>" in html) { "Password label missing" }
            val loginForm = Regex("<form\\b[^>]*action=\"/login\"[^>]*>(.*?)</form>", RegexOption.DOT_MATCHES_ALL)
                .find(html) ?: error("Login form missing")
            check("method=\"post\"" in loginForm.value && "id=\"admin-password\"" in loginForm.value &&
                "type=\"password\"" in loginForm.value && "name=\"password\"" in loginForm.value) { "Login form changed" }
        } else {
            check("class=\"shell\"" in html && "class=\"rail\"" in html && "id=\"content\"" in html) { "Aurora Rail layout missing" }
            check("class=\"skip-link\"" in html) { "Keyboard skip link missing" }
            val currentLinks = Regex("<a\\b[^>]*aria-current=\"page\"[^>]*>").findAll(html).toList()
            check(currentLinks.size == 1 && "href=\"$activePath\"" in currentLinks.single().value) { "Current page not identified" }
            listOf("overview", "actions", "dashboard", "display", "browser", "device", "system", "security").forEach {
                check("href=\"/$it\"" in html) { "Navigation page missing" }
            }
            if (activePath == "/system") {
                check("Support WallMode" in html && "Contributions are optional" in html)
                val supportPosition = html.indexOf("class=\"card support-card\"")
                val settingsPosition = html.indexOf("class=\"card settings-card\"")
                check(supportPosition >= 0 && supportPosition < settingsPosition &&
                    settingsPosition < html.indexOf("Android system controls")) { "Support must lead System settings" }
                listOf(SupportLinks.COFFEE, SupportLinks.GITHUB).forEach { url ->
                    val link = Regex("<a\\b[^>]*href=\"${Regex.escape(url)}\"[^>]*>")
                        .find(html)?.value ?: error("Support destination missing")
                    check("target=\"_blank\"" in link && "rel=\"noopener noreferrer\"" in link) {
                        "Support link must preserve the control panel and not expose its referrer"
                    }
                }
            }
        }
    }

    private fun callbacks(prefs: KioskPreferences, secret: String) = object : LocalControlCallbacks {
        override fun status(): LocalControlStatus {
            val s = prefs.load()
            return LocalControlStatus("test", s.browserEngine.name, prefs.buildDashboardUrl(s),
                s.homeAssistantUrl, s.dashboardPath, s.appendKiosk, s.reloadIntervalSeconds,
                s.autoReloadOnFailure, s.fullscreen, s.keepScreenOn, s.mqttEnabled, false, prefs.diagnosticsSnapshot())
        }
        override fun saveSettings(section: String, params: Map<String, String>): Result<String> = runCatching {
            if (params["_fail"] == "yes") error(secret)
            try {
                require(params["_revision"] == LocalControlSettings.revision(section, prefs.load())) { "Stale form" }
                prefs.save(LocalControlSettings.apply(section, params, prefs.load()))
            } catch (error: IllegalArgumentException) {
                throw LocalControlSettingsException(error.message ?: "Invalid test settings")
            }
            "Saved"
        }
        override fun reloadDashboard() = Unit
        override fun restartEngine() = Unit
        override fun openSettings() = Unit
        override fun openUrl(url: String) = Unit
        override fun showTakeover(url: String, seconds: Int) = Unit
        override fun dismissTakeover() = Unit
        override fun discoverHomeAssistantServers() = Result.success(emptyList<HomeAssistantDiscoveryResult>())
        override fun connectToHomeAssistantServer(baseUrl: String) = Result.success(Unit)
    }

    private data class Reply(val code: Int, val body: String, val cookie: String, val cache: String)

    private fun request(base: String, path: String, values: Map<String, String>? = null, cookie: String = ""): Reply {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            if (cookie.isNotEmpty()) connection.setRequestProperty("Cookie", cookie)
            if (values != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val bytes = values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }.toByteArray()
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val body = (if (code >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Reply(code, body, connection.getHeaderField("Set-Cookie").orEmpty().substringBefore(';'),
                connection.getHeaderField("Cache-Control").orEmpty())
        } finally { connection.disconnect() }
    }

    private fun formValues(html: String): MutableMap<String, String> {
        fun attribute(tag: String, name: String): String = Regex("\\b$name=\"([^\"]*)\"")
            .find(tag)?.groupValues?.get(1)?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }.orEmpty()
        val values = linkedMapOf<String, String>()
        Regex("<input\\b[^>]*>").findAll(html).forEach {
            val tag = it.value
            if (attribute(tag, "type") != "checkbox" || Regex("\\schecked(?:\\s|>)").containsMatchIn(tag)) {
                attribute(tag, "name").takeIf(String::isNotEmpty)?.let { name -> values[name] = attribute(tag, "value") }
            }
        }
        Regex("<select\\b([^>]*)>(.*?)</select>", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach {
            val option = Regex("<option\\b[^>]*\\sselected[^>]*>").find(it.groupValues[2])?.value ?: error("Selection missing")
            values[attribute(it.groupValues[1], "name")] = attribute(option, "value")
        }
        return values
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
