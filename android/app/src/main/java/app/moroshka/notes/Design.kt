package app.moroshka.notes

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Colors and dimensions follow docs/design/tokens.json. */
class Design(context: Context) {
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val background = color(if (dark) "#211B29" else "#FCF5F2")
    val surface = color(if (dark) "#302638" else "#FFFFFF")
    val text = color(if (dark) "#FFF4F5" else "#48374E")
    val muted = color(if (dark) "#D3BED3" else "#69536D")
    val border = color(if (dark) "#514257" else "#EBDDDC")
    val secondary = color(if (dark) "#D8B8F5" else "#79518C")
    val tint = color(if (dark) "#46334E" else "#F8E8F1")
    val gradient = intArrayOf(color("#B84F3B"), color("#794298"))
    private val density = context.resources.displayMetrics.density

    private fun color(value: String) = Color.parseColor(value)
    fun dp(value: Int) = (value * density + .5f).toInt()
    fun panel(color: Int = surface, radius: Int = 20, outline: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (outline) setStroke(dp(1), border)
    }
    fun primary() = GradientDrawable(GradientDrawable.Orientation.TL_BR, gradient).apply {
        cornerRadius = dp(16).toFloat()
    }
}
