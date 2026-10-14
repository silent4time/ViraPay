package ir.virapay.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var etApiId: EditText
    private lateinit var etApiHash: EditText
    private lateinit var etPhone: EditText
    private lateinit var etCardAndBank: EditText
    private lateinit var etBotUsername: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvLogs: TextView

    private var isGatewayRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiId = findViewById(R.id.etApiId)
        etApiHash = findViewById(R.id.etApiHash)
        etPhone = findViewById(R.id.etPhone)
        etCardAndBank = findViewById(R.id.etCardAndBank)
        etBotUsername = findViewById(R.id.etBotUsername)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvLogs = findViewById(R.id.tvLogs)

        btnStart.setOnClickListener {
            startGateway()
        }

        btnStop.setOnClickListener {
            stopGateway()
        }
    }

    private fun startGateway() {
        val botUser = etBotUsername.text.toString().trim()
        val cardInfo = etCardAndBank.text.toString().trim()

        if (botUser.isEmpty() || cardInfo.isEmpty()) {
            appendLog("❌ لطفاً اطلاعات ربات و شماره کارت را کامل وارد کنید.")
            return
        }

        isGatewayRunning = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        
        appendLog("✅ درگاه هوشمند ویراپی روشن شد.")
        appendLog("👀 در حال رصد پیامک‌های بانک رسالت و ربات $botUser...")
        
        // اجرای سرویس پس‌زمینه برای بررسی پیامک و عکس فیش
    }

    private fun stopGateway() {
        isGatewayRunning = false
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        appendLog("⏹ درگاه خودکار متوقف شد.")
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLogs.append("[$time] $message\n")
        }
    }
}
