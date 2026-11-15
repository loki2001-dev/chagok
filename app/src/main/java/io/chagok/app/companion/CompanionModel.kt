package io.chagok.app.companion

import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

enum class CarSkin { RACER, HYUNDAI }
enum class Scenery { CITY, COUNTRY }
data class Encounter(val id: String, val name: String, val scenery: Scenery, val left: Boolean, val line: String)
object Encounters {
    val all = listOf(
        Encounter("calico", "골목의 삼색이", Scenery.CITY, true, "작은 발걸음이 골목으로 사라졌어요."),
        Encounter("cyclist", "노란 헬멧의 소녀", Scenery.CITY, false, "빨간 자전거가 경쾌하게 지나갔어요."),
        Encounter("corgi", "산책 대장 코기", Scenery.CITY, true, "짧은 다리로 앞장서는 산책 대장."),
        Encounter("courier", "파란 가방 배달원", Scenery.CITY, false, "바쁜 길에도 손을 흔들어 줬어요."),
        Encounter("florist", "꽃을 싣는 오후", Scenery.CITY, true, "꽃수레가 골목에 색을 남겼어요."),
        Encounter("umbrella", "빨간 우산의 인사", Scenery.CITY, false, "빨간 우산 아래에서 건넨 인사."),
        Encounter("busker", "골목 기타리스트", Scenery.CITY, true, "골목 모퉁이의 작은 연주."),
        Encounter("ducklings", "오리 가족 나들이", Scenery.COUNTRY, true, "엄마 뒤로 노란 발걸음이 쪼르르."),
        Encounter("farmer", "귤 한 상자", Scenery.COUNTRY, false, "귤 한 상자와 따뜻한 손인사."),
        Encounter("painter", "풍경을 담는 화가", Scenery.COUNTRY, true, "지나온 풍경을 그리고 있어요."),
    )
    fun find(id: String) = all.firstOrNull { it.id == id }
}
data class EncounterRecord(val id: String, val count: Int, val first: Instant, val last: Instant)
data class EncounterPass(val scene: Encounter, val token: String = UUID.randomUUID().toString(),
    var y: Float = -480f, var seenSeconds: Float = 0f, var recorded: Boolean = false)

/** Runs only while the scene is visible and motion is allowed. Never awards unseen/background events. */
class EncounterDirector(private val random: () -> Double = { Random.nextDouble() }) {
    var viewportHeight = 500f
    var scenery = Scenery.CITY
    var active: EncounterPass? = null
    var lastId: String? = null
    var remainingSeconds = 180f
    fun choose(seen: Set<String>): Encounter {
        val setting = if (random() < .75) Scenery.CITY else Scenery.COUNTRY
        val eligible = Encounters.all.filter { it.scenery == setting && it.id != lastId }
        val fresh = eligible.filter { it.id !in seen }.ifEmpty { eligible }
        return fresh[(random() * fresh.size).toInt().coerceIn(fresh.indices)]
    }
    fun begin(scene: Encounter, y: Float = -480f) {
        active = EncounterPass(scene, y = y); scenery = scene.scenery; lastId = scene.id
    }
    fun step(dt: Float, pixels: Float, moving: Boolean, allowed: Boolean, question: Boolean,
        seen: Set<String>, collect: (EncounterPass) -> Boolean) {
        if (!allowed || !moving || dt <= 0f) return
        val event = active
        if (event == null) {
            if (!question) remainingSeconds -= dt
            if (remainingSeconds <= 0 && !question) begin(choose(seen))
            return
        }
        event.y += pixels.coerceAtLeast(0f)
        val personY = event.y + 235f
        if (personY in 0f..(viewportHeight - 50f)) event.seenSeconds += dt
        if (!event.recorded && personY >= viewportHeight * .61f + 10f && event.seenSeconds >= 1.5f) event.recorded = collect(event)
        if (event.y >= viewportHeight) { active = null; remainingSeconds = (180 + random() * 240).toFloat() }
    }
}

