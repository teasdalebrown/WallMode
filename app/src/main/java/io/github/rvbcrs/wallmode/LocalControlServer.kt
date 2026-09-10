package io.github.rvbcrs.wallmode

import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Collections
import java.util.Enumeration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface LocalControlPreviewResult {
    class Jpeg(val bytes: ByteArray) : LocalControlPreviewResult
    data class Unavailable(val reason: String) : LocalControlPreviewResult
}

internal fun previewRetryAfterMillis(
    nowMillis: Long,
    lastAcceptedAtMillis: Long,
    intervalMillis: Long = 2_000L
): Long {
    if (lastAcceptedAtMillis == Long.MIN_VALUE) return 0L
    val elapsed = (nowMillis - lastAcceptedAtMillis).coerceAtLeast(0L)
    return (intervalMillis - elapsed).coerceAtLeast(0L)
}

internal fun isValidPreviewJpeg(bytes: ByteArray, maxBytes: Int = 2 * 1_024 * 1_024): Boolean {
    return bytes.size in 4..maxBytes &&
        bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
        bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()
}

internal data class LocalControlSession(
    val expiresAtMillis: Long,
    val credentialIdentity: String,
    val csrfToken: String = UUID.randomUUID().toString()
) {
    fun acceptsCsrfToken(candidate: String?): Boolean =
        !candidate.isNullOrBlank() && MessageDigest.isEqual(
            csrfToken.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8)
        )

    fun isValid(nowMillis: Long, currentCredentialIdentity: String): Boolean {
        return expiresAtMillis >= nowMillis &&
            credentialIdentity.isNotBlank() &&
            credentialIdentity == currentCredentialIdentity
    }
}

data class LocalControlStatus(
    val appVersion: String,
    val engine: String,
    val dashboardUrl: String,
    val homeAssistantUrl: String,
    val dashboardPath: String,
    val appendKiosk: Boolean,
    val reloadIntervalSeconds: Int,
    val autoReloadOnFailure: Boolean,
    val fullscreen: Boolean,
    val keepScreenOn: Boolean,
    val mqttEnabled: Boolean,
    val takeoverActive: Boolean,
    val diagnostics: DiagnosticsSnapshot,
    val health: String = "",
    val displayMode: String = "",
    val currentPage: String = "",
    val lastError: String = ""
)

/** Only deliberately user-safe validation messages may be returned to the browser. */
class LocalControlSettingsException(message: String) : IllegalArgumentException(message)

interface LocalControlCallbacks {
    fun status(): LocalControlStatus
    fun reloadDashboard()
    fun restartEngine()
    fun openSettings()
    fun openUrl(url: String)
    fun showTakeover(url: String, seconds: Int)
    fun dismissTakeover()
    fun saveSettings(section: String, params: Map<String, String>): Result<String>
    fun discoverHomeAssistantServers(): Result<List<HomeAssistantDiscoveryResult>>
    fun connectToHomeAssistantServer(baseUrl: String): Result<Unit>
    fun capturePreview(): LocalControlPreviewResult =
        LocalControlPreviewResult.Unavailable("Preview capture is unavailable")
}

