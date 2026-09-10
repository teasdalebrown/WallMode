<img src="art/wallmode-icon.svg" width="80" alt="WallMode app icon" />

# WallMode

Turn an Android tablet into a reliable, remotely manageable wall display.

[Source on GitHub](https://github.com/rvbcrs/WallMode) · [Report an issue](https://github.com/rvbcrs/WallMode/issues)

WallMode is a free, open-source Android kiosk app for Home Assistant, GlassHome, and other web dashboards. It keeps a dashboard visible in an embedded Android WebView, adds native recovery when the page fails, and can switch to a dimmed or animated ambient display when the tablet is idle.

Home Assistant is optional: any valid HTTP or HTTPS dashboard can be used. Home Assistant users additionally get local server discovery, MQTT device discovery, telemetry, controls, and temporary event takeovers.

WallMode targets Android 8.0 and newer. Its primary physical test device is the Lenovo ThinkSmart View.

> **Project status:** the WallMode version line is currently `0.1.0`, but no WallMode release has been tagged yet. Build or sideload the development APK as described below.

## Highlights

- Fullscreen Android WebView kiosk for any HTTP(S) dashboard
- Compact Aurora Rail settings UI, verified at the ThinkSmart View's 492dp width
- Native loading, error, timeout, and recovery states instead of a blank page
- Dim mode plus Aurora Weather and Glow Clock screensavers with visual background choices
- Fullscreen ambient preview, smooth transitions, dynamic Aurora colors, and Immich photo clocks
- Fixed or Android-managed ambient brightness
- Touch, camera-motion, and proximity wake
- Home, Wall, and Night dashboard profiles with optional scheduling
- Home Assistant MQTT discovery, status entities, remote controls, and a standard Announcement notify entity
- Ready-made Home Assistant blueprints for announcements, temporary event views, and two-choice prompts
- Priority-aware temporary views for doorbells, cameras, and alarms
- Native MQTT action cards, non-blocking notification banners, and spoken announcements for Home Assistant automations
- Automatically discovered Action response event entity for tablet button presses
- Password-protected control panel with all saved settings available from a phone or computer
- Optional screen preview, diagnostics, configuration backup, watchdog, and updates
- Start on boot, immersive fullscreen, keep-screen-on, and lock-task support
- Optional Buy Me a Coffee and GitHub Sponsors links in both settings interfaces

## How WallMode works

WallMode has one main display surface: Android's system WebView. The configured base URL and dashboard path are combined and loaded there. WallMode watches the main page, network, and renderer, and places a native status layer over the browser whenever loading stalls or fails.

An inactivity timer can leave the dashboard unchanged, lower its brightness, or replace it with a native ambient scene. A touch always returns to the dashboard without clicking the page underneath. With presence detection enabled, detected movement also restarts the inactivity timer while the dashboard is visible.

The hidden settings gesture, local control panel, and MQTT commands all operate on the same saved configuration and current display state.

## Requirements

- Android 8.0 / API 26 or newer
- A functional Android System WebView installation
- Network access to the configured dashboard
- Camera permission only when camera-motion wake or dashboard camera access is needed
- Microphone permission only when the loaded dashboard requests microphone access
- An MQTT broker only for the optional Home Assistant MQTT integration
- An installed Android text-to-speech engine only for spoken announcements
- An Immich server reachable by the tablet only for the optional album slideshow

WallMode does not bundle GeckoView or another browser runtime. This keeps the APK small, but rendering capabilities depend on the Android System WebView installed on the device.

## Quick start

1. Install and open WallMode.
2. Tap the hidden admin corner five times within 2.5 seconds. The default corner is the top right.
3. In **Dashboard**, enter the dashboard base URL, for example `http://192.168.0.248:3124`.
4. Optionally enter a dashboard path, such as `?d=main`, and enable `?kiosk` appending if the dashboard supports it.
5. Use **Test URL** to check basic HTTP reachability.
6. Choose **Save & open dashboard**.
7. Configure ambient display, presence wake, security, or integrations as needed.

A direct IP address is often more reliable than an mDNS name such as `homeassistant.local` on older Android devices.

## Opening settings

The admin control is intentionally invisible over the dashboard. Tap its configured corner five times within 2.5 seconds.

The corner can be moved to top left, top right, bottom left, or bottom right under **Display → Screen & appearance**. This prevents the gesture area from blocking an important dashboard control.

An admin password can protect either entry to settings or only the **Exit kiosk** action. The password must contain at least four characters. Configuration reset keeps the admin password unchanged.

**Admin unlock timeout** remembers a successful settings unlock for the configured number of minutes. Reopening settings does not extend that period. Changing the password or restarting the app process clears the remembered access; changing orientation does not. **Exit kiosk** still requires a fresh password whenever an admin password is set. Browser-panel login sessions are separate.

## Aurora Rail settings

The settings interface is divided into five sections:

| Section | What it controls |
| --- | --- |
| **Dashboard** | Base URL, path, kiosk query, URL test, Home Assistant discovery, loading recovery, and Home/Wall/Night profiles |
| **Display** | WallMode theme, fullscreen, keep screen on, admin gesture corner, idle behavior, ambient scene and background, brightness, weather location, and presence wake |
| **Browser** | WebView compatibility profile, mixed content, third-party cookies, autoplay, desktop mode, and custom user agent |
| **Device** | Start on boot, lock task, admin password, LAN control, Home Assistant MQTT, and kiosk exit |
| **System** | Watchdog, daily maintenance, app updates, diagnostics, import/export, reset, and voluntary project support |

On a narrow tablet the rail becomes compact icon tabs and content is kept in one column. The spacing and controls are tuned to remain usable at the ThinkSmart View's 492dp width; the Ambient Studio background row scrolls horizontally instead of squeezing its choices. Wider displays show a vertical navigation rail and can use multiple content columns.

## Dashboard and browser

### Dashboard source

WallMode accepts a base URL using `http://` or `https://`. The optional dashboard path is appended to it. A blank path opens the base URL.

When **Append `?kiosk`** is enabled, WallMode adds `?kiosk` or `&kiosk` only when that parameter is not already present. WallMode does not otherwise hide controls inside the dashboard itself.

**Test URL** performs a short HTTP request and treats redirects and HTTP responses from 200 through 399 as reachable. The real page can still fail later because of authentication, JavaScript, certificates, or WebView compatibility; use Diagnostics and the browser options in that case.

The configurable reload interval is the delay before retrying after a load failure. It is not a periodic page-refresh interval.

### Home Assistant discovery

**Auto-find Home Assistant on startup** tries once when the app is active and a network is available, and only while the base URL is still the default `http://homeassistant.local:8123`. A chosen GlassHome or other server URL is never replaced automatically. Use **Discover now** to deliberately change an existing server.

**Discover now** first checks common local names on port 8123 and then scans the tablet's private IPv4 `/24` subnet on that port for Home Assistant signatures. The scan runs for at most about 12 seconds. Found servers can be selected as the dashboard base URL.

Discovery is only a convenience and is limited to the standard Home Assistant port; a URL can always be entered manually.

### WebView profiles

Both browser choices use the installed Android System WebView:

- **Android WebView** uses the normal device user agent.
- **Chromium-like** uses a Chromium-style user agent and removes the WebView marker for sites that reject or misclassify embedded browsers.

The Chromium-like option is a compatibility profile, not a separate Chromium Custom Tab, Chromium runtime, or Gecko engine.

Additional page compatibility switches are available for:

- HTTP content embedded in an HTTPS page
- Third-party cookies
- Media autoplay
- Desktop user agent
- A fully custom user-agent string

WallMode enables JavaScript, DOM storage, image loading, and Android Safe Browsing. It disables popup windows and direct file/content access. Top-level remote navigation is limited to HTTP and HTTPS schemes.

When a page requests camera or microphone access, WallMode only considers it for the host configured as the dashboard base URL and still requests the corresponding Android permission.

## Ambient display

After the configured idle delay, WallMode can:

- do nothing;
- dim the dashboard; or
- show a native screensaver.

The idle delay accepts 15–3600 seconds. Ambient brightness can be a fixed 5–100 percent or follow Android's brightness management. Turning adaptive brightness off uses the saved fixed percentage. The same brightness rule is applied to dim and screensaver modes.

Any screen touch restores the dashboard and consumes that touch so the underlying page does not receive an accidental tap.

### Ambient Studio

Choose the screensaver background from a horizontal row of visual tiles under **Display → Ambient display**. The selected tile receives the Aurora accent treatment and WallMode only shows the fields relevant to that choice. Swipe the row to reach:

- WallMode's condition-aware effects;
- the bundled cloud image or looping cloud video;
- a direct image or video URL; or
- a shared Immich album.

The three bundled choices work offline. Custom media uses HTTP(S) without embedded credentials. Images are limited to 8 MiB and sampled to at most 1280 pixels on the longest side; videos use Android's native player and loop silently. A modest H.264 MP4 is the most compatible choice for older tablets.

**Preview now** opens the current scene, background, clock, weather, and configured ambient brightness fullscreen without first saving the settings. Tap the preview anywhere to return to the same settings screen. If remote media cannot be loaded, WallMode shows the built-in effect and reports the failure.

Entering and leaving the screensaver uses a short alpha-and-scale transition. Photo changes use a slower crossfade so an Immich slideshow does not visibly jump between frames. Aurora accents respond to the current weather and day/night state. With a photo background, WallMode samples a small fixed grid from the image and derives a stable accent while keeping it bright enough against the dark clock card.

### Immich photo clock

The **Immich album** background displays photos from one shared album in shuffled order, avoids immediately repeating the last image when reshuffling, and places the clock over the lower part of the photo. **Change photo every** accepts 10–3600 seconds and defaults to 30 seconds. WallMode requests display-sized previews as needed; it does not download the originals or import the album into the app.

Create the link in Immich as follows:

1. Open the album in Immich and choose its share action.
2. Create a public share link for that album.
3. Turn **Allow upload**, **Allow download**, and **Show metadata** off.
4. Leave the password empty; password-protected Immich links are not supported.
5. Create the link and copy the complete URL, including `https://` and the full `/share/...` path. Plain HTTP is accepted only for private LAN addresses such as `192.168.x.x` or `.local` hosts.
6. On the tablet, set an admin password under **Device** if needed, then enable **Device → Browser Control Panel (LAN)** and apply it. Open the displayed panel URL on your phone or computer and sign in with the WallMode admin password.
7. In the browser, open **Display → Photo clock & background**. Turn on **Enable inactivity mode** and **Show screensaver instead of only dimming**, select **Immich album** under **Ambient background**, paste the full link, and set **Change photo every (seconds)**.
8. Close native WallMode settings on the tablet, then click **Save Display settings** in the browser. The photo screensaver starts after the configured idle timeout. The same page also contains brightness and motion settings; leave their existing values unchanged if you only want to configure photos.

You can also enter the link directly on the tablet under **Display → Ambient display → Immich album**, use **Preview now**, and save there. The browser panel is plain HTTP: use it only on a trusted local network, never through an internet port forward.

Anyone who obtains this public link can access the shared album, so revoke it in Immich if it is exposed. The link is readable and editable in the tablet settings and authenticated browser panel. WallMode stores it on the device and excludes it from configuration exports, diagnostics, and logs.

Google Photos is roadmap-only and is not supported in this build. The correct integration for a long-running photo-frame experience is Google's [Ambient API](https://developers.google.com/photos/ambient/guides/get-started), which requires acceptance into the [Google Photos Partner Program](https://developers.google.com/photos/partner-program/overview); the post-2025 Library API cannot read an existing personal library, and the Picker API is not a persistent album source.

### Aurora Weather

Aurora Weather combines a large back-glow clock and date with the current temperature, condition, and selected place. It uses condition-dependent sun or moon, rain, snow, fog, and lightning effects, plus a bundled continuously looping cloud video where appropriate.

Enter a city under **Display → Ambient display**. WallMode resolves the city and refreshes current conditions from Open-Meteo every 30 minutes while the weather scene is active. It shows current conditions, not a forecast or live weather imagery. Weather requires internet access; the dashboard itself can remain entirely local.

### Glow Clock

Glow Clock is a clock-only scene with a stronger light glow, weekday, date, seconds, and 60 subtle markers around the rounded clock frame. The marker for the current second pulses smoothly from dark orange to a bright flash and back. It does not make a weather request. Its time comes from the Android device clock.

### Presence wake

Optional presence wake restarts the inactivity timer and restores the dashboard from dim or screensaver mode using:

- a far-to-near proximity-sensor transition; or
- lightweight motion analysis from the front camera, with rear-camera fallback.

This detects image changes, not a person or face. Camera frames are analyzed locally in memory and are not recorded or uploaded. When ambient mode and presence wake are enabled, the camera remains active while the normal dashboard is visible and stops when WallMode is paused. Each confirmed movement starts a fresh idle interval: with a 120-second timeout, the screensaver starts 120 seconds after the last detected movement or touch. A configurable 2–60 second cooldown reduces repeated wakes but never blocks timer resets while the dashboard is visible. Exposure-change filtering reduces false motion events.

On first launch, WallMode shows a one-time explanation before requesting camera permission. Accepting that request also enables presence wake; it can be disabled later in Settings.

## Dashboard profiles

Home, Wall, and Night profiles each define a relative dashboard path and a unique start hour. A blank profile path falls back to the main dashboard path. The default boundaries are 06:00, 10:00, and 21:00, with scheduling disabled.

The schedule works across midnight and does not depend on the order in which the profiles are listed. A profile can also be selected remotely through MQTT. When scheduling is disabled, that selection remains active. When scheduling is enabled, it is temporary; a reload or the next scheduled boundary selects the currently scheduled profile again.

If a scheduled change occurs during a temporary event takeover, WallMode waits until the takeover closes and then opens the correct profile.

## Home Assistant MQTT

Enable **Home Assistant MQTT device** under **Device → Home Assistant**, then configure the broker host, port, optional TLS, and optional username/password. A password is accepted only together with a username. WallMode uses MQTT 3.1.1 with QoS 1, a retained availability state, a last will, and automatic reconnect with backoff.

WallMode publishes one MQTT device-discovery document. Home Assistant creates entities for:

- battery, charging, screen, network, dashboard health, and screensaver state;
- motion and temporary-takeover state;
- current page, active profile, app version, and WebView version;
- ambient brightness, idle timeout, scene, browser profile, and display mode;
- profile schedule and Home/Wall/Night buttons;
- dashboard reload, browser restart, and remote URL opening;
- an **Announcement** notify entity for Home Assistant's standard `notify.send_message` action;
- an **Action response** event entity for action-card and banner button presses.

For normal announcements, select the WallMode **Announcement** entity in Home Assistant and enter a message; no MQTT topic or JSON is needed. The entity is added to the existing WallMode device automatically after upgrading/reconnecting. WallMode's device-based MQTT discovery requires Home Assistant 2024.11 or newer.

The [Home Assistant starter blueprints](homeassistant/README.md) provide device selection, triggers, and optional conditions for announcements, temporary doorbell/camera pages, and two-choice action cards. Home Assistant owns the automation and its conditions; WallMode handles speech and display. These files are included in the repository; see the guide for local installation until public import URLs are published.

Advanced automations can also publish native action cards, notification banners, and spoken announcements directly to WallMode's command topics.

The persistent `<device-suffix>` is generated on the tablet. Find it in an MQTT browser by looking for `homeassistant/device/wallmode_.../config`, or inspect the discovery topic created for the device. MQTT topics use this layout:

| Purpose | Topic |
| --- | --- |
| Home Assistant discovery | `homeassistant/device/wallmode_<device-suffix>/config` |
| Availability | `wallmode/<device-suffix>/availability` |
| State | `wallmode/<device-suffix>/state` |
| Motion event | `wallmode/<device-suffix>/event/motion` |
| Action-card or banner-button response | `wallmode/<device-suffix>/event/action` |
| Commands | `wallmode/<device-suffix>/command/<command>` |

WallMode republishes discovery when Home Assistant announces that it is online. State and availability are retained; motion and button-response events are not. State is also refreshed every 60 seconds.

### MQTT commands

Commands must be non-retained; QoS 1 is recommended. Unsupported, malformed, oversized, or out-of-range payloads are ignored.

| Command suffix | Accepted payload |
| --- | --- |
| `reload` | `PRESS` |
| `restart_engine` | `PRESS` |
| `open_url` | A valid HTTP(S) URL |
| `takeover` | JSON described below |
| `banner` | JSON described below |
| `announce` | JSON described below |
| `stop_announcement` | `PRESS` |
| `ambient_brightness` | Integer `5`–`100` |
| `ambient_timeout` | Integer `15`–`3600` |
| `ambient_scene` | `Aurora weather` or `Glow clock` |
| `profile` | `Home`, `Wall`, or `Night` |
| `profile_schedule` | `ON` or `OFF` |
| `browser_engine` | `Android WebView` or `Chromium-like` |
| `display_mode` | `Dashboard`, `Dim`, or `Clock + weather` |

For example:

```shell
BROKER_HOST=192.168.0.10
DEVICE_SUFFIX=replace_with_discovered_suffix
mosquitto_pub -h "$BROKER_HOST" -q 1 \
  -t "wallmode/$DEVICE_SUFFIX/command/reload" \
  -m 'PRESS'
```

Published current-page telemetry excludes credentials, query parameters, and URL fragments.

## Temporary event takeovers

A takeover temporarily opens another HTTP(S) page for events such as a doorbell, camera, or alarm. It records the exact previous page and whether the display was showing the dashboard, dim mode, or a screensaver. When the event expires or is dismissed, that state is restored.

Show a view for 30 seconds at priority 50. Omitting `-r` keeps this command non-retained:

```shell
BROKER_HOST=192.168.0.10
DEVICE_SUFFIX=replace_with_discovered_suffix
mosquitto_pub -h "$BROKER_HOST" -q 1 \
  -t "wallmode/$DEVICE_SUFFIX/command/takeover" \
  -m '{"action":"show","id":"front-door","url":"http://192.168.0.248:3124/?d=doorbell","ttl":30,"priority":50}'
```

Clear it early using the same ID:

```shell
BROKER_HOST=192.168.0.10
DEVICE_SUFFIX=replace_with_discovered_suffix
mosquitto_pub -h "$BROKER_HOST" -q 1 \
  -t "wallmode/$DEVICE_SUFFIX/command/takeover" \
  -m '{"action":"clear","id":"front-door"}'
```

Rules:

- IDs contain 1–64 letters, numbers, underscores, or hyphens.
- `ttl` accepts 5–600 seconds and defaults to 30.
- `priority` accepts 0–100 and defaults to 50.
- A different event replaces the current event only at equal or higher priority.
- Updating the same ID refreshes its view and lifetime without lowering its priority.
- Takeover state survives Android activity recreation, but not a process kill, force-stop, or reboot.
- Automatic recovery does not replace an active takeover.

The LAN control panel can also start and dismiss a temporary takeover.

## Native action cards

An action card is a native, fullscreen prompt for short choices such as opening a door or dismissing an alarm. Publish it through the existing `takeover` command with `kind` set to `card`:

```shell
mosquitto_pub -h "$BROKER_HOST" -q 1 \
  -t "wallmode/$DEVICE_SUFFIX/command/takeover" \
  -m '{"action":"show","kind":"card","id":"front-door","title":"Front door","message":"Open the door?","ttl":30,"priority":60,"actions":[{"id":"open","label":"Open"},{"id":"dismiss","label":"Dismiss"}]}'
```

Cards accept one to three actions. A selected action is published with QoS 1 and without retention to `wallmode/<device-suffix>/event/action`:

```json
{"event_type":"action","card_id":"front-door","action_id":"open","event_id":"a-generated-uuid"}
```

The card closes only after WallMode accepts the response for publishing. If MQTT is offline, it remains visible and reports the error. Card IDs, priorities, lifetimes, replacement rules, and state restoration follow the same rules as URL takeovers.

### Action response entity

The same response appears on the automatically discovered **Action response** MQTT event entity in Home Assistant. Its state is the timestamp of the latest event, not the button name; attributes contain `event_type: action`, `card_id`, `action_id`, and `event_id`. Repeated presses of the same choice produce new events. MQTT event entities discard replayed retained messages. [Home Assistant MQTT event documentation](https://www.home-assistant.io/integrations/event.mqtt/)

Use this entity in your own automations without subscribing to an MQTT topic. Match both the prompt ID and action ID, guard against startup/availability changes, and keep actions idempotent because MQTT QoS 1 is not exactly-once delivery. The [Home Assistant guide](homeassistant/README.md#action-response-entity) includes a safe example and a tablet-button test. The existing two-button blueprint remains compatible and still correlates replies using its run-specific card ID.

## Non-blocking notification banners

A banner shows a short Aurora-styled notice over the current dashboard or screensaver without changing its URL, ambient state, or brightness. It uses the screen edge opposite the hidden admin corner so settings stay reachable. Other dashboard controls remain usable. A banner is independent of takeovers and spoken announcements.

Publish to `wallmode/<device-suffix>/command/banner` with QoS 1 and retention disabled:

```json
{"action":"show","id":"laundry","title":"Wasmachine","message":"De was is klaar","level":"success","ttl":10,"sound":true,"button":{"id":"ack","label":"Gezien"}}
```

- `id`: 1–64 ASCII letters, numbers, underscores, or hyphens.
- `message`: required, 1–300 characters; `title`: optional, at most 80 characters. Text is plain, single-line text, not HTML. Emoji can count as two characters.
- `level`: `info`, `success`, `warning`, or `error`; defaults to `info`.
- `ttl`: 3–120 seconds; defaults to 10.
- `sound`: optional boolean, default `false`; plays a short Android notification sound at the system's current volume, not speech.
- The optional `button` has an `id` of 1–32 ASCII letters, numbers, underscores, or hyphens and a `label` of 1–24 characters. A press publishes the normal **Action response** event with the banner ID as `card_id`.

There is one visible banner: a new notice replaces it and starts a fresh lifetime. Its close button dismisses only that notice; touching inside does not wake the screensaver. Touching outside follows the normal dashboard/ambient touch behavior. A failed MQTT button response shows an error instead of dismissing the notice; its original expiry still applies. Clearing an old ID cannot remove a newer notice:

```json
{"action":"clear","id":"laundry"}
```

Banners are not queued or restored after an activity/app restart. They do not change the system volume or automatically start TTS. See the [Home Assistant test steps](homeassistant/README.md#notification-banner-test) for a safe first run.

## Spoken announcements

WallMode can read a short message through Android's installed text-to-speech engine without changing the tablet's system volume. To test from Home Assistant:

1. Open **Settings → Devices & services → MQTT**, then your **WallMode** device. Find its **Announcement** entity.
2. Open **Developer tools → Actions**, choose **Send a notification** (`notify.send_message`), and select that entity as the target.
3. Enter a short message such as “WallMode is connected” and run the action. The tablet should speak without leaving its current page or screensaver.

The standard action uses volume 80/100 within Android's current audio volume. It does not use the optional notification title. Speech requires a working Android TTS engine and an audible system volume. Empty messages, control characters/newlines, and messages longer than 255 characters are rejected. A new message replaces the current one; this is not an announcement queue.

For reusable triggers and conditions, use the **WallMode — spoken announcement** blueprint described in the [starter guide](homeassistant/README.md). For explicit per-message volume, the existing advanced MQTT command remains available:

```shell
mosquitto_pub -h "$BROKER_HOST" -q 1 \
  -t "wallmode/$DEVICE_SUFFIX/command/announce" \
  -m '{"text":"Someone is at the front door","volume":80}'
```

Messages contain 1–255 characters. `volume` is optional, accepts 0–100, and defaults to 80. A new announcement replaces one already speaking. Publish `PRESS` to `command/stop_announcement` to stop speech immediately.

## LAN control panel

The optional local control panel provides browser-based management from another device on the same trusted network. It is disabled by default and cannot be signed into until an admin password is set.

The panel uses the app's Aurora Rail styling: teal accents, rounded glass-like cards, matching outline icons, and the selected light/dark/system theme. Wide screens get a navigation rail; phones get a compact labelled icon grid. Settings remain grouped by tab, with a save bar that stays in reach while scrolling. All styling and icons are included in the APK, with no external fonts or UI libraries.

After enabling it, open the URL shown in Settings, normally `http://<tablet-ip>:8099`, and sign in with the WallMode admin password. The port can be changed from 1024 through 65535.

The panel includes:

- an overview of health, current page, display mode, MQTT, takeover, and errors;
- reload, browser restart, settings, URL, and takeover actions;
- all 57 saved options, grouped into **Dashboard**, **Display**, **Browser**, **Device**, and **System**, including profiles, all ambient backgrounds, brightness, motion wake, compatibility, MQTT, watchdog, maintenance, and update-check settings;
- manual Home Assistant discovery;
- MQTT-password replacement and removal under **Device**;
- admin-password change and logout;
- authenticated JSON status at `/api/status`.

Each settings tab loads the saved values. URLs, including the Immich share link, are readable and editable; stored MQTT and admin passwords are never returned to the browser. Use **Save Dashboard settings**, **Save Display settings**, **Save Browser settings**, **Save Device settings**, or **Save System settings** to apply only that tab. Other tabs keep their saved values.

Save errors are shown beside the button without clearing your edits. This includes invalid values, settings changed since the page was opened, native settings still open on the tablet, or an active event/camera takeover. Close tablet settings or wait for the event before retrying. For a stale page, copy any edits you want to retain before reloading the current values. JavaScript must be enabled for this in-place save behavior.

Under **Device**, a blank new MQTT password keeps the stored password only when the broker host, port, TLS choice, and username stay the same. Changing that identity clears the old password; enter the new broker's credentials if required. Use **Remove the saved MQTT password** to explicitly clear it. Changing the LAN port shows the new address to reconnect and sign in; disabling the panel shows a confirmation and requires re-enabling it on the tablet. A theme change may also require signing in again.

Settings parity does not mean every tablet action can run remotely. Android permissions, device-owner provisioning, and APK-install confirmations still require the tablet. Ambient **Preview now**, configuration import/export/reset, and manual update-check/install actions remain in the native settings. The existing **Overview**, **Actions**, and **Security** pages remain available.

Sessions use a 30-minute sliding inactivity timeout. Changing the admin password immediately invalidates existing sessions.

### Screen preview

Screen preview is a separate opt-in setting. When enabled, an authenticated user can manually fetch a current JPEG of the visible dashboard, recovery layer, or ambient display. It is not a continuous video stream.

Preview requests are rate-limited, not cached, limited to one capture at a time, reduced to at most 720 pixels on the longest side, and capped at 2 MiB. Preview is unavailable while WallMode is not the visible, focused app.

The control panel is plain HTTP and is intended only for a trusted private LAN. WallMode rejects requests that do not originate from loopback, private IPv4, or link-local addresses. Do not expose or port-forward its port to the internet.

## Reliability and recovery

### Native loading and error state

WallMode immediately shows a native dark loading layer while a page starts. It detects main-frame network failures, HTTP errors, renderer loss, and a 30-second load timeout. This keeps an old or unreachable dashboard from appearing as an unexplained white screen.

Automatic reload after a failure is optional and uses a configurable delay of 5–600 seconds. A successful visible load cancels old retries and stale callbacks from earlier navigation attempts are ignored.

When Android reports that the network is lost, WallMode shows a recovery state. It reloads shortly after connectivity returns.

### Health watchdog

The watchdog is enabled by default and requests a configurable path below the dashboard base URL. Its default is `api/` every 45 seconds; the interval accepts 15–1800 seconds. Any HTTP response from 100 through 499 counts as transport-level reachability.

After two consecutive failures WallMode schedules a reload when automatic recovery is enabled. After four it recreates the activity and WebView. Recreation is skipped while a temporary takeover is active; a later failed check can trigger it after the takeover ends.

### Daily maintenance

Optional daily maintenance clears the active WebView cache and reloads the active takeover or the dashboard/profile appropriate at that moment. It is disabled by default and does not clear cookies or site data or restart Android.

### Renderer recovery

If Android reports that the WebView renderer has disappeared, WallMode records the crash and recreates the activity and browser surface.

## App updates

Update checks are optional and disabled by default. WallMode queries the latest GitHub release for the configured `owner/repository`, compares semantic versions, and looks for the first APK release asset.

Checks can be started manually. When periodic checks are enabled, WallMode checks at app start if the configured 1–168 hour interval has elapsed; the default interval is 12 hours. It is not a continuously running background scheduler. WallMode uses Android's Download Manager and package installer. Android may require confirmation and permission to install apps from this source. WallMode does not silently install an APK.

## Diagnostics and configuration

The native Diagnostics screen reports:

- active browser profile;
- camera and microphone permission state;
- network, MQTT, watchdog, and update state;
- last URL and load result;
- last recorded renderer crash.

Diagnostics can be refreshed or exported as text. An export can contain dashboard URLs and hostnames, so review it before sharing.

Settings can be exported to JSON and pasted into another installation. Admin and MQTT passwords and the persistent MQTT device key are not included. Import is intended for a complete WallMode export, validates ranges, and rejects invalid profile schedules. A broker identity change clears any stored MQTT password. The exported configuration can still contain dashboard URLs, broker hostnames, and the MQTT username, so review it before sharing.

The Immich public-album share URL is also excluded from configuration exports, diagnostics, and logs. It must be entered separately on each WallMode device.

Reset restores dashboard, display, browser, device, and system defaults while retaining the admin password and MQTT device key; it clears the MQTT password.

## Kiosk and device behavior

- **Immersive fullscreen** hides Android system bars where the device permits it.
- **Keep screen on** prevents normal sleep while WallMode is in the foreground.
- **Start after boot** launches WallMode after Android sends the completed-boot event. Device-vendor background restrictions may still limit this.
- **Lock task** uses Android's kiosk/pinning mechanism and requires suitable device-owner or system policy on fully managed devices.
- **Exit kiosk** stops lock task when active, returns to the Android launcher, and can be password-protected.
- WallMode's dashboard and settings windows set Android's secure flag to discourage screenshots and screen recordings by other apps.

## Security and privacy

- WallMode can run with a fully local dashboard and without a WallMode cloud service.
- Aurora Weather contacts Open-Meteo only when configured and active.
- Immich is contacted only when its album background is selected or previewed; the public share link remains device-local and is not exported or logged.
- Update checks contact GitHub only when enabled or manually requested.
- Support links open Buy Me a Coffee or GitHub Sponsors only after you choose to open them. WallMode does not process payments or embed donation trackers.
- The local control panel and screen preview are disabled by default.
- Admin passwords are salted and hashed with PBKDF2-HMAC-SHA256.
- Passwords are omitted from configuration and diagnostics exports.
- MQTT credentials should be used with TLS; WallMode warns when credentials are configured over plaintext MQTT.
- MQTT TLS uses Android's trust store; a self-signed broker certificate is not accepted unless the device trusts it.
- MQTT command input and remote URLs are length-, type-, range-, and scheme-validated.
- Retained MQTT commands are rejected so old actions are not replayed after reconnect.
- Android app backup is disabled.

WallMode permits cleartext HTTP because many local dashboards use it. Prefer HTTPS for dashboards and TLS for MQTT whenever the surrounding system supports them.

## Build and install

Build requirements are JDK 17 and Android SDK 36.

Build the debug APK, then run the tests and Android lint as separate steps:

```shell
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

### Low-impact builds

The project defaults favor a responsive computer: one Gradle worker, no parallel
project execution, low process priority, a 1 GiB Gradle/Kotlin heap, and reduced
JVM thread-pool sizing. Kotlin runs inside Gradle instead of starting a separate
compiler daemon. Unit tests use one separate JVM with a 256 MiB heap. Gradle may
start a single-use daemon, but it exits when that invocation finishes.

On macOS, also run the build with background scheduling/I/O policy:

```shell
taskpolicy -b nice -n 15 ./gradlew --no-daemon --max-workers=1 assembleDebug
```

Use the same prefix for subsequent test/lint tasks. Run only one build invocation
at a time, and do not override the worker count upward on a busy desktop. These
settings are not a hard total-RAM or CPU-percentage cap: native memory and Android
tools add overhead. Debug APK and instrumentation APK builds, all 46 unit tests,
and debug lint have passed with this profile. Lint reports 0 errors and 96
warnings; clean-checkout and signed release builds still need verification. If
a task runs out of heap, review the measured usage before raising limits or retrying.

### APK location and installation

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For a device with wireless ADB already enabled:

```shell
TABLET_IP=192.168.0.19
adb connect "${TABLET_IP}:5555"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug package ID is `io.github.rvbcrs.wallmode.debug`; the release package ID is `io.github.rvbcrs.wallmode`.

Before publishing the first signed APK, follow [RELEASING.md](RELEASING.md). A debug build is not a verified public release.

## Support WallMode

WallMode is free and open-source. All app features are available without donating. If you would like to support development:

- [Buy Me a Coffee](https://buymeacoffee.com/rvbcrs) — a one-off thank you.
- [GitHub Sponsors](https://github.com/sponsors/rvbcrs) — support ongoing development.

These are the same support destinations used by OpenNova. Find **Support WallMode** at the top of the **System** tab in the Android settings or browser control panel, highlighted in amber.

On Android, choose a provider, then **Open browser** or **Copy link**. If kiosk mode prevents another app from opening, use the link on your phone or computer instead. In the browser panel the links open a new tab on that computer or phone, leaving the wall display and unsaved settings untouched. WallMode never handles the payment itself.

## Troubleshooting

### The dashboard stays white or fails to load

1. Use **Dashboard → Test URL**.
2. Try the server's direct IP address instead of a `.local` name.
3. Update Android System WebView to the newest version supported by the device.
4. Try the Chromium-like profile or desktop user agent.
5. Enable mixed content only if an HTTPS page embeds required HTTP resources.
6. Check Diagnostics for the last URL, load state, network, and active browser profile. The WebView version is also available through the Home Assistant MQTT device.

### A dashboard button overlaps the hidden settings corner

Move the admin gesture under **Display → Screen & appearance**.

### Motion does not wake the screen

Confirm that an ambient mode and presence wake are enabled and camera permission is granted. Movement while the dashboard is visible must postpone the idle deadline; the cooldown applies only to waking from dim or screensaver mode.

### The LAN control panel does not start

Set an admin password before trying to sign in, keep the controlling device on the same private network, and verify that the selected port is not already in use.

### The MQTT device does not appear in Home Assistant

Verify broker reachability, port, TLS, and credentials; then check MQTT state in Diagnostics. WallMode republishes its discovery document after receiving `online` on `homeassistant/status`.

## Known development limitations

- Browser feature support ultimately depends on the system WebView supplied for the Android device.
- Lock task cannot become a fully managed kiosk unless Android grants the app the required device-owner policy.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the current unreleased WallMode changes from its KioskZen foundation.

## Origin and license

WallMode is based on [KioskZen](https://github.com/exraaaa/KioskZen). The inherited `v1.x` tags in this repository belong to KioskZen; the WallMode version line restarts at `0.1.0`.

Because WallMode uses a new application ID, it installs alongside KioskZen rather than upgrading it in place. Existing KioskZen settings are not migrated automatically.

WallMode is distributed under the [GNU General Public License v3.0](LICENSE).
