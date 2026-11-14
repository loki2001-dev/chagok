package io.chagok.app.ui

import android.view.View
import android.view.Gravity
import android.widget.LinearLayout
import android.view.ViewGroup.LayoutParams.*
import io.chagok.app.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import io.chagok.app.companion.Encounters

class AutoDiaryView(private val ui: Ui,private val store: DiaryStore,private val diary: DiaryService,private val user: String) {
    private val zone=ZoneId.systemDefault()
    private val clock=DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    private fun own()=store.trips(diary.profile().id).filter { it.user==user }.sortedByDescending { it.startedAt }
    fun today(open:()->Unit): View {
        val list=own().filter { it.startedAt.atZone(zone).toLocalDate()==LocalDate.now() }
        val km=list.mapNotNull { it.distanceKm }.sum()
        val distance=if(list.any { it.distanceKm==null })"거리 일부 미수신" else "%.1fkm".format(km)
        val detail=if(list.isEmpty())"달리면 거리와 시간이 자동으로 남아요" else "${list.size}회 · $distance · 친구 ${list.flatMap { it.friends }.toSet().size}명"
        return ui.card(ui.actionRow("오늘의 드라이브",detail,Icon.CAR,open))
    }
    fun history(month: YearMonth,previous:()->Unit,next:()->Unit): View = ui.column(16).apply {
        addView(ui.text("거리·시간은 관측한 구간만 남겨요. 위치는 수집하지 않아요.",22f,Palette.sub))
        addView(LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            fun arrow(text:String,description:String,enabled:Boolean,action:()->Unit)=ui.text(text,32f,bold=true).apply {
                contentDescription=description;gravity=Gravity.CENTER;minHeight=ui.dp(64);isEnabled=enabled;alpha=if(enabled)1f else .4f
                background=ui.touch(Palette.surface,12);setOnClickListener { if(enabled)action() }
            }
            addView(arrow("‹","이전 달",true,previous),LinearLayout.LayoutParams(ui.dp(56),WRAP_CONTENT))
            addView(ui.text("${month.year}년 ${month.monthValue}월",26f,bold=true).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
            addView(arrow("›","다음 달",month<YearMonth.now(),next),LinearLayout.LayoutParams(ui.dp(56),WRAP_CONTENT))
        })
        val list=own().filter { YearMonth.from(it.startedAt.atZone(zone))==month }
        if(list.isEmpty())addView(ui.card(ui.text("아직 운행 기록이 없어요",28f,bold=true),ui.text("자동 기록을 켜고 달리면 이곳에 모여요.")))
        list.groupBy { it.startedAt.atZone(zone).toLocalDate() }.forEach { (date,trips) ->
            addView(ui.section(date.format(DateTimeFormatter.ofPattern("M월 d일")),"운행 ${trips.size}회 · 만난 친구 ${trips.flatMap { it.friends }.toSet().size}명"))
            trips.forEach { t ->
                val card=ui.card(ui.text("${clock.format(t.startedAt)} — ${t.endedAt?.let(clock::format) ?: "운행 정리 중"}",26f,bold=true),
                    ui.text(t.distanceKm?.let { "%.1fkm".format(it) } ?: "거리 확인 필요",36f,Palette.accent,true),
                    ui.text("관측 이동 시간 ${t.movingSeconds/60}분 ${t.movingSeconds%60}초",22f,Palette.sub))
                if(t.hasGap)card.addView(ui.text("일부 구간 미수신\n거리·시간에 누락이 있을 수 있어요.",22f,Palette.warn))
                if(t.friends.isNotEmpty()) {
                    card.addView(ui.label("함께 만난 친구"))
                    t.friends.mapNotNull { Encounters.find(it)?.name }.forEach { card.addView(ui.text(it,22f)) }
                }
                addView(card)
            }
            val candidates=diary.pendingCandidates().count { it.after.receivedAt.atZone(zone).toLocalDate()==date }
            if(candidates>0)addView(ui.label("이날 확인할 주유·충전 후보 ${candidates}건"))
        }
    }
}
