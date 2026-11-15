package io.chagok.app.companion

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import io.chagok.app.domain.Powertrain
import io.chagok.app.ui.AppIcon
import io.chagok.app.ui.Icon
import io.chagok.app.ui.Palette
import io.chagok.app.ui.Ui

/** A single, scrollable setup page. No permissions or recording before the explicit start. */
class FirstRunView(
    context: Context,
    initial: Powertrain?,
    private val onSelection: (Powertrain) -> Unit,
    private val onStart: (Powertrain) -> Unit,
) : FrameLayout(context) {
    private val ui = Ui(context)
    private var selected = initial
    private var parked = false
    private var lastWidth = -1
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val blocked = ui.text("정차 후 차곡을 시작해 주세요.\n차량이 정차했는지 확인하고 있어요.", 24f).apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24))
    }
    private val explanation = ui.text("", 20f, Palette.sub)
    private val choices = RadioGroup(context)
    private val options = mutableMapOf<Powertrain, RadioButton>()
    private val columns = LinearLayout(context)
    private val vehicleColumn = ui.column(12)
    private val detailsColumn = ui.column(16)
    private val title = ui.text("운행은 자동으로,\n비용은 한마디로", 28f, bold = true)
    private val start = ui.button("자동 기록 시작", primary = true) {
        selected?.takeIf { parked }?.let(onStart)
    }

    init {
        setBackgroundColor(Palette.bg)
        val content = ui.column(16).apply {
            setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(20))
            addView(ui.text("차곡 · 시작하기", 24f, Palette.accent, true))
            addView(title)
        }
        vehicleColumn.addView(ui.text("내 차의 종류", 22f, bold = true))
        Powertrain.entries.forEach { powertrain ->
            val option = RadioButton(context).apply {
                id = View.generateViewId()
                text = powertrain.label
                textSize = 22f
                typeface = ui.typeface()
                buttonDrawable = null
                setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
                minHeight = ui.dp(56)
                isChecked = initial == powertrain
                setOnClickListener {
                    selected = powertrain
                    onSelection(powertrain)
                    updateSelection()
                    setParked(parked)
                }
            }
            options[powertrain] = option
            choices.addView(option, RadioGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin=ui.dp(8) })
        }
        vehicleColumn.addView(choices)
        detailsColumn.addView(ui.card(ui.text("차곡이 챙겨둘게요", 22f, bold = true), explanation))
        detailsColumn.addView(ui.text("시작하면 차량 연결·알림 권한을 요청해요. 마이크는 말로 입력할 때, 위치는 방문 감지를 켤 때 요청해요.", 20f, Palette.sub))
        detailsColumn.addView(ui.text("정비 주기와 장소 설정은 나중에 해도 돼요. 자동 기록은 메뉴에서 끌 수 있어요.", 20f, Palette.sub))
        columns.addView(vehicleColumn)
        columns.addView(detailsColumn)
        content.addView(columns)
        panel.addView(ScrollView(context).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        panel.addView(start, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(16))
        })
        addView(panel, LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        addView(blocked, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        updateSelection()
        setParked(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(ui.dp(1080))
        if (lastWidth != width) {
            lastWidth = width
            val wide = width >= ui.dp(700)
            panel.layoutParams.width = width
            columns.orientation = if(wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            vehicleColumn.layoutParams = LinearLayout.LayoutParams(if(wide)0 else MATCH_PARENT, WRAP_CONTENT, if(wide)1f else 0f)
            detailsColumn.layoutParams = LinearLayout.LayoutParams(if(wide)0 else MATCH_PARENT, WRAP_CONTENT, if(wide)1.3f else 0f).apply {
                if(wide) marginStart=ui.dp(24) else topMargin=ui.dp(16)
            }
            title.text = if(wide) "운행은 자동으로, 비용은 한마디로" else "운행은 자동으로,\n비용은 한마디로"
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun updateSelection() {
        options.forEach { (powertrain, option) ->
            val chosen=selected==powertrain
            option.isChecked=chosen
            option.setTextColor(if(chosen)Palette.accent else Palette.text)
            option.background=ui.touch(if(chosen)Palette.accentSoft else Palette.surface,12,if(chosen)Palette.accent else Palette.border)
            val check=if(chosen) AppIcon(Icon.CHECK,Palette.accent).apply { setBounds(0,0,ui.dp(24),ui.dp(24)) } else null
            option.setCompoundDrawablesRelative(null,null,check,null)
            option.contentDescription=powertrain.label+if(chosen)", 선택됨" else ", 선택 안 됨"
        }
        explanation.text = when (selected) {
            Powertrain.EV -> "운행 거리와 충전으로 보이는 변화를 남겨요. 비용은 확인받은 뒤 기록해요."
            Powertrain.ICE, Powertrain.HYBRID -> "운행 거리와 주유로 보이는 변화를 남겨요. 비용은 확인받은 뒤 기록해요."
            null -> "운행과 주유·충전 후보를 차량 안에 남겨요. 비용은 확인받은 뒤 기록해요."
        }
    }

    fun setParked(value: Boolean) {
        parked = value
        panel.visibility = if (value) View.VISIBLE else View.GONE
        blocked.visibility = if (value) View.GONE else View.VISIBLE
        start.isEnabled = value && selected != null
        start.alpha = if (start.isEnabled) 1f else .4f
    }
}
