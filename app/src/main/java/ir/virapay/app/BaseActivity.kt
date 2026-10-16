package ir.virapay.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Shared behaviour: Vazirmatn font, PIN protection for inner screens, top bar helper. */
abstract class BaseActivity : AppCompatActivity() {

    /** Inner screens bounce back to the lock screen if the process was restarted. */
    protected open val requiresUnlock: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (requiresUnlock && !Session.unlocked) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }

    override fun onContentChanged() {
        super.onContentChanged()
        Fonts.apply(window.decorView)
    }

    /** Wires the shared top bar (layout view_topbar). */
    protected fun setupTopBar(@StringRes title: Int) {
        findViewById<TextView>(R.id.tvTitle).setText(title)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
    }

    protected fun toast(@StringRes id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()

    protected fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** Shows a dialog with the Vazirmatn font applied to its buttons, title and content. */
    protected fun showWithFont(dialog: AlertDialog) {
        dialog.show()
        Fonts.apply(dialog.window?.decorView)
    }
}
