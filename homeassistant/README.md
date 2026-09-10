# Home Assistant automations for WallMode

Select your tablet and fill in ordinary Home Assistant forms. The three blueprints handle MQTT topics and JSON internally. This guide also covers the discovered Action response entity and direct notification-banner commands.

## Before you start

- Use Home Assistant **2024.11 or newer**. WallMode uses MQTT device-based discovery, introduced in that release. [Home Assistant release notes](https://www.home-assistant.io/blog/2024/11/06/release-202411/#noteworthy-improvements-to-existing-integrations)
- In WallMode, enable **Device → Home Assistant → Home Assistant MQTT device** and connect it to the same broker as Home Assistant's MQTT integration.
- Find the **WallMode** device under **Settings → Devices & services → MQTT**. For spoken messages, install an Android text-to-speech engine on the tablet and ensure the new **Announcement** entity is enabled and available.
- No HACS component, cloud account, or Home Assistant access token is needed for these automations.

The tablet selectors show MQTT devices because Android manufacturers vary. Choose the actual WallMode device; selecting another MQTT device stops the automation with a useful error in its trace. Renaming the tablet does not change its MQTT address.

## Install the blueprints once

These files are included locally with WallMode. They are not yet published at a public import URL; the Home Assistant **Import blueprint** URL dialog cannot import a file from this checkout.

1. Open your Home Assistant configuration directory using File editor, Studio Code Server, Samba, or your existing configuration-management workflow. This is the directory containing `configuration.yaml` (usually `/config` on Home Assistant OS).
2. Create `blueprints/automation/wallmode/` there and copy these three files into it, keeping their names:
   - [announce.yaml](blueprints/automation/wallmode/announce.yaml)
   - [event_view.yaml](blueprints/automation/wallmode/event_view.yaml)
   - [action_card.yaml](blueprints/automation/wallmode/action_card.yaml)
3. Go to **Settings → Automations & scenes → Blueprints** and refresh the page. Select a WallMode blueprint and **Create automation**.
4. Choose the tablet, add a trigger and fill in the other fields. **Only if** accepts normal Home Assistant conditions, such as presence or allowed hours. Leave it empty for no restriction.
5. Give the automation a name and save it. Copying the files alone does not create or enable any automation.

To update an existing installation, replace only these blueprint files, then reload automations under **Developer tools → YAML → Automations**. Reloading stops active automation runs, including a pending action-card response; avoid doing it while you are answering a card. The tablet still expires the card automatically. [Home Assistant blueprint instructions](https://www.home-assistant.io/docs/automation/using_blueprints/)

## 1. Spoken announcement

Use **WallMode — spoken announcement** for a short message when something happens: for example, “The washing machine is finished.” Pick a trigger and enter a message of 1–255 characters on one line.

The blueprint calls the standard **Notifications: Send a notification message** (`notify.send_message`) action for your selected tablet. It uses WallMode's 80% announcement volume. Speech uses Android's installed voice, not Home Assistant's TTS provider. A new announcement replaces one already speaking.

You can also use the new **Announcement** entity directly in any automation: **Add action → Notifications: Send a notification message**, select the WallMode Announcement entity and enter **Message**. No blueprint is needed for that. MQTT notify accepts message text; it is not a camera, image, or per-message volume API. [MQTT notify](https://www.home-assistant.io/integrations/notify.mqtt/)

**Safe first test:** choose a manually controlled helper as the trigger and enter “WallMode test.” Trigger it and listen on the tablet.

## 2. Doorbell or event view

Use **WallMode — doorbell or event view** to show a temporary web page when your doorbell rings or another event occurs.

- **When to show the page:** choose the doorbell's button-pressed trigger in Home Assistant.
- **Camera or dashboard page URL:** paste a complete `http://` or `https://` URL that already plays correctly in WallMode. A dedicated Home Assistant camera dashboard page is suitable if the tablet can authenticate to it.
- **Show for:** 5–600 seconds; the default is 30 seconds.
- **Priority:** the default is 70. A higher priority can replace a lower-priority event view or card.
- **Optional spoken message:** for example, “Someone is at the front door.” Leave empty for no speech; its volume is adjustable here.

The tablet restores its previous display state when the view expires or is dismissed, including the screensaver if it was active. A later trigger can replace the view and start a fresh display period. There is no delayed “clear everything” command that could dismiss a newer event accidentally.

**Camera limitation:** this opens a web page; it does not turn a `camera.*` entity, RTSP address, or arbitrary stream into a compatible player. Live video depends on the page's player, authentication, Android WebView and codec support. Test the exact URL on the tablet first. Do not embed usernames/passwords in the URL. Very long URLs are rejected to keep the complete command within WallMode's size limit.

**Safe first test:** use a temporary test trigger, a harmless dashboard URL and 5 seconds. Confirm that the page appears and the previous display returns. Then choose the real doorbell trigger and camera page.

## 3. Two-button action card

Use **WallMode — two-button action card** for a question such as “Start vacuuming?” Enter a title, optional explanation, two button labels, and the Home Assistant action sequence for each button. Either action sequence may be empty, making that button a simple dismissal.

Limits are an 80-character title, 300-character explanation, 24 characters per button, 5–600 seconds to respond, and priority 0–100. Text is single-line. Emoji can count as two characters because WallMode uses Android's text-length limits.

Only the first valid reply to the current card runs a selected action. Old replies and replies for another card do not match the fresh run-specific card ID. A dismissed or expired card runs no actions. The automation ignores repeated triggers while waiting or while a selected action is running. Another automation can still show a higher-priority event; an unanswered interrupted card simply times out.

Use harmless actions for the first test, such as creating a Home Assistant persistent notification with “First button pressed” or “Second button pressed.” Test both buttons in separate runs, including a tap as soon as the card appears, then let a third card expire and confirm that neither action runs. Only then select your real actions. Anyone who can touch the tablet can press these buttons; do not treat a card as authenticated approval for unlocking doors or other sensitive operations.

The card appears after a one-second preparation delay, giving Home Assistant time to subscribe for the reply. This accommodates its MQTT subscription debounce, but is not a broker acknowledgement guarantee; an unusually slow or reconnecting broker can still lose an immediate response. The immediate-tap test above checks this on your own installation. [Home Assistant MQTT client](https://github.com/home-assistant/core/blob/2024.11.0/homeassistant/components/mqtt/client.py#L95-L106)

MQTT commands and button replies are non-retained. Home Assistant's raw MQTT trigger does not expose the retain flag; the fresh card ID prevents a stored reply from an earlier run from answering a new card. This is correlation, not sender authentication. Protect your MQTT broker with credentials and suitable topic permissions. The blueprint continues using the existing action-response topic; the discovered event entity below is available for your own automations without changing existing blueprints.

## Action response entity

After updating WallMode and reconnecting MQTT, open **Settings → Devices & services → MQTT → your WallMode device** and find **Action response**. It represents action-card and notification-banner button presses. Its state is the latest event's timestamp; `event_type` is always `action`, with the selected `card_id`, `action_id`, and a unique `event_id` in its attributes. For a banner, `card_id` is the banner ID. [Home Assistant event entities](https://www.home-assistant.io/integrations/event/)

For a safe first test:

1. Open **Developer tools → States** and select the actual `event.…` entity named **Action response**. Copy its entity ID if you want to use the example below; names vary and can be changed in Home Assistant.
2. Show the test banner in the next section and press **Gezien** on the tablet.
3. Confirm that the entity's timestamp changes and the attributes show `event_type: action`, `card_id: laundry`, and `action_id: ack`.
4. Show and press it again. The timestamp and `event_id` should change even though the button name is the same. Let another banner expire or close it with ×: neither should create a button-response event.

To react to a choice, create an automation under **Settings → Automations & scenes → Create automation → Create new automation**, then use **Edit in YAML**. The compatible state-trigger example below only creates a harmless Home Assistant notification. Replace `event.replace_with_wallmode_action_response` with your copied entity ID. Do not use **Developer tools → Events → Fire event**: this is an entity, not a custom HA event-bus event.

```yaml
alias: WallMode laundry acknowledged
triggers:
  - trigger: state
    entity_id: event.replace_with_wallmode_action_response
conditions:
  - condition: template
    value_template: >-
      {{ trigger.from_state is not none and trigger.to_state is not none
         and trigger.from_state.state not in ['unknown', 'unavailable']
         and trigger.to_state.state not in ['unknown', 'unavailable']
         and trigger.from_state.state != trigger.to_state.state
         and trigger.to_state.attributes.get('event_type') == 'action'
         and trigger.to_state.attributes.get('card_id') == 'laundry'
         and trigger.to_state.attributes.get('action_id') == 'ack'
         and trigger.to_state.attributes.get('event_id')
         and trigger.to_state.attributes.get('event_id')
             != trigger.from_state.attributes.get('event_id') }}
actions:
  - action: persistent_notification.create
    data:
      title: WallMode test
      message: The laundry banner was acknowledged on the tablet.
mode: single
```

The guards reject startup, availability-only transitions, and an immediately repeated response ID. For a previously unknown entity, the first press initializes its state; show the banner again to test this guarded automation. Home Assistant's MQTT event integration also discards replayed retained events. This does not authenticate the person pressing the tablet or guarantee exactly-once delivery: keep consequential actions idempotent and protect broker access. [MQTT event payloads and retained-message handling](https://www.home-assistant.io/integrations/event.mqtt/)

Use a fresh prompt ID when correlating a one-off approval, as the two-button blueprint does. A fixed ID such as `laundry` is suitable for this harmless repeated acknowledgment, not for authorizing a later sensitive operation.

## Notification banner test

Banners leave GlassHome or the current screensaver visible. They do not navigate, brighten the display, or read text aloud. No additional setting or blueprint is required.

1. In Home Assistant, open **Developer tools → Actions** and choose **MQTT: Publish** (`mqtt.publish`).
2. Set **Topic** to `wallmode/<device-suffix>/command/banner`, replacing the suffix with the tablet's existing MQTT suffix. This is the same prefix used for `command/takeover`; do not type angle brackets literally.
3. Set **QoS** to `1`, leave **Retain** off, and paste this into **Payload**:

   ```json
   {"action":"show","id":"laundry","title":"Wasmachine","message":"De was is klaar","level":"success","ttl":10,"sound":true,"button":{"id":"ack","label":"Gezien"}}
   ```

4. Run the action. A success-colored banner should appear for ten seconds at the edge opposite the admin corner. Its optional short sound follows Android's current notification volume. Press **Gezien** to publish the response tested above, or × to close it without a response.
5. Repeat while the screensaver is active: the scene and dim level should remain unchanged. The banner's controls do not wake it; a touch outside the banner follows WallMode's normal wake behavior.

For a silent notice without a button, omit `sound` and `button`. To remove a notice early, publish `{"action":"clear","id":"laundry"}` to the same topic. Only the matching visible ID is cleared. A new notice replaces the existing one; there is no queue. Banners expire after 3–120 seconds, defaulting to 10, and are dropped when the activity/app restarts.

Use `level` to choose `info`, `success`, `warning`, or `error`. Plain single-line text limits are 80 characters for the optional title, 300 for the required message, and 24 for the optional button label. IDs use ASCII letters, digits, `_` or `-`: at most 64 characters for the notice, 32 for its button. If MQTT cannot accept a button response, the notice remains with an error until it expires; a visible banner is not proof that a Home Assistant automation has run.

## Troubleshooting

- **No Announcement or Action response entity:** confirm the updated WallMode app is installed, MQTT is connected and discovery is enabled. Reconnect WallMode or restart the app to republish discovery; do not add manual duplicate entities. Action response can remain `unknown` until its first button press.
- **Automation does nothing:** open its **Traces**. Check the chosen device, trigger, conditions and any validation error. **Run actions** bypasses the automation's triggers and top-level conditions, so use the actual trigger to test quiet hours or presence rules.
- **Announcement is silent:** check the tablet's Android speech engine, voice data and audio output. MQTT availability does not prove that the tablet has a working voice installed.
- **Card never appears:** verify that no higher-priority event is active and that Home Assistant and WallMode use the same broker. Cards are not queued until an offline device returns.
- **Card click has no effect:** Home Assistant must still be running the waiting automation. A reload, restart or disabling the automation cancels that wait; show a fresh card afterwards.
- **Camera page is blank or asks to log in:** test that exact page directly on the tablet. The blueprint does not transfer the login session from your desktop browser or proxy a stream.
- **Banner does not appear:** use `command/banner`, valid JSON, QoS 1 and Retain off. Keep the WallMode main display in the foreground. Banners are not replayed after a restart or held for a device that was offline.

## Maintainer checks

Run `python3 homeassistant/test_blueprints.py` from the repository root with PyYAML and Jinja2 available. It checks these YAML templates and their MQTT payload boundaries; it is not a replacement for importing the blueprints and testing real Home Assistant triggers and tablet responses.
