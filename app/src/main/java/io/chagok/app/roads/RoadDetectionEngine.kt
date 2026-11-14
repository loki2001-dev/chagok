package io.chagok.app.roads

import io.chagok.app.domain.PlaceFix
import java.time.Instant

/** Main-thread coordinator; execute/deliver inject the real worker/main queues or deterministic test queues.
 * Each completion belongs to the submitted location, never the previously displayed road grade.
 */
class RoadDetectionEngine(
    private val lookup: (PlaceFix) -> RoadMatch,
    private val execute: (() -> Unit) -> Unit,
    private val deliver: (() -> Unit) -> Unit,
    private val now: () -> Instant,
    private val changed: (String?) -> Unit,
) {
    private val stability=RoadStability()
    private var closed=false
    private var busy=false
    private var generation=0
    private var active=false
    private var last: PlaceFix? = null
    private var latest: PlaceFix? = null
    private var label: String? = null
    private var grade: RoadGrade? = null
    private fun publish(value: String?, resolved: RoadGrade? = null) {
        grade=resolved
        if(label != value) { label=value; changed(value) }
    }
    fun sample(enabled: Boolean, fix: PlaceFix?, at: Instant, completed: (RoadGrade?) -> Unit) {
        if(closed) return
        if(enabled != active) { active=enabled; generation++; last=null; latest=null; stability.reset() }
        if(!enabled) { publish(null); completed(null); return }
        if(!RoadMatcher.fresh(fix,at)) {
            generation++; last=null; latest=null; stability.reset()
            publish("도로 알 수 없음 · 새 위치 대기"); completed(null); return
        }
        val observation=fix!!
        latest=observation
        // Do not queue an unbounded series of telemetry snapshots during a slow lookup.
        if(busy) { publish("도로 확인 중"); completed(null); return }
        if(last == observation) { completed(grade); return }
        if(last?.let { observation.at <= it.at } == true) {
            publish("도로 알 수 없음 · 위치 순서 확인"); completed(null); return
        }
        busy=true
        val token=generation
        execute {
            val match=runCatching { lookup(observation) }
            deliver result@{
                busy=false
                if(closed) return@result
                if(token != generation || latest != observation) { completed(null); return@result }
                if(!RoadMatcher.fresh(observation,now())) {
                    stability.reset(); publish("도로 알 수 없음 · 새 위치 대기"); completed(null); return@result
                }
                last=observation
                val result=match.fold({ stability.observe(it,observation.at) }, {
                    stability.reset(); RoadMatch(reason="도로 데이터 읽기 실패")
                })
                publish(result.label,result.grade)
                completed(result.grade)
            }
        }
    }
    fun close() { closed=true; generation++ }
}
