package ai.chagok.core.voice

/** 음성 입력 상태. 듣는 중·인식 중·초안·저장·실패·취소를 구분해 보여 준다. 인식 완료(DRAFT)는 저장 완료(SAVED)가 아니다. */
enum class VoiceState { IDLE, LISTENING, RECOGNIZING, DRAFT, SAVED, FAILED, CANCELLED }

/** 실패 사유. 어느 것도 저장 성공으로 표시하지 않는다. 주행 중이면 정차 후 수동 입력이 보조 경로다. */
enum class VoiceFailure { PERMISSION_DENIED, NO_SPEECH, ENGINE_UNAVAILABLE, LANGUAGE_UNAVAILABLE, NETWORK, UNRECOGNIZED, NOT_APPLICABLE, DRIVING_RESTRICTED, OTHER }

data class VoiceStatus(
    val sessionId: String? = null,
    val state: VoiceState = VoiceState.IDLE,
    val partial: String? = null,
    val failure: VoiceFailure? = null,
    val entryId: String? = null,
)

/**
 * Pleos SpeechToText(ai.pleos.playground.stt) ErrorType 이름을 VoiceFailure로 옮긴다.
 * 어댑터는 SDK enum의 name을 넘긴다. 문서상 값이 늘 수 있으므로 모르는 값은 OTHER.
 * 앱은 ON_DEVICE 모드를 명시하므로 NETWORK 계열은 원칙적으로 나오지 않아야 한다(사용자 동의 없이 서버 STT로 바꾸지 않는다).
 */
object PleosSttErrors {
    fun map(errorTypeName: String): VoiceFailure = when (errorTypeName) {
        "PERMISSION_DENIED" -> VoiceFailure.PERMISSION_DENIED
        "TIMEOUT" -> VoiceFailure.NO_SPEECH
        "UNAVAILABLE_LANGUAGE" -> VoiceFailure.LANGUAGE_UNAVAILABLE
        "ON_DEVICE_ENGINE_ERROR", "NOT_SUPPORTED_API", "MIC_ERROR", "AUDIO_ERROR" -> VoiceFailure.ENGINE_UNAVAILABLE
        "NETWORK_ERROR", "SERVER_INTERNAL_ERROR", "UNAUTHENTICATED" -> VoiceFailure.NETWORK
        else -> VoiceFailure.OTHER
    }
}
