package ai.chagok.core.sim

import ai.chagok.core.model.Observation
import ai.chagok.core.model.SourceStatus
import ai.chagok.core.model.VehicleSignal
import ai.chagok.core.pipeline.ObservationBus
import ai.chagok.core.pipeline.VehicleDataProducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 기록된 차량 값을 리플레이한다. P0에서 에뮬레이터로 받은 로그를 이 형식으로 저장하면 테스트·시연 입력이 된다.
 * 한 줄: offsetMs,signal,value   signal = odo | fuel | soc,  value = 숫자 또는 null(미수신)
 * realtime=false면 지연 없이 모두 publish(테스트용). 구독자가 붙을 때까지 기다린 뒤 시작한다.
 */
class VehicleReplay(
    private val lines: List<String>,
    private val vehicleId: String,
    private val base: Instant,
    private val realtime: Boolean = false,
    override val name: String = "replay",
) : VehicleDataProducer {
    private val _status = MutableStateFlow(VehicleSignal.entries.associateWith { SourceStatus.NOT_STARTED })
    override val status: StateFlow<Map<VehicleSignal, SourceStatus>> = _status
    private var job: Job? = null

    val observations: List<Observation> by lazy { lines.mapNotNull { parse(it) } }

    override fun start(scope: CoroutineScope, bus: ObservationBus) {
        _status.value = VehicleSignal.entries.associateWith { SourceStatus.AVAILABLE }
        job = scope.launch {
            bus.awaitSubscriber()
            var prev = 0L
            for (o in observations) {
                if (realtime) {
                    val off = o.receivedAt.toEpochMilli() - base.toEpochMilli()
                    if (off > prev) delay(off - prev)
                    prev = off
                }
                bus.publish(o)
            }
        }
    }

    override fun stop() { job?.cancel(); _status.value = VehicleSignal.entries.associateWith { SourceStatus.NOT_STARTED } }

    private fun parse(line: String): Observation? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val p = t.split(",").map { it.trim() }
        if (p.size < 3) return null
        val off = p[0].toLongOrNull() ?: return null
        val signal = when (p[1]) { "odo" -> VehicleSignal.ODOMETER_KM; "fuel" -> VehicleSignal.FUEL_PERCENT; "soc" -> VehicleSignal.EV_SOC_PERCENT; else -> return null }
        val value = if (p[2] == "null") null else (p[2].toDoubleOrNull() ?: return null)
        return Observation(vehicleId, signal, value, base.plusMillis(off))
    }

    companion object {
        fun fromText(text: String, vehicleId: String, base: Instant, realtime: Boolean = false) = VehicleReplay(text.lines(), vehicleId, base, realtime)
    }
}
