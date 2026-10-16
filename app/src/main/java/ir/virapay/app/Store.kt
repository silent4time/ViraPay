package ir.virapay.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bots and cards live here as JSON inside private SharedPreferences. */
object Store {
    private const val PREFS = "ViraPayData"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun <T> read(ctx: Context, key: String, parse: (JSONObject) -> T): List<T> {
        val raw = prefs(ctx).getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun bots(ctx: Context): List<Bot> = read(ctx, "bots") { Bot.fromJson(it) }

    fun cards(ctx: Context): List<Card> = read(ctx, "cards") { Card.fromJson(it) }

    fun cardsOf(ctx: Context, botId: String): List<Card> = cards(ctx).filter { it.botRef == botId }

    private fun saveBots(ctx: Context, list: List<Bot>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString("bots", arr.toString()).apply()
    }

    private fun saveCards(ctx: Context, list: List<Card>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString("cards", arr.toString()).apply()
    }

    fun upsertBot(ctx: Context, bot: Bot) {
        val list = bots(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == bot.id }
        if (i >= 0) list[i] = bot else list.add(bot)
        saveBots(ctx, list)
    }

    /** Deleting a bot also deletes its cards (a card cannot exist without a bot). */
    fun deleteBot(ctx: Context, botId: String) {
        saveBots(ctx, bots(ctx).filter { it.id != botId })
        saveCards(ctx, cards(ctx).filter { it.botRef != botId })
    }

    fun upsertCard(ctx: Context, card: Card) {
        val list = cards(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == card.id }
        if (i >= 0) list[i] = card else list.add(card)
        saveCards(ctx, list)
    }

    fun deleteCard(ctx: Context, cardId: String) {
        saveCards(ctx, cards(ctx).filter { it.id != cardId })
    }

    /**
     * JSON handed to the Python engine. Respects the license limits
     * (pass Int.MAX_VALUE for "unlimited").
     */
    fun configJson(ctx: Context, maxBots: Int, maxCards: Int): String {
        var cardsLeft = maxCards
        val allCards = cards(ctx)
        val botsArr = JSONArray()
        for (bot in bots(ctx).take(maxBots)) {
            val mine = allCards.filter { it.botRef == bot.id }.take(cardsLeft.coerceAtLeast(0))
            if (mine.isEmpty()) continue
            cardsLeft -= mine.size
            val cardsArr = JSONArray()
            mine.forEach {
                cardsArr.put(
                    JSONObject().put("number", it.number).put("holder", it.holder)
                        .put("sample", it.sampleSms).put("sender", it.sender)
                )
            }
            botsArr.put(
                JSONObject().put("id", bot.id).put("bot_id", bot.botId)
                    .put("username", bot.username).put("admin", bot.admin).put("cards", cardsArr)
            )
        }
        return JSONObject().put("bots", botsArr).toString()
    }
}
