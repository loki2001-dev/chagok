package ai.chagok.core.pipeline

import ai.chagok.core.model.Observation
import ai.chagok.core.model.SourceStatus
import ai.chagok.core.model.VehicleSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicLong
import ai.chagok.core.Command

/**
 * 차량 값 버스. 밀리면 오래된 값부터 버린다(DROP_OLDEST): 잔량·주행거리는 최신 값이 중요하다.
 * SDK 콜백 스레드를 모르므로 tryEmit만 쓴다. replay가 없어 구독 전에 들어온 값은 버려진다.
 */
class ObservationBus(bufferCapacity: Int = 1024) {
    private val seq = AtomicLong(0)
    private val _events = MutableSharedFlow<Observation>(replay = 0, extraBufferCapacity = bufferCapacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Observation> = _events.asSharedFlow()
    val subscriptionCount: StateFlow<Int> get() = _events.subscriptionCount
    fun publish(o: Observation): Boolean = _events.tryEmit(o.copy(seq = seq.incrementAndGet()))
    /** 리플레이처럼 시작 순서를 보장해야 하는 Producer가 쓴다. */
    suspend fun awaitSubscriber() { subscriptionCount.first { it > 0 } }
}

/** 사용자 명령(음성·화면) 버스. 손실 없음, 순서 보장. */
class CommandBus {
    private val channel = Channel<Command>(Channel.UNLIMITED)
    val commands = channel.receiveAsFlow()
    fun send(c: Command) { channel.trySend(c) }
}

/**
 * 차량 값 Producer 경계. Pleos Vehicle 어댑터와 리플레이가 같은 인터페이스를 구현한다.
 * 어댑터는 콜백(onComplete/onFailed)에서 수신 시각을 찍어 publish만 한다. 실패는 status로 올린다.
 */
interface VehicleDataProducer {
    val name: String
    val status: StateFlow<Map<VehicleSignal, SourceStatus>>
    fun start(scope: CoroutineScope, bus: ObservationBus)
    fun stop()
}
