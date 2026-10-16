package ir.virapay.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Add / edit / delete bank cards.
 * Rule: every card is assigned to exactly ONE bot, and the same card number cannot be added twice,
 * so a card can never serve two bots. A bot may own many cards.
 */
class CardsActivity : BaseActivity() {

    private lateinit var llList: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cards)
        setupTopBar(R.string.cards_title)
        llList = findViewById(R.id.llList)
        tvEmpty = findViewById(R.id.tvEmpty)
        findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener {
            when {
                Store.bots(this).isEmpty() -> toast(R.string.need_bot_first)
                Store.cards(this).size >= LicenseManager.maxCards(this) -> toast(R.string.limit_cards)
                else -> showCardDialog(null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val cards = Store.cards(this)
        val bots = Store.bots(this)
        tvEmpty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        llList.removeAllViews()
        for (card in cards) {
            val botName = bots.firstOrNull { it.id == card.botRef }?.username ?: "?"
            val v = LayoutInflater.from(this).inflate(R.layout.item_card, llList, false)
            v.findViewById<TextView>(R.id.tvName).text = card.masked()
            v.findViewById<TextView>(R.id.tvLine1).text = getString(R.string.card_line_holder, card.holder)
            v.findViewById<TextView>(R.id.tvLine2).text = getString(R.string.card_line_sender, card.sender)
            v.findViewById<TextView>(R.id.tvLine3).text = getString(R.string.card_line_bot, botName)
            v.findViewById<View>(R.id.btnEdit).setOnClickListener { showCardDialog(card) }
            v.findViewById<View>(R.id.btnDelete).setOnClickListener { confirmDelete(card) }
            llList.addView(v)
        }
        Fonts.apply(llList)
    }

    private fun confirmDelete(card: Card) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_card_title)
            .setMessage(getString(R.string.delete_card_message, card.number.takeLast(4)))
            .setPositiveButton(R.string.delete) { _, _ ->
                Store.deleteCard(this, card.id)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        showWithFont(dialog)
    }

    private fun showCardDialog(existing: Card?) {
        val bots = Store.bots(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_card, null)
        val tilNumber = view.findViewById<TextInputLayout>(R.id.tilCardNumber)
        val tilHolder = view.findViewById<TextInputLayout>(R.id.tilHolder)
        val tilSender = view.findViewById<TextInputLayout>(R.id.tilSender)
        val tilSample = view.findViewById<TextInputLayout>(R.id.tilSample)
        val etNumber = view.findViewById<TextInputEditText>(R.id.etCardNumber)
        val etHolder = view.findViewById<TextInputEditText>(R.id.etHolder)
        val etSender = view.findViewById<TextInputEditText>(R.id.etSender)
        val etSample = view.findViewById<TextInputEditText>(R.id.etSample)
        val spBot = view.findViewById<Spinner>(R.id.spBot)

        val names = bots.map { "@" + it.username }
        spBot.adapter = fontAdapter(names)
        if (existing != null) {
            etNumber.setText(existing.number)
            etHolder.setText(existing.holder)
            etSender.setText(existing.sender)
            etSample.setText(existing.sampleSms)
            val idx = bots.indexOfFirst { it.id == existing.botRef }
            if (idx >= 0) spBot.setSelection(idx)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_card else R.string.edit_card)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                listOf(tilNumber, tilHolder, tilSender, tilSample).forEach { it.error = null }

                val number = Digits.normalize(etNumber.text?.toString().orEmpty()).filter { it in '0'..'9' }
                val holder = etHolder.text?.toString().orEmpty().trim()
                val sender = etSender.text?.toString().orEmpty().trim()
                val sample = etSample.text?.toString().orEmpty().trim()

                var ok = true
                if (number.length != 16) {
                    tilNumber.error = getString(R.string.err_card_number); ok = false
                } else if (Store.cards(this).any { it.id != existing?.id && it.number == number }) {
                    // the same card can never be attached to a second bot
                    tilNumber.error = getString(R.string.err_card_duplicate); ok = false
                }
                if (holder.isEmpty()) {
                    tilHolder.error = getString(R.string.err_card_holder); ok = false
                }
                if (sender.isEmpty()) {
                    tilSender.error = getString(R.string.err_card_sender); ok = false
                }
                if (!Regex("\\d[\\d,٬]{3,}").containsMatchIn(Digits.normalize(sample))) {
                    tilSample.error = getString(R.string.err_card_sample); ok = false
                }
                val bot = bots.getOrNull(spBot.selectedItemPosition)
                if (bot == null) {
                    toast(R.string.need_bot_first); ok = false
                }
                if (!ok || bot == null) return@setOnClickListener

                Store.upsertCard(this, Card(existing?.id ?: Card.newId(), number, holder, sample, sender, bot.id))
                dialog.dismiss()
                render()
            }
        }
        showWithFont(dialog)
    }

    private fun fontAdapter(items: List<String>): ArrayAdapter<String> {
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                Fonts.apply(v)
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                Fonts.apply(v)
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }
}
