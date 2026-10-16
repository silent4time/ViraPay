package ir.virapay.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/** About: builder, bug report, message to the builder. No extra permissions are needed. */
class AboutActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        setupTopBar(R.string.about_title)

        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.about_version, versionName())
        findViewById<TextView>(R.id.tvBuilder).text = AppConfig.BUILDER_NAME

        findViewById<MaterialButton>(R.id.btnReport).setOnClickListener { showReportDialog() }
        findViewById<MaterialButton>(R.id.btnMessage).setOnClickListener { showMessageDialog() }
        findViewById<MaterialButton>(R.id.btnGithub).setOnClickListener { open(AppConfig.GITHUB_URL) }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun deviceInfo(): String =
        "ViraPay ${versionName()} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | " +
            "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun showReportDialog() {
        val options = arrayOf(getString(R.string.report_via_apps), getString(R.string.report_via_github))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.report_bug)
            .setItems(options) { _, which ->
                if (which == 0) {
                    val logs = LogBus.snapshot().takeLast(3000)
                    share(getString(R.string.report_bug), deviceInfo() + "\n\n" + logs)
                } else {
                    open(AppConfig.ISSUES_URL)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        showWithFont(dialog)
    }

    private fun showMessageDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_message, null)
        val et = view.findViewById<TextInputEditText>(R.id.etMessage)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.message_builder)
            .setView(view)
            .setPositiveButton(R.string.send, null)
            .setNeutralButton(R.string.open_telegram) { _, _ -> open("https://t.me/" + AppConfig.CONTACT_TELEGRAM) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = et.text?.toString().orEmpty().trim()
                if (text.isEmpty()) {
                    toast(R.string.message_empty)
                } else {
                    share(getString(R.string.message_builder), text + "\n\n— " + deviceInfo())
                    dialog.dismiss()
                }
            }
        }
        showWithFont(dialog)
    }

    private fun share(subject: String, body: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ViraPay - $subject")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(Intent.createChooser(send, subject))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app_found)
        }
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app_found)
        }
    }
}
