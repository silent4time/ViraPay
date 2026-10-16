package ir.virapay.app

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared log buffer: written by the service/Python, shown by the activity when visible. */
object LogBus {
    private val buffer = StringBuilder()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var listener: ((String) -> Unit)? = null

    @Synchronized
    fun append(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$time] $msg"
        buffer.append(line).append('\n')
        if (buffer.length > 20000) buffer.delete(0, buffer.length - 15000)
        val l = listener
        if (l != null) main.post { l(line) }
    }

    @Synchronized
    fun snapshot(): String = buffer.toString()

    @Synchronized
    fun clear() {
        buffer.setLength(0)
    }
}
