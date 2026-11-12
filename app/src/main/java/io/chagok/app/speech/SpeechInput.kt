package io.chagok.app.speech

import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import ai.pleos.playground.stt.SpeechToText as PleosSpeechToText

/** 음성 세션 상태 (기획서 7장 '실패 처리': 듣는 중·인식 중·실패·취소를 구분한다) */
enum class ListenState(val label: String) {
    IDLE("대기"),
    PREPARING("준비 중"),
    LISTENING("듣는 중"),
    RECOGNIZING("인식 중"),
}

interface SpeechCallbacks {
    fun onState(state: ListenState)
    fun onPartial(text: String)

    /** 최종 인식 텍스트. 인식 완료는 저장 완료가 아니다 */
    fun onFinal(text: String)
    fun onFailed(reason: String)
}

/**
 * 음성 입력. 서버로 보내지 않는다.
 * 1순위 Pleos SpeechToText ON_DEVICE + ko-KR (HYBRID 기본값을 쓰지 않는다)
 * 2순위 Android 표준 SpeechRecognizer (온디바이스 엔진이 있을 때)
 * 세션은 사용자가 시작하고, [stop]으로 끝내거나 [cancel]로 버린다.
 */
class SpeechInput(private val context: Context, private val log: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val pleos = PleosSpeechToText(context.applicationContext, Mode.ON_DEVICE)
    private var pleosReady = false
    private var recognizer: SpeechRecognizer? = null
    private var callbacks: SpeechCallbacks? = null
    private var lastText = ""
    private var session = 0

    /** 현재 엔진 이름. P0 확인 화면에 그대로 보여 준다 */
    var backend: String = "확인 중"
        private set

    private val pleosListener = object : ResultListener {
        override fun onReady() = ui { callbacks?.onState(ListenState.LISTENING) }
        override fun onStartedRecognition() = ui { callbacks?.onState(ListenState.LISTENING) }
        override fun onUpdated(stt: String, completed: Boolean) = ui {
            lastText = stt
            if (completed) finish(stt) else {
                callbacks?.onState(ListenState.RECOGNIZING)
                callbacks?.onPartial(stt)
            }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() = ui { if (callbacks != null) finish(lastText) }
        override fun onError(errorType: ErrorType) = ui { fail(describe(errorType)) }
    }

    init {
        pleos.initialize(object : OnInitializeListener {
            override fun onSuccess() = ui {
                if (pleosReady) return@ui
                pleos.setLocale(Locale.KOREA)
                pleos.addListener(pleosListener)
                pleosReady = true
                backend = "Pleos STT · ON_DEVICE · ko-KR"
                log("stt ready: $backend")
            }

            override fun onFailure(errorType: ErrorType) = ui {
                backend = if (SpeechRecognizer.isRecognitionAvailable(context)) "Android SpeechRecognizer" else "사용 가능한 엔진 없음"
                log("pleos stt init failed: $errorType, backend=$backend")
            }
        })
    }

    fun isAvailable(): Boolean = pleosReady || SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(callbacks: SpeechCallbacks) {
        cancel()
        this.callbacks = callbacks
        lastText = ""
        session++
        callbacks.onState(ListenState.PREPARING)
        if (pleosReady) {
            runCatching { pleos.request() }.onFailure { fail("음성 인식을 시작하지 못했어요: ${it.message}") }
            return
        }
        listenAndroid()
    }

    /**
     * 말하기를 마쳤다. 지금까지 인식한 텍스트로 끝낸다.
     * 말소리가 없으면 엔진이 종료 이벤트를 보내지 않을 수 있어(에뮬레이터 확인), 잠시 뒤 직접 끝낸다.
     */
    fun stop() {
        if (pleosReady) runCatching { pleos.stop() }
        recognizer?.stopListening()
        val current = session
        main.postDelayed({ if (session == current && callbacks != null) finish(lastText) }, STOP_GRACE_MS)
    }

    /** 세션을 버린다. 아무것도 전달하지 않는다 */
    fun cancel() {
        val had = callbacks
        callbacks = null
        if (pleosReady) runCatching { pleos.stop() }
        recognizer?.destroy()
        recognizer = null
        had?.onState(ListenState.IDLE)
    }

    fun release() {
        cancel()
        runCatching {
            pleos.removeListener(pleosListener)
            pleos.release()
        }
    }

    private fun finish(text: String) {
        val target = callbacks ?: return
        callbacks = null
        target.onState(ListenState.IDLE)
        if (text.isBlank()) target.onFailed("말소리를 듣지 못했어요.") else target.onFinal(text)
    }

    private fun fail(reason: String) {
        val target = callbacks ?: return
        callbacks = null
        log("stt failed: $reason")
        target.onState(ListenState.IDLE)
        target.onFailed(reason)
    }

    private fun listenAndroid() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return fail("사용 가능한 음성 인식 엔진이 없어요. 정차 중 직접 입력해 주세요.")
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { callbacks?.onState(ListenState.LISTENING) }
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                lastText = t
                callbacks?.onState(ListenState.RECOGNIZING)
                callbacks?.onPartial(t)
            }
            override fun onResults(results: Bundle) =
                finish(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            override fun onError(error: Int) = fail(androidError(error))
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { callbacks?.onState(ListenState.RECOGNIZING) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // 서버 STT로 넘기지 않는다
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
        )
    }

    private companion object {
        const val STOP_GRACE_MS = 2_000L
    }

    private fun ui(block: () -> Unit) {
        main.post(block)
    }

    private fun describe(e: ErrorType): String = when (e) {
        ErrorType.PERMISSION_DENIED -> "음성 인식 권한이 없어요."
        ErrorType.MIC_ERROR -> "마이크를 쓸 수 없어요. 개인정보 보호 설정의 마이크 접근을 확인해 주세요."
        ErrorType.ON_DEVICE_ENGINE_ERROR -> "차량 음성 엔진에 오류가 있어요."
        ErrorType.UNAVAILABLE_LANGUAGE -> "한국어 인식을 쓸 수 없어요."
        ErrorType.TIMEOUT -> "시간 안에 인식하지 못했어요."
        else -> "음성 인식 실패($e)"
    }

    private fun androidError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리를 듣지 못했어요."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 없어요."
        SpeechRecognizer.ERROR_AUDIO -> "마이크를 쓸 수 없어요."
        else -> "음성 인식 실패(android $code)"
    }
}