data class MotionSnapshot(
    val speedMps: Float? = null, val acceleration: Float = 0f, val turn: Int? = null,
    val rain: Float = 0f, val wiperMode: String? = null, val wiperOn: Boolean? = null,
    val rainConfirmed: Boolean = false, val restricted: Boolean = true, val animationAllowed: Boolean = false, val parkGear: Boolean? = null,
    val forwardGear: Boolean = false,
)

class SpeedFilter {
    private var value: Float? = null
    private var at: Long? = null
    private var acceleration = 0f
    fun accept(now: Long, mps: Float?) {
        if (mps == null || !mps.isFinite() || abs(mps) > 150f) { value = null; at = null; acceleration = 0f; return }
        val previous = value; val elapsed = at?.let { (now - it) / 1000f }
        val speed = abs(mps)
        if (previous != null && elapsed != null && elapsed in .02f..2.5f) {
            val raw = ((speed - previous) / elapsed).coerceIn(-8f, 8f)
            acceleration += (raw - acceleration) * (elapsed / (.35f + elapsed))
        } else acceleration = 0f
        value = speed; at = now
    }
    fun speed(now: Long) = value.takeIf { at != null && now - at!! in 0..3000 }
    fun acceleration(now: Long) = if (speed(now) == null) 0f else acceleration
}

/** Visual inference only. Monotonic time; no precipitation claim is saved to the diary. */
class WiperRainPolicy {
    private var since: Long? = null
    private var offSince: Long? = null
    private var sampleAt: Long? = null
    private var lastTick: Long? = null
    private var intermittentObserved = false
    private var mode: String? = null
    private var strength = .55f
    var confirmed = false; private set
    var intensity = 0f; private set

    fun sample(now: Long, mode: String?, on: Boolean?, supported: List<String>) {
        if (sampleAt?.let { now - it > 5000 || now < it } == true) resetEvidence()
        sampleAt = now
        if (mode == null || on == null) { resetEvidence(); this.mode = null; return }
        val intermittent = mode.startsWith("INTERMITTENT_")
        if (!intermittent || this.mode?.startsWith("INTERMITTENT_") != true) intermittentObserved = false
        this.mode = mode
        if (intermittent && on) intermittentObserved = true
        val active = mode !in listOf("OFF", "MIST", "SERVICE", "OTHER") && (on || intermittent && intermittentObserved)
        if (active) {
            if (since == null) since = now
            offSince = null
            if (now - since!! >= 60_000) confirmed = true
            strength = strength(mode, supported)
        } else {
            since = null
            if (offSince == null) offSince = now
            if (now - offSince!! >= 30_000) confirmed = false
        }
    }
    fun tick(now: Long): Float {
        val dt = lastTick?.let { ((now - it) / 1000f).coerceIn(0f, .5f) } ?: 0f; lastTick = now
        if (sampleAt == null || now - sampleAt!! !in 0..5000) resetEvidence()
        val target = if (confirmed) strength else 0f
        val step = dt / if (target < intensity) 3f else 2f
        intensity += (target - intensity).coerceIn(-step, step)
        return intensity
    }
    private fun resetEvidence() { since = null; offSince = null; confirmed = false; intermittentObserved = false }
    companion object {
        fun strength(mode: String, supported: List<String>): Float {
            val family = when { mode.startsWith("CONTINUOUS_") -> "CONTINUOUS_"; mode.startsWith("INTERMITTENT_") -> "INTERMITTENT_"; else -> return .55f }
            val values = supported.filter { it.startsWith(family) }.sorted()
            val index = values.indexOf(mode)
            if (index < 0) return .55f
            val fraction = if (values.size > 1) index.toFloat() / (values.size - 1) else .5f
            return if (family == "CONTINUOUS_") .45f + fraction * .55f else .18f + fraction * .22f
        }
    }
}
