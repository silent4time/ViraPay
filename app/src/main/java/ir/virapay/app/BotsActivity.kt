package ir.virapay.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Add / edit / delete Telegram store bots. */
class BotsActivity : BaseActivity() {

    private lateinit var llList: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bots)
        setupTopBar(R.string.bots_title)
        llList = findViewById(R.id.llList)
        tvEmpty = findViewById(R.id.tvEmpty)
        findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener {
            if (Store.bots(this).size >= LicenseManager.maxBots(this)) {
                toast(R.string.limit_bots)
            } else {
                showBotDialog(null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val bots = Store.bots(this)
        tvEmpty.visibility = if (bots.isEmpty()) View.VISIBLE else View.GONE
        llList.removeAllViews()
        for (bot in bots) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_bot, llList, false)
            v.findViewById<TextView>(R.id.tvName).text = "@" + bot.username
            v.findViewById<TextView>(R.id.tvLine1).text = getString(R.string.bot_line_id, bot.botId)
            v.findViewById<TextView>(R.id.tvLine2).text = getString(R.string.bot_line_admin, bot.admin)
            v.findViewById<TextView>(R.id.tvLine3).text =
                getString(R.string.bot_line_cards, Store.cardsOf(this, bot.id).size)
            v.findViewById<View>(R.id.btnEdit).setOnClickListener { showBotDialog(bot) }
            v.findViewById<View>(R.id.btnDelete).setOnClickListener { confirmDelete(bot) }
            llList.addView(v)
        }
        Fonts.apply(llList)
    }

    private fun confirmDelete(bot: Bot) {
        val n = Store.cardsOf(this, bot.id).size
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_bot_title)
            .setMessage(getString(R.string.delete_bot_message, bot.username, n))
            .setPositiveButton(R.string.delete) { _, _ ->
                Store.deleteBot(this, bot.id)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        showWithFont(dialog)
    }

    private fun showBotDialog(existing: Bot?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_bot, null)
        val tilId = view.findViewById<TextInputLayout>(R.id.tilBotId)
        val tilUser = view.findViewById<TextInputLayout>(R.id.tilBotUsername)
        val tilAdmin = view.findViewById<TextInputLayout>(R.id.tilBotAdmin)
        val etId = view.findViewById<TextInputEditText>(R.id.etBotId)
        val etUser = view.findViewById<TextInputEditText>(R.id.etBotUsername)
        val etAdmin = view.findViewById<TextInputEditText>(R.id.etBotAdmin)
        if (existing != null) {
            etId.setText(existing.botId)
            etUser.setText(existing.username)
            etAdmin.setText(existing.admin)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_bot else R.string.edit_bot)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                tilId.error = null
                tilUser.error = null
                tilAdmin.error = null

                val botId = Digits.normalize(etId.text?.toString().orEmpty().trim())
                val user = etUser.text?.toString().orEmpty().trim().removePrefix("@")
                val admin = etAdmin.text?.toString().orEmpty().trim().removePrefix("@")

                var ok = true
                if (botId.isEmpty() || !botId.all { it in '0'..'9' }) {
                    tilId.error = getString(R.string.err_bot_id); ok = false
                }
                if (!USERNAME.matches(user) || !user.endsWith("bot", ignoreCase = true)) {
                    tilUser.error = getString(R.string.err_bot_username); ok = false
                } else if (Store.bots(this).any {
                        it.id != existing?.id && it.username.equals(user, ignoreCase = true)
                    }
                ) {
                    tilUser.error = getString(R.string.err_bot_duplicate); ok = false
                }
                if (!USERNAME.matches(admin)) {
                    tilAdmin.error = getString(R.string.err_admin_username); ok = false
                }
                if (!ok) return@setOnClickListener

                Store.upsertBot(this, Bot(existing?.id ?: Bot.newId(), botId, user, admin))
                dialog.dismiss()
                render()
            }
        }
        showWithFont(dialog)
    }

    private companion object {
        /** Telegram usernames: 5-32 chars, letters / digits / underscore, start with a letter. */
        val USERNAME = Regex("^[A-Za-z][A-Za-z0-9_]{4,31}$")
    }
}
