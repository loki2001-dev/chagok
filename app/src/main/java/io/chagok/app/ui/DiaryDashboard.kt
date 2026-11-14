package io.chagok.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.*
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import io.chagok.app.domain.*
import io.chagok.app.domain.DiaryService.Companion.km
import io.chagok.app.domain.DiaryService.Companion.won
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Parked read models, using only records visible to the current local user. */
class DiaryDashboard(private val context: Context, private val ui: Ui, private val service: DiaryService) {
    private val dates = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    private fun horizontal() = LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
    private fun action(view:View,description:String,click:()->Unit):View = view.apply {
        isClickable=true;isFocusable=true;contentDescription=description;setOnClickListener { click() }
    }
    fun pair(first:View,second:View):View {
        val wide=(ui.availableWidthDp?:0)>=700
        return object:LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
                super.onMeasure(widthMeasureSpec,heightMeasureSpec)
                val equal=maxOf(first.measuredHeight,second.measuredHeight)
                for(child in listOf(first,second))child.measure(View.MeasureSpec.makeMeasureSpec(child.measuredWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(equal,View.MeasureSpec.EXACTLY))
                setMeasuredDimension(measuredWidth,View.resolveSize(if(wide)equal else equal*2+ui.dp(16),heightMeasureSpec))
            }
        }.apply {
            orientation=if(wide)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            addView(first,LinearLayout.LayoutParams(if(wide)0 else MATCH_PARENT,WRAP_CONTENT,if(wide)1f else 0f).apply { if(wide)marginEnd=ui.dp(16) })
            addView(second,LinearLayout.LayoutParams(if(wide)0 else MATCH_PARENT,WRAP_CONTENT,if(wide)1f else 0f).apply { if(!wide)topMargin=ui.dp(16) })
        }
    }

    fun home(currentKm:Double?,openSchedule:()->Unit,openReview:()->Unit,openDiary:()->Unit,openStats:()->Unit,openEntry:(DiaryEntry)->Unit):View {
        val ledger=service.ledger(YearMonth.from(service.today()))
        val next=service.schedules(currentKm).firstOrNull { it.state in setOf(ScheduleState.DUE,ScheduleState.SOON,ScheduleState.UPCOMING) }
        val nextCard=ui.card(
            ui.withIcon(ui.text("다음 차량 관리",22f,Palette.onAccent),Icon.WRENCH,Palette.onAccent),
            ui.text(next?.plan?.item?.label?:"관리 주기 설정",32f,Palette.onAccent,true),
            ui.text(next?.let(::remaining)?:"차량 매뉴얼에 맞춰 알려주세요",26f,Palette.onAccent,true),
            ui.text("관리 일정 보기  ›",22f,Palette.onAccent),tint=Palette.accent)
        val costCard=ui.card(
            ui.withIcon(ui.text("이번 달 지출",22f,Palette.sub),Icon.CHART,Palette.sub),
            ui.text(if(ledger.entryCount==0)"첫 기록을 기다려요" else if(ledger.missingCostCount==ledger.entryCount)"금액 미입력" else won(ledger.total),40f,bold=true),
            ui.text("확정 ${ledger.entryCount}건 · 금액 미입력 ${ledger.missingCostCount}건",22f,Palette.sub),
            ui.text("비용 통계 보기  ›",22f,Palette.accent))
        val reviews=service.reviewCount()
        val review=ui.actionRow(if(reviews==0)"확인할 기록이 없어요" else "${reviews}건만 확인해 주세요",
            "자동 감지·작성 중인 기록",Icon.JOURNAL,openReview)
        review.contentDescription="확인할 기록 $reviews 건"
        val reviewCard=ui.card(review).apply { gravity=Gravity.CENTER_VERTICAL;tag="summary-review-card" }
        val mileage=ui.card(ui.text("차량 누적 거리",22f,Palette.sub),ui.text(currentKm?.let(::km)?:"수신 대기",34f,bold=true)).apply {
            gravity=Gravity.CENTER_VERTICAL;tag="summary-mileage-card"
        }
        return ui.column(20).apply {
            addView(pair(action(nextCard,"다음 관리 보기",openSchedule),action(costCard,"이번 달 통계 보기",openStats)))
            addView(pair(reviewCard,mileage))
            addView(ui.section("최근 차량 일지","차곡에 남은 최근 기록이에요"))
            val recent=ui.card()
            val entries=service.timeline().take(3)
            if(entries.isEmpty()) recent.addView(empty("아직 남겨진 기록이 없어요","주유·충전을 감지하면 확인할 기록으로 모아둘게요.",Icon.JOURNAL))
            entries.forEachIndexed { index,e -> if(index>0)recent.addView(ui.divider());recent.addView(recordRow(e) { openEntry(e) }) }
            recent.addView(ui.actionRow("일지 전체 보기",null,Icon.JOURNAL,openDiary))
            addView(recent)
        }
    }

    fun empty(title:String,message:String,icon:Icon):View = ui.column(12).apply {
        setPadding(ui.dp(8),ui.dp(20),ui.dp(8),ui.dp(20));addView(ui.icon(icon,40));addView(ui.text(title,26f,bold=true));addView(ui.text(message,22f,Palette.sub))
    }

    fun recordRow(entry:DiaryEntry,open:()->Unit):View {
        val cancelled=entry.status==EntryStatus.CANCELLED
        val narrow=(ui.availableWidthDp?:0)<550
        val tone=if(cancelled)Palette.sub else Palette.text
        return action(ui.column(10).apply {
            setPadding(ui.dp(2),ui.dp(16),ui.dp(2),ui.dp(16))
            addView(ui.text(entry.performedOn.format(dates)+(if(cancelled)" · 취소됨" else ""),20f,Palette.sub))
            val row=horizontal()
            row.addView(ui.icon(itemIcon(entry.item),30,if(cancelled)Palette.sub else Palette.accent),LinearLayout.LayoutParams(ui.dp(30),ui.dp(30)).apply { marginEnd=ui.dp(14) })
            row.addView(ui.text(entry.item.label,26f,tone,true),LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
            if(!narrow) row.addView(ui.text(won(entry.cost),28f,tone,true),LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT).apply { marginStart=ui.dp(16) })
            row.addView(ui.icon(Icon.CHEVRON,22,Palette.sub),LinearLayout.LayoutParams(ui.dp(22),ui.dp(22)).apply { marginStart=ui.dp(12) })
            addView(row)
            if(narrow)addView(ui.text(won(entry.cost),28f,tone,true))
            addView(ui.text(listOfNotNull(entry.odometer?.let { km(it.value) },if(entry.candidateId!=null)"자동 감지 후 확인" else "직접 기록",
                if(entry.version>1)"수정 ${entry.version-1}회" else null).joinToString(" · "),20f,Palette.sub))
            background=ui.touch()
        },"${entry.performedOn} ${entry.item.label} 기록 상세",open)
    }

    fun statistics(month:YearMonth,changeMonth:(YearMonth)->Unit,openMonth:(YearMonth)->Unit):View {
        val ledger=service.ledger(month);val previous=service.ledger(month.minusMonths(1))
        val comparison=when {
            ledger.entryCount==0->"이 달에는 확정한 기록이 없어요."
            ledger.missingCostCount>0||previous.missingCostCount>0->"금액 미입력이 있어 전월 비교를 보류해요."
            previous.entryCount==0->"지난달 기록이 없어 비교를 보류해요."
            ledger.total==previous.total->"지난달과 같은 금액이에요."
            else->"지난달보다 ${won(kotlin.math.abs(ledger.total-previous.total))} ${if(ledger.total>previous.total)"늘었어요" else "줄었어요"}."
        }
        return ui.column(20).apply {
            addView(monthPicker(month,changeMonth))
            addView(ui.card(ui.text("확정된 차량 비용",22f,Palette.sub),
                ui.text(if(ledger.entryCount>0&&ledger.missingCostCount==ledger.entryCount)"금액 미입력" else won(ledger.total),46f,bold=true),
                ui.text("기록 ${ledger.entryCount}건  ·  금액 미입력 ${ledger.missingCostCount}건",22f,Palette.sub),
                ui.divider(),ui.text(comparison,22f,Palette.sub)))
            val months=(5 downTo 0).map { service.ledger(month.minusMonths(it.toLong())) }
            val max=months.maxOf { it.total }.coerceAtLeast(1)
            val chartWidthDp=((ui.availableWidthDp?:600)-48).coerceAtLeast(540)
            val chart=horizontal().apply { gravity=Gravity.BOTTOM;minimumWidth=ui.dp(chartWidthDp) }
            months.forEach { value ->
                val col=ui.column(10).apply {
                    gravity=Gravity.CENTER_HORIZONTAL
                    addView(View(context),LinearLayout.LayoutParams(1,0,1f))
                    addView(ui.text(if(value.entryCount==0)"—" else if(value.entryCount==value.missingCostCount)"미입력" else "%.1f".format(value.total/10000.0),22f,Palette.sub))
                    addView(View(context).apply { background=ui.round(if(value.month==month)Palette.accent else Palette.line,7) },
                        LinearLayout.LayoutParams(ui.dp(32),if(value.total==0L)0 else (ui.dp(148)*value.total.toDouble()/max).toInt().coerceAtLeast(2)))
                    addView(ui.text("${value.month.monthValue}월",22f,Palette.text,value.month==month))
                }
                action(col,"${value.month} ${won(value.total)}, 금액 미입력 ${value.missingCostCount}건, 일지 보기") { openMonth(value.month) }
                chart.addView(col,LinearLayout.LayoutParams(ui.dp(chartWidthDp/6),ui.dp(256)))
            }
            val scroller=HorizontalScrollView(context).apply { isFillViewport=true;isHorizontalScrollBarEnabled=true
                addView(chart,android.widget.FrameLayout.LayoutParams(ui.dp(chartWidthDp),WRAP_CONTENT)) }
            addView(if(months.all { it.entryCount==0 }) ui.card(ui.section("최근 6개월"),ui.text("기록이 쌓이면 월별 지출 흐름을 보여드릴게요.",22f,Palette.sub))
                else ui.card(ui.section("최근 6개월","단위: 만 원 · 월을 눌러 일지 보기"),scroller))
            val categories=ui.card(ui.section("항목별 비용"))
            if(ledger.byItem.isEmpty())categories.addView(ui.text("금액을 입력한 기록이 쌓이면 보여드릴게요.",22f,Palette.sub))
            ledger.byItem.entries.sortedByDescending { it.value }.forEach { (item,value) ->
                categories.addView(ui.text("${item.label} · ${won(value)}",24f,bold=true))
                val bar=horizontal();val ratio=if(ledger.total>0)value.toFloat()/ledger.total else 0f
                if(ratio>0)bar.addView(View(context).apply { background=ui.round(Palette.accent,4) },LinearLayout.LayoutParams(0,ui.dp(8),ratio))
                if(ratio<1)bar.addView(View(context).apply { background=ui.round(Palette.surface2,4) },LinearLayout.LayoutParams(0,ui.dp(8),1-ratio))
                categories.addView(bar)
            }
            addView(categories);addView(ui.actionRow("이 달의 일지 보기","초안·취소 기록은 통계에서 제외돼요",Icon.JOURNAL) { openMonth(month) })
        }
    }

    fun monthPicker(month:YearMonth,change:(YearMonth)->Unit):View = horizontal().apply {
        setPadding(0,ui.dp(4),0,ui.dp(4))
        addView(ui.text("‹",36f,bold=true).apply { gravity=Gravity.CENTER;minHeight=ui.dp(64);background=ui.touch();contentDescription="이전 달";isFocusable=true;setOnClickListener { change(month.minusMonths(1)) } },LinearLayout.LayoutParams(ui.dp(56),WRAP_CONTENT))
        addView(ui.text("${month.year}년 ${month.monthValue}월",28f,bold=true).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        addView(ui.text("›",36f,bold=true).apply { gravity=Gravity.CENTER;minHeight=ui.dp(64);background=ui.touch();contentDescription="다음 달";isFocusable=true;isEnabled=month<YearMonth.from(service.today());alpha=if(isEnabled)1f else .3f;setOnClickListener { change(month.plusMonths(1)) } },LinearLayout.LayoutParams(ui.dp(56),WRAP_CONTENT))
    }

    companion object {
        fun itemIcon(item:Item)=when(item) { Item.REFUEL->Icon.FUEL;Item.CHARGE->Icon.BOLT;else->Icon.WRENCH }
        fun stateColor(state:ScheduleState)=when(state) { ScheduleState.DUE->Palette.danger;ScheduleState.SOON->Palette.warn;ScheduleState.UPCOMING->Palette.accent;else->Palette.sub }
        fun remaining(status:ScheduleStatus):String {
            val distance=status.remainingKm?.let { if(it<=0)"거리 기준 도래" else "${km(it)} 남음" }
            val days=status.remainingDays?.let { if(it<=0)"날짜 기준 도래" else "${it}일 남음" }
            return listOfNotNull(distance,days).joinToString(" · ").ifEmpty { if(status.basis==null)"이전 정비 기록이 필요해요" else "주행거리를 확인하면 계산해요" }
        }
    }
}
