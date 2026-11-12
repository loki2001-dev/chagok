package io.chagok.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 화면 부품. 주·야간 모두 본문 대비 4.5:1 이상을 맞춘 중립 팔레트다.
 * 의미는 색만이 아니라 문구로도 표시한다 (기획서 10장).
 */
object Palette {
    val bg = Color.parseColor("#101318")
    val surface = Color.parseColor("#1A1F26")
    val surface2 = Color.parseColor("#252C36")
    val line = Color.parseColor("#323B47")
    val text = Color.parseColor("#F2F4F7")
    val sub = Color.parseColor("#B4BDC9")
    val accent = Color.parseColor("#4CC9A4")
    val onAccent = Color.parseColor("#06241B")
    val warn = Color.parseColor("#F4C25B")
    val danger = Color.parseColor("#FF8A80")
}

class Ui(private val ctx: Context) {
    fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun round(color: Int, radius: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    fun column(gap: Int = 12) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(0, dp(gap)) }
    }

    fun row(gap: Int = 12) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(dp(gap), 0) }
    }

    fun card(vararg children: View, tint: Int = Palette.surface) = column(10).apply {
        background = round(tint)
        setPadding(dp(24), dp(20), dp(24), dp(20))
        children.forEach { addView(it) }
    }

    fun text(s: CharSequence, size: Float = 22f, color: Int = Palette.text, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    fun title(s: String) = text(s, 30f, bold = true)
    fun label(s: String) = text(s, 18f, Palette.sub)
    fun big(s: String, color: Int = Palette.text) = text(s, 36f, color, bold = true)

    fun button(s: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(64)
        minWidth = dp(120)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.4f
        setTextColor(if (primary) Palette.onAccent else Palette.text)
        background = round(if (primary) Palette.accent else Palette.surface2, 14)
        isClickable = true
        isFocusable = true
        setOnClickListener { if (enabled) onClick() }
    }

    fun chip(s: String, color: Int = Palette.sub) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(color)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = round(Palette.surface2, 20, color)
    }

    fun input(hint: String, value: String?, numeric: Boolean) = EditText(ctx).apply {
        setText(value.orEmpty())
        this.hint = hint
        setHintTextColor(Palette.sub)
        setTextColor(Palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        // 가로 차량 화면에서 키보드가 앱 전체를 덮는 전체 화면 입력을 쓰지 않는다
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_DONE
        isSingleLine = true
        background = round(Palette.surface2, 12, Palette.line)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minHeight = dp(60)
    }

    fun spacer(h: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h)) }

    fun weighted(v: View, weight: Float = 1f) = v.apply {
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, weight)
    }
}
