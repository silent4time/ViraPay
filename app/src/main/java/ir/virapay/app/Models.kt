package ir.virapay.app

import org.json.JSONObject
import java.util.UUID

/** A Telegram store bot whose payment confirmations are handled. */
data class Bot(
    val id: String,        // internal id
    val botId: String,     // numeric Telegram id of the bot
    val username: String,  // bot username, without @
    val admin: String      // admin username, without @
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("bot_id", botId).put("username", username).put("admin", admin)

    companion object {
        fun fromJson(o: JSONObject) =
            Bot(o.getString("id"), o.optString("bot_id"), o.getString("username"), o.optString("admin"))

        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** A bank card. A card belongs to exactly ONE bot (botRef); a bot can own many cards. */
data class Card(
    val id: String,
    val number: String,     // 16 digits
    val holder: String,
    val sampleSms: String,  // a real deposit SMS, used to learn the SMS format
    val sender: String,     // SMS sender (short code / number / name)
    val botRef: String      // Bot.id this card is assigned to
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("number", number).put("holder", holder)
        .put("sample", sampleSms).put("sender", sender).put("bot", botRef)

    fun masked(): String = if (number.length >= 4) "•••• •••• •••• " + number.takeLast(4) else number

    companion object {
        fun fromJson(o: JSONObject) = Card(
            o.getString("id"), o.getString("number"), o.optString("holder"),
            o.optString("sample"), o.optString("sender"), o.getString("bot")
        )

        fun newId(): String = UUID.randomUUID().toString()
    }
}

data class Tx(val time: Long, val bot: String, val card: String, val rial: Long, val ok: Boolean)

object Digits {
    /** Persian / Arabic-Indic digits -> ASCII digits. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                in '\u06F0'..'\u06F9' -> sb.append('0' + (c - '\u06F0'))
                in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660'))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
