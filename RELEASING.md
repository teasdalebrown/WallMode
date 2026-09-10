# First WallMode Android release

WallMode source is hosted at https://github.com/rvbcrs/WallMode. Successful CI builds
of `master` publish signed development APKs as GitHub pre-releases, with a unique
`build-RUN-ATTEMPT` tag pointing to the exact source commit. Branch/tag builds only
upload Actions artifacts. Pre-releases are not offered by the stable in-app updater.

CI verifies unit tests, release lint, APK signing and alignment from a clean
checkout. This does not complete the checklist below: device acceptance, media
provenance and backup rules remain open. Do not present an automated build as a
stable release until those checks are complete.

## Before tagging

- Confirm the destination repository: `origin` is `rvbcrs/WallMode`; the inherited
  KioskZen remote remains `upstream`. Never publish a WallMode release to
  `upstream` or push all inherited tags. The app's update default is `rvbcrs/WallMode`.
- Include all WallMode sources, resources, tests, blueprints and documentation
  in the release commit. Verify a build from a clean checkout.
- Document redistribution permission and attribution for bundled media,
  including `weather_clouds.mp4` and `weather_cloud_layer.png`. Keep the GPLv3
  license and KioskZen origin notice. Their media provenance still needs review.
- Complete real Home Assistant acceptance checks from
  [homeassistant/README.md](homeassistant/README.md), including announcements,
  action responses, banner buttons, timeouts and return from a camera/event view.
- Check narrow-screen settings, touch/camera wake, ambient dimming, Immich
  playback and reconnect behavior on the ThinkSmart over an extended run.
- Add and verify explicit Android 12+ data-extraction rules for cloud backup
  and device transfer. Legacy backups are disabled, but lint flags the missing
  modern rules; MQTT credentials and Immich share links must remain excluded.

## Build and verify the actual release

1. Set the intended version name and an increasing `versionCode` in
   `app/build.gradle.kts`. Choose a WallMode tag that does not conflict with
   inherited KioskZen tags.
2. Configure release signing using `keystore/signing.properties.example` as
   the template. Keep the private key and passwords out of Git, and back up
   the key securely: later APK updates need the same signing identity.
3. Follow the [low-impact build instructions](README.md#low-impact-builds).
   Run `assembleDebug`, `testDebugUnitTest`, `assembleDebugAndroidTest` and
   `lintDebug` as separate Gradle invocations, never concurrently. Run the device
   checks before proceeding. The reduced-memory profile has passed both debug
   APK builds, 46 unit tests and debug lint (0 errors, 96 warnings). CI now also
   verifies clean-checkout signed development builds.
4. Run `assembleRelease` and `lintRelease` separately with the same low-impact
   command prefix. Signing is optional in the
   Gradle file, so a successful build alone does not prove the APK is signed.
   Verify the resulting APK with the Android SDK's `apksigner verify --verbose
   --print-certs` before distribution.
5. Test the signed release package on the ThinkSmart. Release
   (`io.github.rvbcrs.wallmode`) and debug (`io.github.rvbcrs.wallmode.debug`)
   install separately. Keep debug until configuration transfer is checked;
   passwords and the Immich share link must be entered separately, and a fresh
   installation gets its own MQTT device identity.

## Publish after those checks

- Publish only to the confirmed WallMode repository, with release notes and
  **one signed release APK**. Do not attach the debug/test APKs: the current
  updater selects the first `.apk` asset.
- Provide the corresponding complete source and keep the build instructions
  available with it. Add public download and HA blueprint import links to the
  README once those destinations exist.
- Confirm the repository's Sponsor button offers Buy Me a Coffee and GitHub
  Sponsors through `.github/FUNDING.yml`.
- Verify installation from the public download. On the next version, verify
  the in-app update keeps settings and uses a higher `versionCode` with the
  same signing key.

Google Play is a separate distribution decision. Review its current payment,
privacy and APK-installation policies before preparing a store build; a
GitHub APK release does not imply Play Store approval.

## Latest local verification — 2026-09-07

- Installed the updated debug APK in place on the ThinkSmart View (Android 8).
- The device runner passed authenticated browser settings, five form
  roundtrips, three themes, CSRF, validation, stale revisions and secret
  redaction; MQTT discovery/payload checks use fixtures, not live HA automations.
- Native admin-session checks passed with isolated preference stores.
- Native banner layout, replacement, expiry, action handling and dim/screensaver
  preservation passed. UI tests now wait for a real layout frame before
  measuring controls or injecting a tap.
- Support leads the System tab in both UIs, with amber emphasis. Both native
  buttons are visible without scrolling on the ThinkSmart. External-browser intents were
  intercepted, the missing-browser fallback passed, and saved user settings
  and kiosk/security flags remained unchanged. No payment page was opened.
  The lock-task branch still needs a run with kiosk locking enabled.
- The browser support card was visually checked in light and dark themes at
  492 CSS pixels, with both buttons visible without scrolling or horizontal
  overflow. The normal app and LAN health endpoint were reachable
  again after testing. Extended device use and real HA acceptance remain open.
