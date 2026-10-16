package ir.virapay.app

import android.os.Bundle
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlin.concurrent.thread

/**
 * Activation screen. Built and ready, but NOT reachable while AppConfig.LICENSE_ENABLED = false
 * (the Settings entry is hidden). See License.kt for the algorithm.
 */
class LicenseActivity : BaseActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnActivate: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_license)
        setupTopBar(R.string.section_license)

        tvStatus = findViewById(R.id.tvLicStatus)
        btnActivate = findViewById(R.id.btnActivate)
        findViewById<TextView>(R.id.tvDevice).text =
            getString(R.string.license_device, LicenseManager.deviceHash(this).take(16))
        val etKey = findViewById<TextInputEditText>(R.id.etKey)

        refresh()
        btnActivate.setOnClickListener {
            val key = etKey.text?.toString().orEmpty().trim()
            if (key.isEmpty()) {
                toast(R.string.license_enter_key)
                return@setOnClickListener
            }
            btnActivate.isEnabled = false
            thread {
                val result = LicenseManager.activate(applicationContext, key)
                runOnUiThread {
                    btnActivate.isEnabled = true
                    when (result) {
                        is LicenseManager.Result.Ok -> toast(R.string.license_ok)
                        is LicenseManager.Result.Error -> toast(errorText(result.code))
                    }
                    refresh()
                }
            }
        }
    }

    private fun refresh() {
        tvStatus.setText(if (LicenseManager.isLicensed(this)) R.string.license_active else R.string.license_free)
    }

    private fun errorText(code: String): Int = when (code) {
        "invalid_key" -> R.string.license_err_invalid
        "already_used" -> R.string.license_err_used
        "revoked" -> R.string.license_err_revoked
        "network" -> R.string.license_err_network
        else -> R.string.license_err_generic
    }
}
