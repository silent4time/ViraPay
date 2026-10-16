package ir.virapay.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat
import java.util.Locale

/** Dashboard: status, start/stop, connection state, statistics and live log. */
class MainActivity : BaseActivity() {

    override val requiresUnlock: Boolean = false

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: View
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnClearLogs: MaterialButton
    private lateinit var btnSettings: View
    private lateinit var tvLogs: TextView
    private lateinit var svLogs: NestedScrollView
    private lateinit var tvStatus: TextView
    private lateinit var statusDot: View
    private lateinit var tvConnTelegram: TextView
    private lateinit var tvConnSms: TextView
    private lateinit var tvConnBots: TextView
    private lateinit var tvConnCards: TextView
    private lateinit var rowLicense: View
    private lateinit var tvConnLicense: TextView
    private lateinit var tvStatTotal: TextView
    private lateinit var tvStatOk: TextView
    private lateinit var tvStatFail: TextView
    private lateinit var tvStatsEmpty: TextView
    private lateinit var llStats: LinearLayout

    private var statsSignature = ""

    private val ticker = object : Runnable {
        override fun run() {
            renderStatus()
            renderConnection()
            renderStats()
            ui.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasSmsPermission()) launchService() else toast(R.string.sms_permission_needed)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
        enforcePinGate()
    }

    override fun onResume() {
        super.onResume()
        tvLogs.text = LogBus.snapshot()
        scrollLogsToEnd()
        LogBus.listener = { line ->
            tvLogs.append(line + "\n")
            scrollLogsToEnd()
        }
        statsSignature = ""
        ui.post(ticker)
    }

    override fun onPause() {
        LogBus.listener = null
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnSettings = findViewById(R.id.btnSettings)
        tvLogs = findViewById(R.id.tvLogs)
        svLogs = findViewById(R.id.svLogs)
        tvStatus = findViewById(R.id.tvStatus)
        statusDot = findViewById(R.id.statusDot)
        tvConnTelegram = findViewById(R.id.tvConnTelegram)
        tvConnSms = findViewById(R.id.tvConnSms)
        tvConnBots = findViewById(R.id.tvConnBots)
        tvConnCards = findViewById(R.id.tvConnCards)
        rowLicense = findViewById(R.id.rowLicense)
        tvConnLicense = findViewById(R.id.tvConnLicense)
        tvStatTotal = findViewById(R.id.tvStatTotal)
        tvStatOk = findViewById(R.id.tvStatOk)
        tvStatFail = findViewById(R.id.tvStatFail)
        tvStatsEmpty = findViewById(R.id.tvStatsEmpty)
        llStats = findViewById(R.id.llStats)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        btnStart.setOnClickListener { startGateway() }
        btnStop.setOnClickListener { stopGateway() }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnClearLogs.setOnClickListener {
            LogBus.clear()
            tvLogs.text = ""
        }
        // Let the log box scroll inside the page scroll view.
        svLogs.setOnTouchListener { v, event ->
            val done = event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL
            v.parent.requestDisallowInterceptTouchEvent(!done)
            false
        }
    }

    // ------------------------------------------------------------- PIN gate

    private fun enforcePinGate() {
        val prefs = getSharedPreferences("ViraPayPrefs", Context.MODE_PRIVATE)
        if (Session.unlocked) {
            root.visibility = View.VISIBLE
            return
        }
        root.visibility = View.INVISIBLE
        if (PinStore.isSet(prefs)) showUnlockDialog(prefs) else showSetPinDialog(prefs)
    }

