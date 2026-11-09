package com.example.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class VoiceState {
    IDLE,
    LISTENING,
    RECOGNIZED,
    PROCESSING,
    RESPONDING,
    COMPLETED,
    ERROR
}

class VoiceAssistantManager(
    private val context: Context,
    private val onFinalSpeech: (String) -> Unit
) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _decibelLevel = MutableStateFlow(0f)
    val decibelLevel: StateFlow<Float> = _decibelLevel.asStateFlow()

    init {
        initTts()
    }

    private fun initTts() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _voiceState.value = VoiceState.RESPONDING
                    }
                    override fun onDone(utteranceId: String?) {
                        _voiceState.value = VoiceState.COMPLETED
                    }
                    override fun onError(utteranceId: String?) {
                        _voiceState.value = VoiceState.IDLE
                    }
                })
            }
        }
    }

    fun startListening(languageCode: String = "en-US") {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _voiceState.value = VoiceState.ERROR
            return
        }

        stopSpeaking()
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(this@VoiceAssistantManager)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        _partialText.value = ""
        _voiceState.value = VoiceState.LISTENING
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            _voiceState.value = VoiceState.ERROR
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            // ignore
        }
        if (_voiceState.value == VoiceState.LISTENING) {
            _voiceState.value = VoiceState.IDLE
        }
    }

    fun speak(text: String, language: Locale = Locale.getDefault()) {
        if (!isTtsReady || text.isBlank()) return
        textToSpeech?.language = language
        _voiceState.value = VoiceState.RESPONDING
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "SmartBubbleUtterance")
    }

    fun stopSpeaking() {
        if (textToSpeech?.isSpeaking == true) {
            textToSpeech?.stop()
        }
    }

    fun shutdown() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    // RecognitionListener
    override fun onReadyForSpeech(params: Bundle?) {
        _voiceState.value = VoiceState.LISTENING
    }

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(rmsdB: Float) {
        _decibelLevel.value = (rmsdB.coerceIn(0f, 10f) / 10f)
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        _voiceState.value = VoiceState.PROCESSING
    }

    override fun onError(error: Int) {
        _voiceState.value = VoiceState.IDLE
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull().orEmpty().trim()
        if (text.isNotBlank()) {
            _partialText.value = text
            _voiceState.value = VoiceState.RECOGNIZED
            onFinalSpeech(text)
        } else {
            _voiceState.value = VoiceState.IDLE
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull().orEmpty()
        if (text.isNotBlank()) {
            _partialText.value = text
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
