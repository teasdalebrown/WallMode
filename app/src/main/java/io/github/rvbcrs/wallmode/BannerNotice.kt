package io.github.rvbcrs.wallmode

import org.json.JSONObject

internal enum class BannerLevel { INFO, SUCCESS, WARNING, ERROR }

internal data class BannerNotice(
    val id: String,
    val title: String,
    val message: String,
    val seconds: Int,
    val level: BannerLevel,
    val sound: Boolean,
    val action: ActionCardAction?
) {
    companion object {
        fun validated(
            id: String,
            title: String,
            message: String,
            seconds: Int = 10,
            level: String = "info",
            sound: Boolean = false,
            action: ActionCardAction? = null
        ): BannerNotice? {
            if (!MqttContract.TAKEOVER_ID.matches(id) || seconds !in 3..120) return null
            val tone = BannerLevel.entries.firstOrNull { it.name.lowercase() == level } ?: return null
            val heading = MqttContract.normalizedText(title, 0, 80) ?: return null
            val body = MqttContract.normalizedText(message, 1, 300) ?: return null
            val button = action?.let {
                if (!MqttContract.ACTION_ID.matches(it.id)) return null
                ActionCardAction(it.id, MqttContract.normalizedText(it.label, 1, 24) ?: return null)
            }
            return BannerNotice(id, heading, body, seconds, tone, sound, button)
        }

        // Called only after the shared MQTT retained-message and payload-size guards.
        fun parseCommand(payload: String): WallModeMqttCommand? = runCatching {
            val json = JSONObject(payload)
            val id = json.opt("id") as? String ?: return null
            if (!MqttContract.TAKEOVER_ID.matches(id)) return null
            when (json.opt("action")) {
                "clear" -> WallModeMqttCommand.ClearBanner(id)
                "show" -> {
                    val title = if (json.has("title")) json.opt("title") as? String ?: return null else ""
                    val message = json.opt("message") as? String ?: return null
                    val seconds = if (json.has("ttl")) {
                        MqttContract.run { json.strictIntOrNull("ttl") } ?: return null
                    } else 10
                    val level = if (json.has("level")) json.opt("level") as? String ?: return null else "info"
                    val sound = if (json.has("sound")) json.opt("sound") as? Boolean ?: return null else false
                    val button = if (json.has("button")) {
                        val value = json.opt("button") as? JSONObject ?: return null
                        ActionCardAction(value.opt("id") as? String ?: return null,
                            value.opt("label") as? String ?: return null)
                    } else null
                    validated(id, title, message, seconds, level, sound, button)?.let(WallModeMqttCommand::ShowBanner)
                }
                else -> null
            }
        }.getOrNull()
    }
}
