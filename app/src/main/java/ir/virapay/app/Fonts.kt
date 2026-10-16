package ir.virapay.app

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.textfield.TextInputLayout

/**
 * Applies the Vazirmatn font to every text in a view tree.
 * The font files are fetched into res/font by the Gradle task `downloadFonts`;
 * if they are not there the app silently keeps the system font.
 */
object Fonts {
    @Volatile private var cached: Typeface? = null

    private fun family(ctx: Context): Typeface? {
        cached?.let { return it }
        val id = ctx.resources.getIdentifier("vazirmatn", "font", ctx.packageName)
        if (id == 0) return null
        val tf = try {
            ResourcesCompat.getFont(ctx, id)
        } catch (e: Exception) {
            null
        }
        cached = tf
        return tf
    }

    fun apply(root: View?) {
        if (root == null) return
        val tf = family(root.context) ?: return
        walk(root, tf)
    }

    private fun walk(v: View, tf: Typeface) {
        if (v is TextInputLayout) {
            v.typeface = tf
        } else if (v is TextView) {
            val style = v.typeface?.style ?: Typeface.NORMAL
            v.setTypeface(tf, style)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), tf)
        }
    }
}
