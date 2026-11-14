package io.chagok.app.speech

import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
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
class SpeechInput(private val context: Context, private val log: (String) -> Unit, private val onAvailabilityChanged: () -> Unit = {}) {
    private val main = Handler(Looper.getMainLooper())
    private val pleos = PleosSpeechToText(context.applicationContext, Mode.ON_DEVICE)
    private var pleosReady = false
    private var recognizer: SpeechRecognizer? = null
    private var callbacks: SpeechCallbacks? = null
    private var lastText = ""
    private var session = 0
    private var released = false
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change -> if (change < 0) {
            val target = callbacks
            cancel()
            target?.onFailed("통화 또는 다른 오디오 사용으로 음성 입력을 중단했어요.")
        } }.build()

    private fun androidAvailable() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** 현재 엔진 이름. P0 확인 화면에 그대로 보여 준다 */
    var backend: String = "확인 중"
        private set

    private var pleosListener: ResultListener? = null
    private fun listenerFor(token: Int) = object : ResultListener {
        private fun active(block: () -> Unit) = ui { if (session == token && callbacks != null) block() }
        override fun onReady() = active { callbacks?.onState(ListenState.LISTENING) }
        override fun onStartedRecognition() = active { callbacks?.onState(ListenState.LISTENING) }
        override fun onUpdated(stt: String, completed: Boolean) = active {
            lastText = stt
            if (completed) finish(stt) else {
                callbacks?.onState(ListenState.RECOGNIZING)
                callbacks?.onPartial(stt)
            }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() = active { finish(lastText) }
        override fun onError(errorType: ErrorType) = active { fail(describe(errorType)) }
    }

    init {
        pleos.initialize(object : OnInitializeListener {
            override fun onSuccess() = ui {
                if (pleosReady || released) return@ui
                pleos.setLocale(Locale.KOREA)
                pleosReady = true
                backend = "Pleos STT · ON_DEVICE · ko-KR"
                log("stt ready: $backend")
                onAvailabilityChanged()
            }

            override fun onFailure(errorType: ErrorType) = ui {
                if (released) return@ui
                backend = if (androidAvailable()) "Android 온디바이스 인식" else "사용 가능한 로컬 엔진 없음"
                log("pleos stt init failed: $errorType, backend=$backend")
                onAvailabilityChanged()
            }
        })
    }

    fun isAvailable(): Boolean = !released && (pleosReady || androidAvailable())

    fun listen(callbacks: SpeechCallbacks) {
        cancel()
        this.callbacks = callbacks
        lastText = ""
        session++
        if (released) return fail("음성 엔진이 종료되었어요.")
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return fail("통화 또는 다른 오디오 사용 중이에요. 잠시 뒤 다시 시도해 주세요.")
        }
        callbacks.onState(ListenState.PREPARING)
        val current = session
        main.postDelayed({ if (session == current && this.callbacks != null) {
            val target = this.callbacks
            cancel()
            target?.onFailed("음성 입력 시간이 끝났어요. 다시 시작해 주세요.")
        } }, 60_000L)
        if (pleosReady) {
            pleosListener = listenerFor(current).also { pleos.addListener(it) }
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
        session++
        val had = callbacks
        callbacks = null
        pleosListener?.let { runCatching { pleos.removeListener(it) } }
        pleosListener = null
        if (pleosReady && had != null) runCatching { pleos.stop() }
        recognizer?.destroy()
        recognizer = null
        audio.abandonAudioFocusRequest(focus)
        had?.onState(ListenState.IDLE)
    }

    fun release() {
        released = true
        cancel()
        main.removeCallbacksAndMessages(null)
        runCatching {
            pleos.release()
        }
    }

    private fun finish(text: String) {
        val target = callbacks ?: return
        cancel()
        if (text.isBlank()) target.onFailed("말소리를 듣지 못했어요.") else target.onFinal(text)
    }

    private fun fail(reason: String) {
        val target = callbacks ?: return
        cancel()
        log("stt failed: $reason")
        target.onFailed(reason)
    }

    private fun listenAndroid() {
        if (Build.VERSION.SDK_INT < 31 || !androidAvailable()) return fail("사용 가능한 로컬 음성 엔진이 없어요. 정차 중 직접 입력해 주세요.")
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val token = session
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (session == token) callbacks?.onState(ListenState.LISTENING) }
            override fun onPartialResults(partialResults: Bundle?) {
                if (session != token) return
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                lastText = t
                callbacks?.onState(ListenState.RECOGNIZING)
                callbacks?.onPartial(t)
            }
            override fun onResults(results: Bundle) {
                if (session == token) finish(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onError(error: Int) { if (session == token) fail(androidError(error)) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (session == token) callbacks?.onState(ListenState.RECOGNIZING) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // 서버 STT로 넘기지 않는다
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
        ) }.onFailure { fail("로컬 음성 인식을 시작하지 못했어요. 직접 입력해 주세요.") }
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
