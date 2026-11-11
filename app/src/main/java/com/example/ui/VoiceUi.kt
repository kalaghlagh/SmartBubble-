package com.example.ui

import androidx.annotation.StringRes
import com.example.R
import com.example.core.voice.VoiceError

/** Voice conversation phase shown in the Chat UI. */
enum class VoicePhase {
    IDLE,
    /** Microphone open. */
    LISTENING,
    /** Speech ended; waiting for the final transcription. */
    RECOGNIZING,
    /** The default AI provider is answering the spoken request. */
    THINKING,
    /** The reply is being read aloud. */
    SPEAKING,
    /** Reading is paused and can be resumed. */
    PAUSED
}

@StringRes
fun voiceErrorStringRes(error: VoiceError): Int = when (error) {
    VoiceError.RECOGNITION_UNAVAILABLE -> R.string.voice_err_recognition_unavailable
    VoiceError.MIC_PERMISSION -> R.string.voice_err_permission
    VoiceError.NO_SPEECH -> R.string.voice_err_no_speech
    VoiceError.NETWORK -> R.string.voice_err_network
    VoiceError.LANGUAGE_UNAVAILABLE -> R.string.voice_err_language
    VoiceError.RECOGNIZER_BUSY -> R.string.voice_err_busy
    VoiceError.RECOGNITION_FAILED -> R.string.voice_err_recognition_failed
    VoiceError.TTS_UNAVAILABLE -> R.string.voice_err_tts_unavailable
    VoiceError.TTS_LANGUAGE_UNAVAILABLE -> R.string.voice_err_tts_language
    VoiceError.TTS_FAILED -> R.string.voice_err_tts_failed
}
