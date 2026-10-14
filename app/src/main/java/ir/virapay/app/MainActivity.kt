package ir.virapay.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvLogs: TextView
    private lateinit var pyModule: PyObject
    private var failedAttempts = 0
    private var lockedUntil = 0L

    private val logListener: (String) -> Unit = { msg -> tvLogs.append("$msg\n") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        pyModule = Python.getInstance().getModule("virapay_engine")

        val layoutLogin = findViewById<LinearLayout>(R.id.layoutLogin)
        val layoutMain = findViewById<LinearLayout>(R.id.layoutMain)
        val etPasscode = findViewById<EditText>(R.id.etPasscode)
        val etPasscodeConfirm = findViewById<EditText>(R.id.etPasscodeConfirm)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        val etApiId = findViewById<EditText>(R.id.etApiId)
        val etApiHash = findViewById<EditText>(R.id.etApiHash)
        val etPhone = findViewById<EditText>(R.id.etPhone)
        val etBankCards = findViewById<EditText>(R.id.etBankCards)
        val etBotUsernames = findViewById<EditText>(R.id.etBotUsernames)
        val etOtpCode = findViewById<EditText>(R.id.etOtpCode)
        val etNewPin = findViewById<EditText>(R.id.etNewPin)

        val btnSendCode = findViewById<Button>(R.id.btnSendCode)
        val btnVerifyCode = findViewById<Button>(R.id.btnVerifyCode)
        val btnStartService = findViewById<Button>(R.id.btnStartService)
        val btnStopService = findViewById<Button>(R.id.btnStopService)
        val btnChangePin = findViewById<Button>(R.id.btnChangePin)
        tvLogs = findViewById(R.id.tvLogs)
        tvLogs.movementMethod = ScrollingMovementMethod()

        val prefs = getSharedPreferences("ViraPayPrefs", Context.MODE_PRIVATE)

        // ---- PIN: first run forces creating a PIN (no default PIN) ----
        if (!PinStore.isSet(prefs)) {
            etPasscodeConfirm.visibility = View.VISIBLE
            btnLogin.setText(R.string.set_pin)
        }

        fun enterApp() {
            etPasscode.setText("")
            etPasscodeConfirm.setText("")
            layoutLogin.visibility = View.GONE
            layoutMain.visibility = View.VISIBLE
            LogBus.append("✅ Access granted. / ورود موفقیت‌آمیز.")
        }

        btnLogin.setOnClickListener {
            val pin = etPasscode.text.toString().trim()
            if (!PinStore.isSet(prefs)) {
                when {
                    pin.length < 4 -> toast(R.string.pin_short)
                    pin != etPasscodeConfirm.text.toString().trim() -> toast(R.string.pin_mismatch)
                    else -> {
                        PinStore.set(prefs, pin)
                        enterApp()
                    }
                }
                return@setOnClickListener
            }
            val now = SystemClock.elapsedRealtime()
            if (now < lockedUntil) {
                toast(R.string.pin_locked)
                return@setOnClickListener
            }
            if (PinStore.verify(prefs, pin)) {
                failedAttempts = 0
                enterApp()
            } else {
                failedAttempts++
                if (failedAttempts >= 5) {
                    lockedUntil = now + 30_000
                    failedAttempts = 0
                }
                toast(R.string.pin_wrong)
            }
        }

        btnChangePin.setOnClickListener {
            val newPin = etNewPin.text.toString().trim()
            if (newPin.length < 4) {
                toast(R.string.pin_short)
            } else {
                PinStore.set(prefs, newPin)
                etNewPin.setText("")
                toast(R.string.pin_changed)
            }
        }

        etApiId.setText(prefs.getString("api_id", ""))
        etApiHash.setText(prefs.getString("api_hash", ""))
        etPhone.setText(prefs.getString("phone", ""))
        etBankCards.setText(prefs.getString("bank_cards", "0000000000000000:ResalatBank"))
        etBotUsernames.setText(prefs.getString("bot_usernames", "silentping_vpn_bot"))

        ensurePermissions()

        fun savePrefs() {
            prefs.edit()
                .putString("api_id", etApiId.text.toString().trim())
                .putString("api_hash", etApiHash.text.toString().trim())
                .putString("phone", etPhone.text.toString().trim())
                .putString("bank_cards", etBankCards.text.toString().trim())
                .putString("bot_usernames", etBotUsernames.text.toString().trim())
                .apply()
        }

        fun apiIdOrNull(): Int? = etApiId.text.toString().trim().toIntOrNull()

        btnSendCode.setOnClickListener {
            savePrefs()
            val apiId = apiIdOrNull()
            val apiHash = etApiHash.text.toString().trim()
            val phone = etPhone.text.toString().trim()
            if (apiId == null || apiHash.isEmpty() || phone.isEmpty()) {
                LogBus.append("❌ Please fill Telegram credentials (API ID must be a number). / لطفاً اطلاعات تلگرام را کامل وارد کنید.")
                return@setOnClickListener
            }
            LogBus.append("⏳ Sending Telegram login code... / در حال ارسال کد تلگرام...")
            thread {
                val res = pyModule.callAttr("send_login_code", filesDir.absolutePath, apiId, apiHash, phone).toString()
                LogBus.append(res)
            }
        }

        btnVerifyCode.setOnClickListener {
            savePrefs()
            val apiId = apiIdOrNull()
            val codeInput = etOtpCode.text.toString().trim()
            if (apiId == null || codeInput.isEmpty()) {
                LogBus.append("❌ Please enter API ID and OTP code. / لطفاً API ID و کد ورود را وارد کنید.")
                return@setOnClickListener
            }
            LogBus.append("⏳ Verifying login code... / در حال بررسی کد ورود...")
            thread {
                val res = pyModule.callAttr(
                    "verify_login_code",
                    filesDir.absolutePath,
                    apiId,
                    etApiHash.text.toString().trim(),
                    etPhone.text.toString().trim(),
                    codeInput
                ).toString()
                LogBus.append(res)
            }
        }

        btnStartService.setOnClickListener {
            savePrefs()
            if (apiIdOrNull() == null || etApiHash.text.toString().trim().isEmpty()) {
                LogBus.append("❌ API ID / API Hash invalid. / API ID یا API Hash نامعتبر است.")
                return@setOnClickListener
            }
            if (!ensurePermissions()) {
                LogBus.append("❌ SMS permission is missing! / مجوز خواندن پیامک داده نشده است!")
                return@setOnClickListener
            }
            if (GatewayService.running) {
                LogBus.append("ℹ️ Gateway already running. / درگاه از قبل فعال است.")
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(this, Intent(this, GatewayService::class.java))
            LogBus.append("🚀 ViraPay Gateway Service Started! / سرویس درگاه ویراپِی فعال شد!")
        }

        btnStopService.setOnClickListener {
            stopService(Intent(this, GatewayService::class.java))
            LogBus.append("🛑 Stop requested. / درخواست توقف ارسال شد.")
        }
    }

    override fun onResume() {
        super.onResume()
        tvLogs.text = LogBus.snapshot()
        LogBus.listener = logListener
    }

    override fun onPause() {
        LogBus.listener = null
        super.onPause()
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Requests missing permissions; returns true only if READ_SMS is granted. */
    private fun ensurePermissions(): Boolean {
        val needed = mutableListOf<String>()
        for (p in listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) {
            if (!granted(p)) needed.add(p)
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
        return granted(Manifest.permission.READ_SMS)
    }
}
