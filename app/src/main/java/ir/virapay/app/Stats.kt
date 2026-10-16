package ir.virapay.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Transaction history used for the statistics screen (last 1000 entries). */
object Stats {
    private const val PREFS = "ViraPayData"
    private const val KEY = "tx"
    private const val MAX = 1000

    @Synchronized
    fun record(ctx: Context, bot: String, card: String, rial: Long, ok: Boolean) {
        val list = all(ctx).toMutableList()
        list.add(Tx(System.currentTimeMillis(), bot, card, rial, ok))
        val trimmed = if (list.size > MAX) list.takeLast(MAX) else list
        val arr = JSONArray()
        trimmed.forEach {
            arr.put(
                JSONObject().put("t", it.time).put("b", it.bot).put("c", it.card)
                    .put("r", it.rial).put("ok", it.ok)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun all(ctx: Context): List<Tx> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Tx(o.getLong("t"), o.getString("b"), o.getString("c"), o.getLong("r"), o.getBoolean("ok"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