    private fun showUnlockDialog(prefs: android.content.SharedPreferences) {
        val input = pinField(getString(R.string.pin_hint))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pin_title_unlock)
            .setView(column(input))
            .setCancelable(false)
            .setPositiveButton(R.string.unlock, null)
            .setNegativeButton(R.string.exit) { _, _ -> finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val now = System.currentTimeMillis()
                if (now < Session.lockedUntil) {
                    toast(R.string.pin_locked)
                    return@setOnClickListener
                }
                if (PinStore.verify(prefs, input.text.toString())) {
                    Session.unlocked = true
                    Session.failedAttempts = 0
                    root.visibility = View.VISIBLE
                    dialog.dismiss()
                } else {
                    input.text?.clear()
                    Session.failedAttempts++
                    if (Session.failedAttempts >= 5) {
                        Session.failedAttempts = 0
                        Session.lockedUntil = now + 30_000
                        toast(R.string.pin_locked)
                    } else {
                        toast(R.string.pin_wrong)
                    }
                }
            }
        }
        showWithFont(dialog)
    }

    private fun showSetPinDialog(prefs: android.content.SharedPreferences) {
        val first = pinField(getString(R.string.new_pin_hint))
        val second = pinField(getString(R.string.confirm_pin_hint))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pin_title_set)
            .setView(column(first, second))
            .setCancelable(false)
            .setPositiveButton(R.string.set_pin, null)
            .setNegativeButton(R.string.exit) { _, _ -> finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = first.text.toString()
                when {
                    !PinStore.isValid(a) -> toast(R.string.pin_short)
                    a != second.text.toString() -> toast(R.string.pin_mismatch)
                    else -> {
                        PinStore.set(prefs, a)
                        Session.unlocked = true
                        root.visibility = View.VISIBLE
                        dialog.dismiss()
                    }
                }
            }
        }
        showWithFont(dialog)
    }

    private fun pinField(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        textDirection = View.TEXT_DIRECTION_LTR
        filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(6))
    }

    private fun column(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        views.forEach {
            addView(
                it,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    // --------------------------------------------------------------- service

    private fun startGateway() {
        val prefs = getSharedPreferences("ViraPayPrefs", Context.MODE_PRIVATE)
        val hasTelegram = prefs.getString("api_id", "").orEmpty().isNotBlank() &&
            prefs.getString("api_hash", "").orEmpty().isNotBlank()
        if (!hasTelegram) {
            toast(R.string.need_telegram)
            startActivity(Intent(this, TelegramActivity::class.java))
            return
        }
        val bots = Store.bots(this)
        val cards = Store.cards(this)
        if (bots.isEmpty()) {
            toast(R.string.need_bot)
            startActivity(Intent(this, BotsActivity::class.java))
            return
        }
        if (cards.isEmpty()) {
            toast(R.string.need_card)
            startActivity(Intent(this, CardsActivity::class.java))
            return
        }
        if (GatewayService.running) {
            toast(R.string.already_running)
            return
        }
        if (bots.size > LicenseManager.maxBots(this) || cards.size > LicenseManager.maxCards(this)) {
            LogBus.append(getString(R.string.log_license_limited))
        }

        val missing = mutableListOf<String>()
        if (!hasSmsPermission()) missing.add(Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) launchService() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun launchService() {
        LogBus.append(getString(R.string.log_starting))
        ContextCompat.startForegroundService(this, Intent(this, GatewayService::class.java))
        ui.postDelayed({ renderStatus() }, 500)
    }

    private fun stopGateway() {
        LogBus.append(getString(R.string.log_stopping))
        stopService(Intent(this, GatewayService::class.java))
        ui.postDelayed({ renderStatus() }, 500)
    }

    private fun hasSmsPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    // -------------------------------------------------------------------- UI

    private fun renderStatus() {
        val running = GatewayService.running
        tvStatus.setText(if (running) R.string.status_running else R.string.status_stopped)
        val dot = ContextCompat.getColor(this, if (running) R.color.vp_success else R.color.vp_idle)
        statusDot.backgroundTintList = ColorStateList.valueOf(dot)
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    private fun renderConnection() {
        val (tgText, tgColor) = when (GatewayService.connection) {
            "connected" -> R.string.conn_connected to R.color.vp_success
            "connecting" -> R.string.conn_connecting to R.color.vp_gold
            "error" -> R.string.conn_error to R.color.vp_error
            else -> R.string.conn_off to R.color.vp_text_secondary
        }
        tvConnTelegram.setText(tgText)
        tvConnTelegram.setTextColor(ContextCompat.getColor(this, tgColor))

        val sms = hasSmsPermission()
        tvConnSms.setText(if (sms) R.string.sms_granted else R.string.sms_missing)
        tvConnSms.setTextColor(ContextCompat.getColor(this, if (sms) R.color.vp_success else R.color.vp_error))

        tvConnBots.text = fa(Store.bots(this).size)
        tvConnCards.text = fa(Store.cards(this).size)

        if (LicenseManager.enabled()) {
            rowLicense.visibility = View.VISIBLE
            tvConnLicense.setText(
                if (LicenseManager.isLicensed(this)) R.string.license_active else R.string.license_free
            )
        } else {
            rowLicense.visibility = View.GONE
        }
    }

    private fun renderStats() {
        val tx = Stats.all(this)
        val bots = Store.bots(this)
        val cards = Store.cards(this)
        val signature = "${tx.size}|${tx.lastOrNull()?.time}|${bots.hashCode()}|${cards.hashCode()}"
        if (signature == statsSignature) return
        statsSignature = signature

        val ok = tx.count { it.ok }
        tvStatTotal.text = fa(tx.size)
        tvStatOk.text = fa(ok)
        tvStatFail.text = fa(tx.size - ok)

        llStats.removeAllViews()
        tvStatsEmpty.visibility = if (bots.isEmpty()) View.VISIBLE else View.GONE
        for (bot in bots) {
            val botTx = tx.filter { it.bot.equals(bot.username, ignoreCase = true) }
            val botOk = botTx.count { it.ok }
            llStats.addView(
                statRow(
                    "🤖 @" + bot.username,
                    getString(R.string.stat_counts, botOk, botTx.size - botOk),
                    bold = true, indentDp = 0, topDp = 12
                )
            )
            for (card in cards.filter { it.botRef == bot.id }) {
                val n = tx.count { it.ok && it.card == card.number }
                llStats.addView(
                    statRow(
                        "💳 " + card.number.takeLast(4) + (if (card.holder.isNotBlank()) " · " + card.holder else ""),
                        getString(R.string.stat_card_count, n),
                        bold = false, indentDp = 18, topDp = 6
                    )
                )
            }
        }
        Fonts.apply(llStats)
    }

    private fun statRow(label: String, value: String, bold: Boolean, indentDp: Int, topDp: Int): View {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((indentDp * d).toInt(), (topDp * d).toInt(), 0, 0)
        }
        val left = TextView(this).apply {
            text = label
            textSize = if (bold) 14f else 13f
            setTextColor(ContextCompat.getColor(context, if (bold) R.color.vp_text else R.color.vp_text_secondary))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val right = TextView(this).apply {
            text = value
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.vp_primary))
        }
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(right, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun fa(n: Int): String = NumberFormat.getInstance(Locale("fa")).format(n)

    private fun scrollLogsToEnd() {
        svLogs.post { svLogs.fullScroll(View.FOCUS_DOWN) }
    }
}