class LocalControlServer(
    private val prefs: KioskPreferences,
    private val callbacks: LocalControlCallbacks,
    private val controlPort: Int = DEFAULT_PORT
) : NanoHTTPD(controlPort) {

    private val sessions = ConcurrentHashMap<String, LocalControlSession>()
    private val secureRandom = SecureRandom()
    private val previewInFlight = AtomicBoolean(false)
    private val lastPreviewAtMillis = AtomicLong(NO_PREVIEW_REQUEST)

    fun startSafely(): Result<Unit> = runCatching {
        start(SOCKET_READ_TIMEOUT, false)
        Log.i(TAG, "Local control server started on port ${activePort()}")
    }

    fun stopSafely() {
        runCatching { stop() }
        sessions.clear()
    }

    fun activePort(): Int {
        val listeningPort = runCatching { listeningPort }.getOrDefault(-1)
        return if (listeningPort > 0) listeningPort else controlPort
    }

    fun primaryLocalUrl(): String {
        return discoverPrimaryUrl(activePort())
    }

    override fun serve(session: IHTTPSession): Response {
        cleanupExpiredSessions()
        return runCatching { route(session) }
            .getOrElse { error ->
                Log.w(TAG, "Control server request failed: ${error.javaClass.simpleName}")
                textResponse(
                    Response.Status.INTERNAL_ERROR,
                    "Control server request failed. Reload the page and try again."
                )
            }.also { response ->
                // ponytail: form posts are infrequent; close so unread rejected bodies cannot poison the next request.
                // Track consumed bodies if high-frequency HTTP commands ever need connection reuse.
                if (session.method == Method.POST) response.closeConnection(true)
            }.let(::noStore)
    }

    private fun route(session: IHTTPSession): Response {
        val path = session.uri.trim().ifBlank { "/" }
        val method = session.method

        if (!isPrivateClient(session)) {
            return textResponse(Response.Status.FORBIDDEN, "Forbidden")
        }

        if (path == "/health") {
            return textResponse(Response.Status.OK, "ok")
        }

        if (!isAuthenticated(session)) {
            if (path == "/login" && method == Method.POST) {
                return handleLogin(session)
            }
            if (path.startsWith("/api/")) {
                return noStore(textResponse(Response.Status.UNAUTHORIZED, "Unauthorized"))
            }
            return loginPage(errorMessage = null)
        }

        // All authenticated forms share one CSRF guard, including actions and passwords.
        val requestParams = if (method == Method.POST) {
            // NanoHTTPD retains queryParameterString after a POST on keep-alive sockets.
            // Before parseBody, parameters contains only this request's URL parameters.
            if (session.parameters.isNotEmpty()) {
                return textResponse(Response.Status.BAD_REQUEST, "Submit forms without URL parameters")
            }
            val contentLength = session.headers["content-length"]?.toLongOrNull()
            if (contentLength == null || contentLength !in 0L..65_536L ||
                session.headers["content-type"]?.substringBefore(';')?.trim() != "application/x-www-form-urlencoded"
            ) {
                return textResponse(Response.Status.BAD_REQUEST, "Submit a valid settings form (maximum 64 KB)")
            }
            val params = postParams(session)
            if (sessions[session.cookies.read(SESSION_COOKIE)]?.acceptsCsrfToken(params["csrf_token"]) != true) {
                return textResponse(Response.Status.FORBIDDEN, "Reload the page and submit the form again")
            }
            params
        } else emptyMap()

        return when {
            method == Method.GET && path == "/" -> redirect("/overview")
            method == Method.GET && path == "/overview" -> pageOverview(session)
            method == Method.GET && path == "/actions" -> pageActions(session)
            method == Method.GET && LocalControlSettings.isSection(path.removePrefix("/")) ->
                pageSettings(session, path.removePrefix("/"))
            method == Method.GET && path == "/security" -> pageSecurity(session)
            method == Method.POST && path == "/logout" -> handleLogout(session)
            method == Method.GET && path == "/api/status" -> statusJson()
            method == Method.GET && path == "/api/preview.jpg" -> previewJpeg()

            method == Method.POST && path == "/action/reload" -> {
                callbacks.reloadDashboard()
                redirectWithMessage("Dashboard reloaded", safeReturnPath(requestParams["return_to"]))
            }

            method == Method.POST && path == "/action/restart-engine" -> {
                callbacks.restartEngine()
                redirectWithMessage("Browser engine restarted", safeReturnPath(requestParams["return_to"]))
            }

            method == Method.POST && path == "/action/open-settings" -> {
                callbacks.openSettings()
                redirectWithMessage(
                    "Settings opened on tablet",
                    safeReturnPath(requestParams["return_to"])
                )
            }

            method == Method.POST && path == "/action/open-url" -> {
                val params = requestParams
                val url = params["url"].orEmpty().trim()
                val returnPath = safeReturnPath(params["return_to"])
                if (!KioskPreferences.isHttpOrHttpsUrl(url)) {
                    redirectWithMessage("Enter a valid http:// or https:// URL", returnPath)
                } else {
                    callbacks.openUrl(url)
                    redirectWithMessage("Loaded URL", returnPath)
                }
            }

            method == Method.POST && path == "/action/takeover" -> {
                val params = requestParams
                val url = params["url"].orEmpty().trim()
                val seconds = params["seconds"]?.toIntOrNull()
                    ?: MqttContract.DEFAULT_TAKEOVER_SECONDS
                val returnPath = safeReturnPath(params["return_to"])
                val command = MqttContract.validatedTakeover(
                    "show",
                    "local-control",
                    url,
                    seconds,
                    MqttContract.DEFAULT_TAKEOVER_PRIORITY
                ) as? WallModeMqttCommand.ShowTakeover
                if (command == null) {
                    redirectWithMessage("Enter a valid URL and duration", returnPath)
                } else {
                    callbacks.showTakeover(command.url, command.seconds)
                    redirectWithMessage("Temporary view opened", returnPath)
                }
            }

            method == Method.POST && path == "/action/dismiss-takeover" -> {
                callbacks.dismissTakeover()
                redirectWithMessage("Temporary view dismissed", safeReturnPath(requestParams["return_to"]))
            }

            method == Method.POST && path.startsWith("/action/settings/") ->
                saveSettingsConfig(path.removePrefix("/action/settings/"), requestParams)

            method == Method.POST && path == "/action/discover-home-assistant" -> {
                val params = requestParams
                val returnPath = safeReturnPath(params["return_to"])
                callbacks.discoverHomeAssistantServers().fold(
                    onSuccess = { servers ->
                        when {
                            servers.isEmpty() -> {
                                redirectWithMessage(
                                    "No Home Assistant server found on local network",
                                    returnPath
                                )
                            }
                            servers.size == 1 -> {
                                val chosen = servers.first().baseUrl
                                callbacks.connectToHomeAssistantServer(chosen).fold(
                                    onSuccess = {
                                        redirectWithMessage("Connected to $chosen", returnPath)
                                    },
                                    onFailure = { error ->
                                        redirectWithMessage(
                                            error.message ?: "Failed to connect to $chosen",
                                            returnPath
                                        )
                                    }
                                )
                            }
                            else -> {
                                pageDiscoveredServers(
                                    session = session,
                                    candidates = servers,
                                    returnPath = returnPath
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        redirectWithMessage(
                            error.message ?: "No Home Assistant server found on local network",
                            returnPath
                        )
                    }
                )
            }

            method == Method.POST && path == "/action/select-home-assistant" -> {
                val params = requestParams
                val returnPath = safeReturnPath(params["return_to"])
                val selected = params["server_url"].orEmpty().trim()
                if (!KioskPreferences.isHttpOrHttpsUrl(selected)) {
                    redirectWithMessage("Select a valid server URL", returnPath)
                } else {
                    callbacks.connectToHomeAssistantServer(selected).fold(
                        onSuccess = {
                            redirectWithMessage("Connected to $selected", returnPath)
                        },
                        onFailure = { error ->
                            redirectWithMessage(
                                error.message ?: "Failed to connect to $selected",
                                returnPath
                            )
                        }
                    )
                }
            }

            method == Method.POST && path == "/action/change-password" -> {
                val params = requestParams
                changePassword(params, safeReturnPath(params["return_to"]))
            }

            else -> textResponse(Response.Status.NOT_FOUND, "Not found")
        }
    }

    private fun pageOverview(session: IHTTPSession): Response {
        val status = callbacks.status()
        val previewCard = if (prefs.load().localControlPreviewEnabled) {
            """
                <div class="card">
                  <h2>Screen Preview</h2>
                  <p class="muted">Captured only when requested. The image is not stored.</p>
                  <button class="btn" type="button" onclick="capturePreview()">Capture Current Screen</button>
                  <img id="screenPreview" class="screen-preview" alt="WallMode screen preview" hidden />
                  <script>
                    function capturePreview() {
                      const image = document.getElementById('screenPreview');
                      image.hidden = false;
                      image.src = '/api/preview.jpg?t=' + Date.now();
                    }
                  </script>
                </div>
            """.trimIndent()
        } else {
            """
                <div class="card">
                  <h2>Screen Preview</h2>
                  <p class="muted">Preview is disabled in WallMode settings.</p>
                </div>
            """.trimIndent()
        }
        val body = """
            <div class="card">
              <h2>System Overview</h2>
              <dl class="status">
                <dt>Health</dt><dd>${escapeHtml(resolvedHealth(status))}</dd>
                <dt>App version</dt><dd>${escapeHtml(status.appVersion)}</dd>
                <dt>Engine</dt><dd>${escapeHtml(status.engine)}</dd>
                <dt>Display mode</dt><dd>${escapeHtml(resolvedDisplayMode(status))}</dd>
                <dt>Current page</dt><dd>${escapeHtml(resolvedCurrentPage(status))}</dd>
                <dt>Load status</dt><dd>${escapeHtml(status.diagnostics.lastLoadStatus)}</dd>
                <dt>Network</dt><dd>${escapeHtml(status.diagnostics.lastNetworkState)}</dd>
                <dt>Watchdog</dt><dd>${escapeHtml(status.diagnostics.lastWatchdogState)}</dd>
                <dt>MQTT</dt><dd>${escapeHtml(status.diagnostics.lastMqttState)}</dd>
                <dt>Temporary view</dt><dd>${if (status.takeoverActive) "Active" else "Inactive"}</dd>
                <dt>Updates</dt><dd>${escapeHtml(status.diagnostics.lastUpdateState)}</dd>
                <dt>Last error</dt><dd>${escapeHtml(resolvedLastError(status))}</dd>
              </dl>
              <div class="row" style="margin-top:10px">
                <a class="btn" href="/api/status" target="_blank" rel="noreferrer">Open JSON status</a>
                <a class="btn" href="/overview">Refresh page</a>
              </div>
            </div>
            $previewCard
        """.trimIndent()
        return renderPage(
            title = "Overview",
            activePath = "/overview",
            message = session.parms["msg"],
            body = body,
            session = session
        )
    }

    private fun pageActions(session: IHTTPSession): Response {
        val body = """
            <div class="card">
              <h2>Quick Actions</h2>
              <div class="tile-grid">
                <form method="post" action="/action/reload" class="stack">
                  ${returnInput("/actions")}
                  <button class="btn primary" type="submit">Reload Dashboard</button>
                </form>
                <form method="post" action="/action/restart-engine" class="stack">
                  ${returnInput("/actions")}
                  <button class="btn" type="submit">Restart Browser Engine</button>
                </form>
                <form method="post" action="/action/open-settings" class="stack">
                  ${returnInput("/actions")}
                  <button class="btn" type="submit">Open Settings on Tablet</button>
                </form>
                <form method="post" action="/logout" class="stack">
                  <button class="btn" type="submit">Sign Out</button>
                </form>
              </div>
            </div>
            <div class="card">
              <h2>Open URL Now</h2>
              <form method="post" action="/action/open-url" class="stack">
                ${returnInput("/actions")}
                <label for="open-url">Dashboard URL</label>
                <input class="field" id="open-url" type="url" name="url" placeholder="https://homeassistant.local/lovelace" />
                <button class="btn primary" type="submit">Open URL on Tablet</button>
              </form>
            </div>
            <div class="card">
              <h2>Temporary Event View</h2>
              <form method="post" action="/action/takeover" class="stack">
                ${returnInput("/actions")}
                <label for="event-url">Event or camera URL</label>
                <input class="field" id="event-url" type="url" name="url" placeholder="https://homeassistant.local/camera" required />
                <label class="label" for="event-seconds">Show for 5–600 seconds</label>
                <input class="field" id="event-seconds" type="number" min="${MqttContract.MIN_TAKEOVER_SECONDS}" max="${MqttContract.MAX_TAKEOVER_SECONDS}" name="seconds" value="${MqttContract.DEFAULT_TAKEOVER_SECONDS}" required />
                <button class="btn primary" type="submit">Show Temporarily</button>
              </form>
              <form method="post" action="/action/dismiss-takeover" class="stack" style="margin-top:10px">
                ${returnInput("/actions")}
                <button class="btn" type="submit">Dismiss Temporary View</button>
              </form>
            </div>
        """.trimIndent()
        return renderPage(
            title = "Actions",
            activePath = "/actions",
            message = session.parms["msg"],
            body = body,
            session = session
        )
    }

    private fun pageSettings(session: IHTTPSession, section: String): Response {
        val settings = prefs.load()
        val title = section.replaceFirstChar { it.uppercaseChar() }
        val supportCard = if (section == "system") """
              <section class="card support-card" aria-labelledby="support-title">
                <h2 id="support-title">Support WallMode</h2>
                <p class="muted">WallMode is free and open-source. If it makes your wall display feel at home, you can support its development. Contributions are optional and never unlock app features.</p>
                <div class="stack">
                  <a class="btn support-link coffee" href="${SupportLinks.COFFEE}" target="_blank" rel="noopener noreferrer">
                    <span class="support-icon">${LocalControlStyle.icon("coffee")}</span>
                    <span class="support-label"><strong>Buy Me a Coffee</strong><small>A one-off thank you</small></span>
                    ${LocalControlStyle.icon("external")}
                    <span class="visually-hidden"> (opens in a new tab)</span>
                  </a>
                  <a class="btn support-link" href="${SupportLinks.GITHUB}" target="_blank" rel="noopener noreferrer">
                    <span class="support-icon">${LocalControlStyle.icon("heart")}</span>
                    <span class="support-label"><strong>GitHub Sponsors</strong><small>Support ongoing development</small></span>
                    ${LocalControlStyle.icon("external")}
                    <span class="visually-hidden"> (opens in a new tab)</span>
                  </a>
                </div>
                <p class="support-note muted">Opens on this device, not on your wall display. No payment is handled by WallMode.</p>
              </section>
        """.trimIndent() else ""
        val extras = when (section) {
            "dashboard" -> """
              <div class="card">
              <form method="post" action="/action/discover-home-assistant" class="stack" style="margin-top:8px">
                ${returnInput("/dashboard")}
                <button class="btn" type="submit">Auto-find and connect Home Assistant</button>
              </form>
              </div>
            """.trimIndent()
            "system" -> """
              <div class="card">
                <h2>Android system controls</h2>
                <p class="muted">Android permissions, choosing the default launcher, system settings and app installs require confirmation on the tablet.</p>
                <form method="post" action="/action/open-settings" class="stack">
                  ${returnInput("/system")}
                  <button class="btn" type="submit">Open settings on tablet</button>
                </form>
              </div>
            """.trimIndent()
            else -> ""
        }
        val mqttPasswordFields = if (section == "device") """
            <fieldset class="settings-group stack">
              <legend>MQTT password</legend>
              <p class="muted" id="mqtt-password-state">${if (prefs.hasMqttPassword()) "A password is saved." else "No password is saved."} Leave empty to keep it. Saved passwords are never returned to the browser.</p>
              <label for="mqttPassword">New MQTT password</label>
              <input class="field" id="mqttPassword" name="mqttPassword" type="password" maxlength="4096" autocomplete="new-password" />
              <label class="check"><input type="checkbox" name="clearMqttPassword" />Remove the saved MQTT password</label>
            </fieldset>
        """.trimIndent() else ""
        val body = """
            $supportCard
            <div class="card settings-card">
              <h2>$title settings</h2>
              <p class="muted section-help">Save changes per tab. Close WallMode settings on the tablet before saving here. Use this HTTP panel only on a trusted local network.</p>
              <form method="post" action="/action/settings/$section" class="stack settings-form">
                <input type="hidden" name="_revision" value="${escapeHtml(LocalControlSettings.revision(section, settings))}" />
                ${LocalControlSettings.render(section, settings)}
                $mqttPasswordFields
                <div class="save-bar">
                  <p class="save-result" role="status" aria-live="polite"></p>
                  <button class="btn primary" type="submit">${LocalControlStyle.icon("save")} Save $title settings</button>
                </div>
                <noscript><p>Enable JavaScript to save these settings without losing your input if validation fails.</p></noscript>
              </form>
            </div>
            $extras
        """.trimIndent()
        return renderPage(
            title = title,
            activePath = "/$section",
            message = session.parms["msg"],
            body = body,
            session = session
        )
    }

    private fun saveSettingsConfig(section: String, params: Map<String, String>): Response {
        if (!LocalControlSettings.isSection(section)) {
            return textResponse(Response.Status.NOT_FOUND, "Unknown settings tab")
        }
        return callbacks.saveSettings(section, params).fold(
            onSuccess = { message ->
                val json = JSONObject().put("ok", true).put("message", message)
                    .put("revision", LocalControlSettings.revision(section, prefs.load()))
                if (section == "device") json.put("hasMqttPassword", prefs.hasMqttPassword())
                newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString())
            },
            onFailure = { error ->
                val safeError = error as? LocalControlSettingsException
                val json = JSONObject().put("ok", false).put(
                    "message", safeError?.message ?: "Could not save settings. Your input has been kept; try again."
                )
                newFixedLengthResponse(
                    if (safeError != null) Response.Status.BAD_REQUEST else Response.Status.INTERNAL_ERROR,
                    "application/json; charset=utf-8", json.toString()
                )
            }
        )
    }

    private fun pageSecurity(session: IHTTPSession): Response {
        val body = """
            <div class="card">
              <h2>Security</h2>
              <p class="muted">Use the same admin password as the tablet app.</p>
              <form method="post" action="/action/change-password" class="stack">
                ${returnInput("/security")}
                <label for="current-password">Current password</label>
                <input class="field" id="current-password" name="current_password" type="password" autocomplete="current-password" />
                <label for="new-password">New password (min 4 chars)</label>
                <input class="field" id="new-password" name="new_password" type="password" autocomplete="new-password" />
                <label for="confirm-password">Confirm new password</label>
                <input class="field" id="confirm-password" name="confirm_password" type="password" autocomplete="new-password" />
                <button class="btn primary" type="submit">Update Admin Password</button>
              </form>
            </div>
        """.trimIndent()
        return renderPage(
            title = "Security",
            activePath = "/security",
            message = session.parms["msg"],
            body = body,
            session = session
        )
    }

    private fun pageDiscoveredServers(
        session: IHTTPSession,
        candidates: List<HomeAssistantDiscoveryResult>,
        returnPath: String
    ): Response {
        val optionsHtml = candidates.mapIndexed { index, candidate ->
            val label = escapeHtml("${candidate.baseUrl} (${candidate.source})")
            val value = escapeHtml(candidate.baseUrl)
            """
                <label class="check">
                  <input type="radio" name="server_url" value="$value" ${if (index == 0) "checked" else ""} />
                  $label
                </label>
            """.trimIndent()
        }.joinToString(separator = "")

        val body = """
            <div class="card">
              <h2>Select Home Assistant Server</h2>
              <form method="post" action="/action/select-home-assistant" class="stack">
                ${returnInput(returnPath)}
                $optionsHtml
                <button class="btn primary" type="submit">Connect Selected Server</button>
              </form>
            </div>
        """.trimIndent()

        return renderPage(
            title = "Dashboard",
            activePath = "/dashboard",
            message = null,
            body = body,
            session = session
        )
    }

    private fun pageDescription(path: String): String = when (path) {
        "/overview" -> "A clear view of your wall display."
        "/actions" -> "Quick controls, right at your fingertips."
        "/dashboard" -> "The right dashboard, at the right moment."
        "/display" -> "Make your display feel at home."
        "/browser" -> "Fine-tune how your dashboard runs."
        "/device" -> "Connect your display to the rest of your home."
        "/system" -> "Keep WallMode running smoothly."
        else -> "Keep control in the right hands."
    }

    private fun renderPage(
        title: String,
        activePath: String,
        message: String?,
        body: String,
        session: IHTTPSession
    ): Response {
        val hostHeader = session.headers["host"].orEmpty().trim()
        val accessUrl = if (hostHeader.isBlank()) {
            primaryLocalUrl()
        } else {
            "http://$hostHeader"
        }
        val themeCss = LocalControlStyle.css(prefs.load().themeMode)
        val alertHtml = if (message.isNullOrBlank()) {
            ""
        } else {
            """<div class="notice" role="status">${escapeHtml(message)}</div>"""
        }

        fun navLink(path: String, label: String): String {
            val activeClass = if (path == activePath) "nav-link active" else "nav-link"
            return """<a class="$activeClass" href="$path" ${if (path == activePath) "aria-current=\"page\"" else ""}><span class="nav-icon">${LocalControlStyle.icon(path.removePrefix("/"))}</span><span>$label</span></a>"""
        }

        val navHtml = listOf(
            navLink("/overview", "Overview"),
            navLink("/actions", "Actions"),
            navLink("/dashboard", "Dashboard"),
            navLink("/display", "Display"),
            navLink("/browser", "Browser"),
            navLink("/device", "Device"),
            navLink("/system", "System"),
            navLink("/security", "Security")
        ).joinToString("")
        val csrfToken = sessions[session.cookies.read(SESSION_COOKIE)]?.csrfToken.orEmpty()
        val protectedBody = Regex("<form\\b[^>]*>").replace(body) { match ->
            "${match.value}<input type=\"hidden\" name=\"csrf_token\" value=\"${escapeHtml(csrfToken)}\" />"
        }

        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>WallMode Control - ${escapeHtml(title)}</title>
              <style>$themeCss</style>
            </head>
            <body>
              <a class="skip-link" href="#content">Skip to content</a>
              <div class="shell">
                <aside class="rail" aria-label="WallMode navigation">
                  <a class="brand" href="/overview">
                    <span class="brand-icon">${LocalControlStyle.icon("brand")}</span>
                    <span><strong>WallMode</strong><small>Browser control panel</small></span>
                  </a>
                  <p class="nav-caption">Control center</p>
                  <nav class="nav" aria-label="Control panel pages">
                    $navHtml
                  </nav>
                  <div class="rail-footer">
                    <span class="connection"><span class="status-dot" aria-hidden="true"></span>Connected to tablet</span>
                    <span class="access-url">${escapeHtml(accessUrl)}</span>
                    <p class="muted">Your display. Your space.</p>
                  </div>
                </aside>
                <main class="workspace" id="content" tabindex="-1">
                  <header class="page-header">
                    <span class="page-symbol">${LocalControlStyle.icon(activePath.removePrefix("/"))}</span>
                    <div>
                      <p class="eyebrow">Control center</p>
                      <h1>${escapeHtml(title)}</h1>
                      <p>${escapeHtml(pageDescription(activePath))}</p>
                    </div>
                  </header>
                  <div class="page-content">
                    $alertHtml
                    $protectedBody
                  </div>
                </main>
              </div>
              <script>
                document.querySelectorAll('.settings-form').forEach(function(form) {
                  form.addEventListener('submit', async function(event) {
                    event.preventDefault();
                    const button = form.querySelector('button[type="submit"]');
                    const status = form.querySelector('.save-result');
                    button.disabled = true;
                    status.textContent = 'Saving…';
                    status.dataset.error = 'false';
                    try {
                      const response = await fetch(form.action, {
                        method: 'POST', credentials: 'same-origin',
                        headers: {'Accept': 'application/json'},
                        body: new URLSearchParams(new FormData(form))
                      });
                      if (!(response.headers.get('content-type') || '').includes('application/json')) {
                        throw new Error('Your session has expired or the form is no longer valid. Copy any unsaved changes, then reload and sign in again.');
                      }
                      const result = await response.json();
                      status.textContent = result.message;
                      status.dataset.error = result.ok ? 'false' : 'true';
                      if (result.ok) {
                        form.elements['_revision'].value = result.revision;
                        if (form.elements['mqttPassword']) {
                          form.elements['mqttPassword'].value = '';
                          form.elements['clearMqttPassword'].checked = false;
                          document.getElementById('mqtt-password-state').textContent =
                            (result.hasMqttPassword ? 'A password is saved.' : 'No password is saved.') +
                            ' Leave empty to keep it. Saved passwords are never returned to the browser.';
                        }
                      }
                    } catch (error) {
                      status.dataset.error = 'true';
                      status.textContent = error.message === 'Failed to fetch'
                        ? 'Connection lost; saving could not be confirmed. Your input is still here. Check the tablet or reconnect before retrying.'
                        : error.message;
                    } finally {
                      button.disabled = false;
                    }
                  });
                });
              </script>
            </body>
            </html>
        """.trimIndent()
        return htmlResponse(html)
    }

    private fun statusJson(): Response {
        val status = callbacks.status()
        val json = JSONObject().apply {
            put("health", resolvedHealth(status))
            put("appVersion", status.appVersion)
            put("engine", status.engine)
            put("displayMode", resolvedDisplayMode(status))
            put("currentPage", resolvedCurrentPage(status))
            put("dashboardUrl", status.dashboardUrl)
            put("homeAssistantUrl", status.homeAssistantUrl)
            put("dashboardPath", status.dashboardPath)
            put("appendKiosk", status.appendKiosk)
            put("reloadIntervalSeconds", status.reloadIntervalSeconds)
            put("autoReloadOnFailure", status.autoReloadOnFailure)
            put("fullscreen", status.fullscreen)
            put("keepScreenOn", status.keepScreenOn)
            put("mqttEnabled", status.mqttEnabled)
            put("mqttState", status.diagnostics.lastMqttState)
            put("takeoverActive", status.takeoverActive)
            put("lastLoadStatus", status.diagnostics.lastLoadStatus)
            put("lastNetworkState", status.diagnostics.lastNetworkState)
            put("lastWatchdogState", status.diagnostics.lastWatchdogState)
            put("lastUpdateState", status.diagnostics.lastUpdateState)
            put("lastCrashReason", status.diagnostics.lastCrashReason)
            put("lastError", resolvedLastError(status))
            put("previewEnabled", prefs.load().localControlPreviewEnabled)
        }
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            json.toString()
        )
        return noStore(response)
    }

    private fun previewJpeg(): Response {
        if (!prefs.load().localControlPreviewEnabled) {
            return noStore(textResponse(Response.Status.FORBIDDEN, "Preview is disabled"))
        }
        if (!previewInFlight.compareAndSet(false, true)) {
            return previewRateLimited(PREVIEW_INTERVAL_MILLIS)
        }

        val now = System.nanoTime() / 1_000_000L
        val retryAfterMillis = previewRetryAfterMillis(
            nowMillis = now,
            lastAcceptedAtMillis = lastPreviewAtMillis.get(),
            intervalMillis = PREVIEW_INTERVAL_MILLIS
        )
        if (retryAfterMillis > 0L) {
            previewInFlight.set(false)
            return previewRateLimited(retryAfterMillis)
        }
        lastPreviewAtMillis.set(now)

        return try {
            when (val result = callbacks.capturePreview()) {
                is LocalControlPreviewResult.Jpeg -> {
                    if (!isValidPreviewJpeg(result.bytes, MAX_PREVIEW_BYTES)) {
                        noStore(textResponse(Response.Status.INTERNAL_ERROR, "Invalid preview image"))
                    } else {
                        val response = newFixedLengthResponse(
                            Response.Status.OK,
                            "image/jpeg",
                            ByteArrayInputStream(result.bytes),
                            result.bytes.size.toLong()
                        )
                        noStore(response).also {
                            it.addHeader("X-Content-Type-Options", "nosniff")
                        }
                    }
                }
                is LocalControlPreviewResult.Unavailable -> noStore(
                    textResponse(
                        Response.Status.SERVICE_UNAVAILABLE,
                        result.reason.trim().ifBlank { "Preview is unavailable" }.take(180)
                    )
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "Preview capture failed", error)
            noStore(textResponse(Response.Status.SERVICE_UNAVAILABLE, "Preview capture failed"))
        } finally {
            previewInFlight.set(false)
        }
    }

    private fun previewRateLimited(retryAfterMillis: Long): Response {
        return noStore(
            textResponse(Response.Status.TOO_MANY_REQUESTS, "Preview requested too quickly")
        ).also {
            val retrySeconds = ((retryAfterMillis + 999L) / 1_000L).coerceAtLeast(1L)
            it.addHeader("Retry-After", retrySeconds.toString())
        }
    }

    private fun resolvedHealth(status: LocalControlStatus): String {
        status.health.trim().takeIf(String::isNotEmpty)?.let { return it.take(64) }
        val network = status.diagnostics.lastNetworkState.lowercase()
        val load = status.diagnostics.lastLoadStatus.lowercase()
        val watchdog = status.diagnostics.lastWatchdogState.lowercase()
        return when {
            listOf("offline", "lost", "unavailable").any(network::contains) -> "offline"
            listOf("error", "fail", "recover", "retry").any(load::contains) ||
                listOf("error", "fail").any(watchdog::contains) -> "recovering"
            load == "loaded" || watchdog == "ok" -> "ok"
            else -> "unknown"
        }
    }

    private fun resolvedDisplayMode(status: LocalControlStatus): String {
        return status.displayMode.trim().ifBlank {
            if (status.takeoverActive) "Temporary view" else "Dashboard"
        }.take(64)
    }

    private fun resolvedCurrentPage(status: LocalControlStatus): String {
        return status.currentPage.trim().ifBlank { status.dashboardUrl }.take(2_048)
    }

    private fun resolvedLastError(status: LocalControlStatus): String {
        status.lastError.trim().takeIf(String::isNotEmpty)?.let { return it.take(255) }
        status.diagnostics.lastLoadStatus.trim().takeIf { value ->
            listOf("error", "fail", "recover", "retry").any { marker ->
                value.contains(marker, ignoreCase = true)
            }
        }?.let { return it.take(255) }
        status.diagnostics.lastWatchdogState.trim().takeIf { value ->
            listOf("error", "fail").any { marker ->
                value.contains(marker, ignoreCase = true)
            }
        }?.let { return it.take(255) }
        return status.diagnostics.lastCrashReason.trim().ifBlank { "-" }.take(255)
    }

    private fun changePassword(params: Map<String, String>, returnPath: String): Response {
        val current = params["current_password"].orEmpty()
        val next = params["new_password"].orEmpty().trim()
        val confirm = params["confirm_password"].orEmpty().trim()

        if (next.length < MIN_PASSWORD_LENGTH) {
            return redirectWithMessage("Use at least $MIN_PASSWORD_LENGTH characters", returnPath)
        }
        if (next != confirm) {
            return redirectWithMessage("New password and confirmation do not match", returnPath)
        }
        if (prefs.hasAdminPassword() && !prefs.verifyAdminPassword(current)) {
            return redirectWithMessage("Current admin password is incorrect", returnPath)
        }

        prefs.setAdminPassword(next)
        return redirectWithMessage("Admin password updated", returnPath)
    }

    private fun loginPage(errorMessage: String?): Response {
        val errorHtml = if (errorMessage.isNullOrBlank()) {
            ""
        } else {
            """<div class="error" role="alert">${escapeHtml(errorMessage)}</div>"""
        }
        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>WallMode Login</title>
              <style>${LocalControlStyle.css(prefs.load().themeMode)}</style>
            </head>
            <body class="login-page">
              <main class="card login-card">
                <div class="brand">
                  <span class="brand-icon">${LocalControlStyle.icon("brand")}</span>
                  <span><strong>WallMode</strong><small>Browser control panel</small></span>
                </div>
                <p class="eyebrow">Your display. Your space.</p>
                <h1>Welcome back.</h1>
                <p class="muted">Sign in with the same admin password used in the app.</p>
                $errorHtml
                <form method="post" action="/login" class="stack">
                  <label for="admin-password">Admin password</label>
                  <input class="field" id="admin-password" type="password" name="password" autocomplete="current-password" required autofocus />
                  <button class="btn primary" type="submit">${LocalControlStyle.icon("security")} Sign in</button>
                </form>
                <p class="login-footer">Local control · Use only on a trusted network</p>
              </main>
            </body>
            </html>
        """.trimIndent()
        return htmlResponse(html)
    }

    private fun handleLogin(session: IHTTPSession): Response {
        if (!prefs.hasAdminPassword()) {
            return loginPage("Set an admin password in WallMode before using remote control")
        }

        val password = postParams(session)["password"].orEmpty()
        if (prefs.verifyAdminPassword(password)) {
            val token = issueSessionToken()
            val response = redirect("/overview")
            setAuthCookie(response, token)
            return response
        }

        return loginPage("Incorrect admin password")
    }

    private fun handleLogout(session: IHTTPSession): Response {
        session.cookies.read(SESSION_COOKIE)?.let(sessions::remove)
        val response = redirect("/login")
        response.addHeader(
            "Set-Cookie",
            "$SESSION_COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
        )
        return response
    }

    private fun issueSessionToken(): String {
        val raw = ByteArray(24)
        secureRandom.nextBytes(raw)
        val token = Base64.encodeToString(raw, Base64.NO_WRAP)
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
        sessions[token] = LocalControlSession(
            expiresAtMillis = System.currentTimeMillis() + SESSION_TTL_MILLIS,
            credentialIdentity = prefs.adminCredentialIdentity()
        )
        return token
    }

    private fun isAuthenticated(session: IHTTPSession): Boolean {
        if (!prefs.hasAdminPassword()) {
            return false
        }
        val token = session.cookies.read(SESSION_COOKIE) ?: return false
        val authenticatedSession = sessions[token] ?: return false
        val now = System.currentTimeMillis()
        if (!authenticatedSession.isValid(now, prefs.adminCredentialIdentity())) {
            sessions.remove(token)
            return false
        }
        sessions[token] = authenticatedSession.copy(expiresAtMillis = now + SESSION_TTL_MILLIS)
        return true
    }

    private fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { it.value.expiresAtMillis < now }
    }

    private fun isPrivateClient(session: IHTTPSession): Boolean {
        val ip = runCatching { session.remoteIpAddress }.getOrNull().orEmpty().trim()
        if (ip.isBlank()) return false
        if (ip == "127.0.0.1" || ip == "::1") return true
        if (ip.startsWith("10.")) return true
        if (ip.startsWith("192.168.")) return true
        if (ip.startsWith("169.254.")) return true
        if (ip.startsWith("172.")) {
            val second = ip.split('.').getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) {
                return true
            }
        }
        return false
    }

    private fun setAuthCookie(response: Response, token: String) {
        response.addHeader(
            "Set-Cookie",
            "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Lax"
        )
    }

    private fun postParams(session: IHTTPSession): Map<String, String> {
        val files = hashMapOf<String, String>()
        runCatching { session.parseBody(files) }
        return session.parms
    }

    private fun safeReturnPath(candidate: String?): String {
        val value = candidate.orEmpty().trim()
        return if (value in PAGE_PATHS) value else "/overview"
    }

    private fun returnInput(path: String): String {
        return """<input type="hidden" name="return_to" value="$path" />"""
    }

    private fun redirectWithMessage(message: String, targetPath: String): Response {
        val encoded = URLEncoder.encode(message, Charsets.UTF_8.name())
        return redirect("$targetPath?msg=$encoded")
    }

    private fun redirect(location: String): Response {
        val response = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
        response.addHeader("Location", location)
        return response
    }

    private fun textResponse(status: Response.IStatus, text: String): Response {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", text)
    }

    private fun htmlResponse(html: String): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        return noStore(response)
    }

    private fun noStore(response: Response): Response {
        response.addHeader("Cache-Control", "no-store, private")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Referrer-Policy", "no-referrer")
        response.addHeader("X-Frame-Options", "DENY")
        return response
    }

    private fun escapeHtml(raw: String): String {
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    companion object {
        private const val TAG = "WallModeControlServer"
        private const val SESSION_COOKIE = "wallmode_session"
        private const val SESSION_TTL_MILLIS = 30 * 60 * 1_000L
        private const val SOCKET_READ_TIMEOUT = 12_000
        private const val MIN_PASSWORD_LENGTH = 4
        private const val PREVIEW_INTERVAL_MILLIS = 2_000L
        private const val MAX_PREVIEW_BYTES = 2 * 1_024 * 1_024
        private const val NO_PREVIEW_REQUEST = Long.MIN_VALUE
        const val DEFAULT_PORT = 8099

        private val PAGE_PATHS = setOf(
            "/overview",
            "/actions",
            "/dashboard",
            "/display",
            "/browser",
            "/device",
            "/system",
            "/security"
        )

        fun discoverLocalUrls(port: Int): List<String> {
            val clampedPort = KioskPreferences.clampInt(
                port,
                KioskPreferences.MIN_LOCAL_CONTROL_PORT,
                KioskPreferences.MAX_LOCAL_CONTROL_PORT
            )
            val urls = mutableListOf<String>()
            urls += "http://127.0.0.1:$clampedPort"
            urls += "http://localhost:$clampedPort"
            val seen = linkedSetOf<String>()
            asList(NetworkInterface.getNetworkInterfaces()).forEach { iface ->
                asList(iface.inetAddresses).forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress.orEmpty().trim()
                        if (host.isNotBlank()) {
                            seen += "http://$host:$clampedPort"
                        }
                    }
                }
            }
            urls += seen
            return urls.distinct()
        }

        fun discoverPrimaryUrl(port: Int): String {
            val urls = discoverLocalUrls(port)
            val preferred = urls.firstOrNull {
                it.contains("192.168.") || it.contains("10.") || it.contains("172.")
            }
            return preferred ?: urls.firstOrNull() ?: "http://127.0.0.1:$port"
        }

        private fun <T> asList(enumeration: Enumeration<T>?): List<T> {
            if (enumeration == null) return emptyList()
            return Collections.list(enumeration)
        }
    }
}
