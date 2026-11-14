package io.chagok.app.ui

import android.content.Context
import android.content.res.Configuration
import io.chagok.app.R
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.app.AlertDialog
import android.widget.ImageView
import android.widget.AbsListView
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
    var bg = Color.parseColor("#101318"); private set
    var surface = Color.parseColor("#1A1F26"); private set
    var surface2 = Color.parseColor("#252C36"); private set
    var line = Color.parseColor("#323B47"); private set
    var text = Color.parseColor("#F2F4F7"); private set
    var sub = Color.parseColor("#B4BDC9"); private set
    var accent = Color.parseColor("#4CC9A4"); private set
    var onAccent = Color.parseColor("#06241B"); private set
    var warn = Color.parseColor("#F4C25B"); private set
    var danger = Color.parseColor("#FF8A80"); private set
    var border = Color.parseColor("#364A3F"); private set
    var accentSoft = Color.parseColor("#193E2F"); private set

    fun configure(config: Configuration) {
        val night = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        fun c(day: String, dark: String) = Color.parseColor(if (night) dark else day)
        bg = c("#F1F4F2", "#101713"); surface = c("#FFFFFF", "#1A251F")
        surface2 = c("#E7EDE9", "#27362D"); line = c("#81958A", "#7F9889")
        text = c("#16291F", "#F1F7F3"); sub = c("#52675B", "#B9CBBF")
        accent = c("#087457", "#78DDB3"); onAccent = c("#FFFFFF", "#092A1B")
        warn = c("#80520B", "#F4CA78"); danger = c("#AC322B", "#FFABA2")
        border = c("#D8E1DB", "#364A3F"); accentSoft = c("#E1F2E9", "#193E2F")
    }
}

class Ui(private val ctx: Context) {
    var availableWidthDp: Int? = null
    private val compact get() = (availableWidthDp ?: ctx.resources.configuration.screenWidthDp) < 900
    private val font by lazy { ctx.resources.getFont(R.font.chagok_sans) }
    private val weights=mutableMapOf<Int,Typeface>()
    fun typeface(weight: Int = 500) = weights.getOrPut(weight) { Typeface.create(font,weight,false) }
    fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun round(color: Int, radius: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    fun touch(color: Int = Palette.surface, radius: Int = 16, stroke: Int? = null) = RippleDrawable(
        ColorStateList.valueOf((Palette.accent and 0x00ffffff) or 0x20000000), round(color,radius,stroke), round(Color.WHITE,radius))

    fun icon(kind: Icon, size: Int = 28, color: Int = Palette.accent) = ImageView(ctx).apply {
        setImageDrawable(AppIcon(kind,color));layoutParams=LinearLayout.LayoutParams(dp(size),dp(size));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun withIcon(view: TextView, kind: Icon, color: Int = view.currentTextColor, size: Int = 26): TextView = view.apply {
        val drawable=AppIcon(kind,color).apply { setBounds(0,0,dp(size),dp(size)) }
        setCompoundDrawablesRelative(drawable,null,null,null);compoundDrawablePadding=dp(10)
    }

    fun divider() = View(ctx).apply { setBackgroundColor(Palette.border);layoutParams=LinearLayout.LayoutParams(MATCH_PARENT,dp(1)) }

    fun section(title: String, subtitle: String? = null) = column(6).apply {
        addView(text(title,28f,bold=true));subtitle?.let { addView(text(it,22f,Palette.sub)) }
    }

    fun field(label: String, input: EditText, support: String? = null) = column(8).apply {
        addView(text(label,22f,bold=true));addView(input);support?.let { addView(text(it,20f,Palette.sub)) }
    }

    fun actionRow(title: String, subtitle: String?, kind: Icon, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(88)
        setPadding(dp(8),dp(16),dp(8),dp(16));background=touch()
        addView(icon(kind,26),LinearLayout.LayoutParams(dp(26),dp(26)).apply { marginEnd=dp(12) })
        addView(column(5).apply { addView(text(title,24f,bold=true));subtitle?.let { addView(text(it,20f,Palette.sub)) } },LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        addView(icon(Icon.CHEVRON,20,Palette.sub),LinearLayout.LayoutParams(dp(20),dp(20)).apply { marginStart=dp(8) })
        isClickable=true;isFocusable=true;setOnClickListener { onClick() }
    }

    fun column(gap: Int = 12) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(0, dp(gap)) }
    }

    fun row(gap: Int = 12) = LinearLayout(ctx).apply {
        orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { if (compact) setSize(0, dp(gap)) else setSize(dp(gap), 0) }
    }

    fun card(vararg children: View, tint: Int = Palette.surface) = column(10).apply {
        background = round(tint,20,Palette.border)
        val inset=if((availableWidthDp ?: ctx.resources.configuration.screenWidthDp)<400)16 else 24
        setPadding(dp(inset),dp(inset),dp(inset),dp(inset))
        children.forEach { addView(it) }
    }

    // This compile-time constant is inlined; TextView.breakStrategy itself is available since API 23.
    @android.annotation.SuppressLint("InlinedApi")
    fun text(s: CharSequence, size: Float = 22f, color: Int = Palette.text, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = typeface(if (bold) 650 else 500)
        setLineSpacing(0f, 1.12f)
        breakStrategy=android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
    }

    fun title(s: String) = text(s, 36f, bold = true)
    fun label(s: String) = text(s, 20f, Palette.sub)
    fun big(s: String, color: Int = Palette.text) = text(s, 44f, color, bold = true)

    fun button(s: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        typeface = typeface(600)
        gravity = Gravity.CENTER
        minHeight = dp(68)
        minWidth = dp(120)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.4f
        setTextColor(if (primary) Palette.onAccent else Palette.text)
        background = touch(if (primary) Palette.accent else Palette.surface, 14, if(primary)null else Palette.border)
        isClickable = true
        isFocusable = true
        setOnClickListener { if (enabled) onClick() }
    }

    fun chip(s: String, color: Int = Palette.sub) = TextView(ctx).apply {
        text = s
        typeface = typeface(600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(color)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = round(Palette.accentSoft, 8)
    }

    fun input(hint: String, value: String?, numeric: Boolean) = EditText(ctx).apply {
        typeface = typeface(500)
        setText(value.orEmpty())
        this.hint = hint
        setHintTextColor(Palette.sub)
        setTextColor(Palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        // 가로 차량 화면에서 키보드가 앱 전체를 덮는 전체 화면 입력을 쓰지 않는다
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_DONE
        isSingleLine = true
        background = round(Palette.bg, 12, Palette.border)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minHeight = dp(68)
    }

    fun spacer(h: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h)) }

    fun checkbox(label: String, checked: Boolean) = android.widget.CheckBox(ctx).apply {
        text=label;isChecked=checked;typeface=typeface(500);textSize=24f;setTextColor(Palette.text)
        minHeight=dp(64);buttonTintList=ColorStateList.valueOf(Palette.accent);setPadding(dp(8),dp(8),dp(8),dp(8))
    }

    fun weighted(v: View, weight: Float = 1f) = v.apply {
        layoutParams = if (compact) LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, WRAP_CONTENT, weight)
    }

