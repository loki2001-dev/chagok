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
import java.time.LocalDate
import java.time.Period
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

    fun home(
        currentKm: Double?,
        driveCard: View,
        openSchedule: () -> Unit,
        setUpPlan: (Item) -> Unit,
        openReview: () -> Unit,
        openDiary: () -> Unit,
        openStats: () -> Unit,
        openEntry: (DiaryEntry) -> Unit,
    ): View {
        val ledger = service.ledger(YearMonth.from(service.today()))
        val reviews = service.reviewCount()
        val cost = when {
            ledger.entryCount == 0 -> "기록 없음"
            ledger.missingCostCount == ledger.entryCount -> "금액 미입력"
            else -> won(ledger.total)
        }
        val costDetails = if (ledger.missingCostCount > 0) "금액 미입력 ${ledger.missingCostCount}건" else null
        val costCard = metricCard("이번 달 지출", cost, Icon.CHART, costDetails, open = openStats)
        val reviewCard = metricCard("확인할 기록", "${reviews}건", Icon.JOURNAL,
            color = if (reviews > 0) Palette.accent else Palette.text, open = openReview).apply {
            tag = "summary-review-card"
        }
        val mileage = metricCard("차량 누적 거리", currentKm?.let(::km) ?: "수신 대기", Icon.CAR).apply {
            tag = "summary-mileage-card"
        }
        val items = service.maintenanceItems()
        val schedules = service.schedules(currentKm).filter {
            it.plan.item in items && it.state != ScheduleState.NO_PLAN
        }
        val scheduledItems = schedules.map { it.plan.item }.toSet()
        return ui.column(16).apply {
            addView(ui.section("정비 주기", if (schedules.isNotEmpty()) "마지막 정비 이후 주행과 기간으로 본 마모도" else null))
            schedules.map { maintenanceCard(it, currentKm, openSchedule) }.chunked(2).forEach { cards ->
                addView(if (cards.size == 2) pair(cards[0], cards[1]) else cards.single())
            }
            val unconfigured = items.filterNot { it in scheduledItems }
            if (unconfigured.isNotEmpty()) {
                addView(ui.section("주기 미설정 · ${unconfigured.size}개"))
                unconfigured.map { item ->
                    ui.actionRow(item.label, "주기 설정", Icon.WRENCH) { setUpPlan(item) }
                }.chunked(2).forEach { rows ->
                    addView(if (rows.size == 2) pair(rows[0], rows[1]) else rows.single())
                }
            }
            addView(pair(driveCard, costCard))
            addView(pair(reviewCard, mileage))
            addView(ui.section("최근 차량 일지"))
            val recent = ui.card()
            val entries = service.timeline().take(3)
            if (entries.isEmpty()) recent.addView(ui.text("아직 남겨진 기록이 없어요", 22f, Palette.sub))
            entries.forEachIndexed { index, entry ->
                if (index > 0) recent.addView(ui.divider())
                recent.addView(recordRow(entry) { openEntry(entry) })
            }
            recent.addView(ui.actionRow("일지 전체 보기", null, Icon.JOURNAL, openDiary))
            addView(recent)
        }
    }

    /** "1년 3개월", "6개월", "12일"처럼 가장 큰 두 단위까지만 읽기 쉽게 적는다. */
    private fun span(from: LocalDate, to: LocalDate): String {
        val period = Period.between(from, to)
        return when {
            period.years > 0 -> "${period.years}년" + if (period.months > 0) " ${period.months}개월" else ""
            period.months > 0 -> "${period.months}개월"
            else -> "${period.days}일"
        }
    }

    private fun months(count: Int) = if (count % 12 == 0) "${count / 12}년" else "${count}개월"

    private fun conditionTone(state: ConditionState) = when (state) {
        ConditionState.DUE -> Palette.danger
        ConditionState.SOON -> Palette.warn
        else -> Palette.accent
    }

    private fun fact(label: String, value: String) = ui.column(4).apply {
        addView(ui.text(label, 20f, Palette.sub))
        addView(ui.text(value, 24f, Palette.text, true))
    }

    /** 제목·퍼센트, 막대, 풀어 쓴 설명 한 줄. 색에만 기대지 않도록 수치를 문장으로도 적는다. */
    private fun meter(title: String, percent: Int, tone: Int, detail: String) = ui.column(8).apply {
        addView(horizontal().apply {
            addView(ui.text(title, 22f, Palette.text, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(ui.text("$percent%", 24f, tone, true))
        })
        val ratio = percent.coerceIn(0, 100) / 100f
        addView(horizontal().apply {
            background = ui.round(Palette.surface2, 6)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (ratio > 0) addView(View(context).apply { background = ui.round(tone, 6) }, LinearLayout.LayoutParams(0, MATCH_PARENT, ratio))
            if (ratio < 1) addView(View(context), LinearLayout.LayoutParams(0, MATCH_PARENT, 1 - ratio))
        }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(12)))
        addView(ui.text(detail, 20f, Palette.sub))
    }

    private fun maintenanceCard(status: ScheduleStatus, currentKm: Double?, open: () -> Unit): View {
        val item = status.plan.item
        // 항목 이름이 쓰는 낱말을 그대로 따른다: 엔진오일 '교환', 에어컨 필터 '교체'
        val verb = when {
            item.kind == ItemKind.REPLACE || item == Item.TIRE_REPLACE -> if (item.label.endsWith("교환")) "교환" else "교체"
            item.kind == ItemKind.INSPECT -> "점검"
            item.kind == ItemKind.REFILL -> "보충"
            else -> "관리"
        }
        val planned = if (verb == "교환") "교체" else verb
        val dateLabel = "$planned 예정일"
        val needsAction = "$planned 필요"
        val percent = status.progressPercent
        val basis = status.basis
        val basisKm = basis?.odometer?.value
        val drivenKm = status.usedKm ?: if (basisKm != null && currentKm != null && currentKm >= basisKm) currentKm - basisKm else null
        val label = when (status.state) {
            ScheduleState.DUE -> needsAction
            ScheduleState.SOON -> "임박"
            ScheduleState.UPCOMING -> if (percent == null) "계산 보류" else "예정"
            else -> status.state.label
        }
        val urgent = status.state == ScheduleState.DUE || status.state == ScheduleState.SOON
        val tone = if (urgent) Palette.danger else Palette.accent
        return ui.card().apply {
            addView(cardHeading(item.label, Icon.WRENCH, true, tone))
            addView(ui.text(listOfNotNull(percent?.let { "$it%" }, label).joinToString(" · "),
                if (percent == null) 24f else 30f, tone, true))
            if (basis != null) {
                addView(horizontal().apply {
                    gravity = Gravity.TOP
                    addView(fact("최근 $verb", basis.performedOn.format(dates)), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(fact("$verb 후 주행", when {
                        drivenKm != null -> km(drivenKm)
                        status.odometerReversed -> "확인 필요"
                        basisKm == null -> "기록 없음"
                        else -> "수신 대기"
                    }), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.dp(12) })
                })
            }
            val interval = status.plan.intervalKm
            val wear = status.wearPercent
            val used = status.usedKm
            val leftKm = status.remainingKm
            if (interval != null && wear != null && used != null && leftKm != null) {
                addView(ui.divider())
                val rest = if (leftKm > 0) "${km(leftKm)} 남음" else if (leftKm < 0) "${km(-leftKm)} 지남" else "권장 주행 도달"
                addView(meter("주행 마모도", wear, conditionTone(status.kmState),
                    "권장 ${km(interval.toDouble())} 중 ${km(used)} 주행 · $rest"))
            } else if (status.kmState == ConditionState.PENDING) {
                addView(ui.divider())
                addView(ui.text(if (status.odometerReversed) "주행 마모도 · 주행거리 확인이 필요해요" else "주행 마모도 · 주행거리를 확인하면 계산해요", 20f, Palette.sub))
            }
            val age = status.agePercent
            val today = status.asOf
            val nextDate = status.nextDate
            val intervalMonths = status.plan.intervalMonths
            if (basis != null && age != null && today != null && nextDate != null && intervalMonths != null) {
                addView(ui.divider())
                val rest = when {
                    today.isBefore(nextDate) -> "${span(today, nextDate)} 남음"
                    today.isAfter(nextDate) -> "${span(nextDate, today)} 지남"
                    else -> "권장 기간 도달"
                }
                addView(meter("사용 기간", age, conditionTone(status.dateState),
                    "권장 ${months(intervalMonths)} 중 ${span(basis.performedOn, today)}째 사용 · $rest"))
                addView(ui.text("$dateLabel ${nextDate.format(dates)}", 20f, Palette.sub))
            }
            background = ui.touch(Palette.surface, 20, if (urgent) tone else Palette.border)
            isFocusable = true
            setOnClickListener { open() }
        }
    }

    private fun metricCard(
        title: String,
        value: String,
        icon: Icon,
        detail: String? = null,
        color: Int = Palette.text,
        open: (() -> Unit)? = null,
    ): View = ui.card(cardHeading(title, icon, open != null), ui.text(value, 36f, color, true)).apply {
        detail?.let { addView(ui.text(it, 20f, Palette.sub)) }
        if (open != null) {
            background = ui.touch(Palette.surface, 20, Palette.border)
            isFocusable = true
            setOnClickListener { open() }
        }
    }

    private fun cardHeading(title: String, icon: Icon, clickable: Boolean, tone: Int = Palette.sub): View =
        horizontal().apply {
            addView(ui.withIcon(ui.text(title, 22f, Palette.text, true), icon, tone),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (clickable) addView(ui.icon(Icon.CHEVRON, 20, tone))
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
