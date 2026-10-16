package ir.virapay.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : BaseActivity() {

    private class Item(
        val icon: String,
        val title: Int,
        val subtitle: () -> String,
        val onClick: () -> Unit
    )

    private lateinit var llItems: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setupTopBar(R.string.settings)
        llItems = findViewById(R.id.llItems)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val items = mutableListOf(
            Item("🔐", R.string.section_telegram, { getString(R.string.settings_telegram_sub) }) {
                startActivity(Intent(this, TelegramActivity::class.java))
            },
            Item("🤖", R.string.bots_title, { getString(R.string.settings_count_bots, Store.bots(this).size) }) {
                startActivity(Intent(this, BotsActivity::class.java))
            },
            Item("💳", R.string.cards_title, { getString(R.string.settings_count_cards, Store.cards(this).size) }) {
                startActivity(Intent(this, CardsActivity::class.java))
            },
            Item("🔑", R.string.change_pin, { getString(R.string.settings_pin_sub) }) { showChangePinDialog() }
        )
        if (LicenseManager.enabled()) {
            items.add(
                Item("🛡️", R.string.section_license, {
                    getString(if (LicenseManager.isLicensed(this)) R.string.license_active else R.string.license_free)
                }) { startActivity(Intent(this, LicenseActivity::class.java)) }
            )
        }
        items.add(
            Item("ℹ️", R.string.about_title, { getString(R.string.settings_about_sub) }) {
                startActivity(Intent(this, AboutActivity::class.java))
            }
        )

        llItems.removeAllViews()
        for (item in items) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_setting, llItems, false)
            v.findViewById<TextView>(R.id.tvItemIcon).text = item.icon
            v.findViewById<TextView>(R.id.tvItemTitle).setText(item.title)
            v.findViewById<TextView>(R.id.tvItemSub).text = item.subtitle()
            v.setOnClickListener { item.onClick() }
            llItems.addView(v)
        }
        Fonts.apply(llItems)
    }

    /** PIN: 4 to 6 digits; the current PIN is required to change it. */
    private fun showChangePinDialog() {
        val prefs = getSharedPreferences("ViraPayPrefs", Context.MODE_PRIVATE)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_pin_change, null)
        val etOld = view.findViewById<TextInputEditText>(R.id.etOldPin)
        val etNew = view.findViewById<TextInputEditText>(R.id.etNewPin)
        val etConfirm = view.findViewById<TextInputEditText>(R.id.etConfirmPin)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_pin)
            .setView(view)
            .setPositiveButton(R.string.set_pin, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val old = etOld.text?.toString().orEmpty()
                val new = etNew.text?.toString().orEmpty()
                when {
                    !PinStore.verify(prefs, old) -> toast(R.string.pin_wrong)
                    !PinStore.isValid(new) -> toast(R.string.pin_short)
                    new != etConfirm.text?.toString().orEmpty() -> toast(R.string.pin_mismatch)
                    else -> {
                        PinStore.set(prefs, new)
                        toast(R.string.pin_changed)
                        dialog.dismiss()
                    }
                }
            }
        }
        showWithFont(dialog)
    }
}