    /** Apply the same typography to platform menus, report dialogs and confirmation dialogs. */
    fun styleDialog(dialog: AlertDialog) {
        val host=ctx as? android.app.Activity
        val available=host?.window?.decorView?.width?.takeIf { it>0 } ?: ctx.resources.displayMetrics.widthPixels
        dialog.window?.setLayout(minOf(available-dp(32),dp(760)),WRAP_CONTENT)
        dialog.window?.setBackgroundDrawable(round(Palette.surface,24))
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            typeface=typeface(500);textSize=24f;setTextColor(Palette.text);setLineSpacing(0f,1.15f)
            maxLines=Int.MAX_VALUE;ellipsize=null;gravity=Gravity.START;textAlignment=View.TEXT_ALIGNMENT_VIEW_START
            val message=this
            post {
                var parent=message.parent
                while(parent is android.view.ViewGroup && parent !is android.widget.ScrollView)parent=parent.parent
                (parent as? android.widget.ScrollView)?.let { scroll ->
                    val buttons=listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL).count { dialog.getButton(it)?.visibility==View.VISIBLE }
                    val maxHeight=((host?.window?.decorView?.height ?: dp(900))-dp(220+buttons*88)).coerceAtLeast(dp(120))
                    message.measure(View.MeasureSpec.makeMeasureSpec(message.width.coerceAtLeast(dp(200)),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    scroll.layoutParams=scroll.layoutParams.apply { height=minOf(message.measuredHeight+scroll.paddingTop+scroll.paddingBottom,maxHeight) }
                }
            }
        }
        if(dialog is android.app.DatePickerDialog) {
            fun stylePicker(view: View) {
                if(view is android.widget.NumberPicker && android.os.Build.VERSION.SDK_INT>=29) {
                    view.textSize=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,28f,ctx.resources.displayMetrics)
                    view.textColor=Palette.text
                }
                if(view is TextView) { view.typeface=typeface(500);view.setTextColor(Palette.text);view.textSize=28f }
                if(view is android.view.ViewGroup)for(i in 0 until view.childCount)stylePicker(view.getChildAt(i))
            }
            stylePicker(dialog.datePicker)
        }
        val titleId=ctx.resources.getIdentifier("alertTitle","id","android")
        dialog.findViewById<TextView>(titleId)?.apply { typeface=typeface(650);textSize=28f;setTextColor(Palette.text) }
        for(which in listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL)) dialog.getButton(which)?.apply {
            typeface=typeface(600);textSize=22f;isAllCaps=false;minHeight=dp(68);minimumHeight=dp(68)
            layoutParams=layoutParams.apply { height=dp(68) }
            setTextColor(if(which==AlertDialog.BUTTON_POSITIVE)Palette.onAccent else Palette.text)
            background=touch(if(which==AlertDialog.BUTTON_POSITIVE)Palette.accent else Palette.surface,14,Palette.border)
        }
        fun styleRows(list: AbsListView) { for(i in 0 until list.childCount) (list.getChildAt(i) as? TextView)?.apply {
            if(tag!="chagok-dialog-row") {
                tag="chagok-dialog-row";typeface=typeface(500);textSize=24f;setTextColor(Palette.text);minHeight=dp(80)
            }
        } }
        dialog.listView?.let { list->
            list.layoutParams=list.layoutParams.apply { height=minOf(dp(list.count*84),(host?.window?.decorView?.height ?: dp(900))-dp(280)).coerceAtLeast(dp(120)) }
            styleRows(list);list.setOnScrollListener(object:AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view:AbsListView?,state:Int) {}
            override fun onScroll(view:AbsListView,first:Int,count:Int,total:Int) { styleRows(view) }
        }) }
    }
}
