package ir.virapay.app

import android.content.Context
import android.os.Bundle
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlin.concurrent.thread

/** One-time Telegram login (API ID / hash / phone / code). */
class TelegramActivity : BaseActivity() {

    private val prefs by lazy { getSharedPreferences("ViraPayPrefs", Context.MODE_PRIVATE) }

    private lateinit var etApiId: TextInputEditText
    private lateinit var etApiHash: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var etOtp: TextInputEditText
    private lateinit var btnSendCode: MaterialButton
    private lateinit var btnVerifyCode: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telegram)
        setupTopBar(R.string.section_telegram)

        etApiId = findViewById(R.id.etApiId)
        etApiHash = findViewById(R.id.etApiHash)
        etPhone = findViewById(R.id.etPhone)
        etOtp = findViewById(R.id.etOtp)
        btnSendCode = findViewById(R.id.btnSendCode)
        btnVerifyCode = findViewById(R.id.btnVerifyCode)

        etApiId.setText(prefs.getString("api_id", ""))
        etApiHash.setText(prefs.getString("api_hash", ""))
        etPhone.setText(prefs.getString("phone", ""))

        btnSendCode.setOnClickListener { sendCode() }
        btnVerifyCode.setOnClickListener { verifyCode() }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun TextInputEditText.value(): String = text?.toString()?.trim().orEmpty()

    private fun save() {
        prefs.edit()
            .putString("api_id", Digits.normalize(etApiId.value()))
            .putString("api_hash", etApiHash.value())
            .putString("phone", Digits.normalize(etPhone.value()))
            .apply()
    }

    private fun inputs(): Triple<Int, String, String>? {
        val id = Digits.normalize(etApiId.value()).toIntOrNull()
        val hash = etApiHash.value()
        val phone = Digits.normalize(etPhone.value())
        if (id == null || hash.isEmpty() || phone.isEmpty()) {
            toast(R.string.fill_telegram)
            return null
        }
        return Triple(id, hash, phone)
    }

    private fun sendCode() {
        val (id, hash, phone) = inputs() ?: return
        save()
        val dir = filesDir.absolutePath
        runPython(btnSendCode) { it.callAttr("send_login_code", dir, id, hash, phone).toString() }
    }

    private fun verifyCode() {
        val (id, hash, phone) = inputs() ?: return
        val code = Digits.normalize(etOtp.value())
        if (code.isEmpty()) {
            toast(R.string.fill_otp)
            return
        }
        save()
        val dir = filesDir.absolutePath
        runPython(btnVerifyCode) {
            it.callAttr("verify_login_code", dir, id, hash, phone, code).toString()
        }
    }

    /** Runs a call into virapay_engine.py off the main thread; the result goes to the log + a toast. */
    private fun runPython(button: MaterialButton, call: (PyObject) -> String) {
        button.isEnabled = false
        thread {
            val result = try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                call(Python.getInstance().getModule("virapay_engine"))
            } catch (e: Exception) {
                "❌ ${e.message}"
            }
            LogBus.append(result)
            runOnUiThread {
                button.isEnabled = true
                toast(result)
            }
        }
    }
}
