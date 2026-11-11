package com.example.core.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** What the voice engine is doing right now. Listening and speaking never overlap. */
enum class VoiceState {
    IDLE,
    /** Microphone open, waiting for / receiving speech. */
    LISTENING,
    /** Speech ended; the recognizer is producing the final transcription. */
    RECOGNIZING,
    /** Text-to-speech is reading a message aloud. */
    SPEAKING,
    /** Reading was paused by the user or by an audio interruption; it can be resumed. */
    PAUSED
}

enum class VoiceError {
    RECOGNITION_UNAVAILABLE,
    MIC_PERMISSION,
    NO_SPEECH,
    NETWORK,
    LANGUAGE_UNAVAILABLE,
    RECOGNIZER_BUSY,
    RECOGNITION_FAILED,
    TTS_UNAVAILABLE,
    TTS_LANGUAGE_UNAVAILABLE,
    TTS_FAILED
}

/**
 * Speech-to-text (Android [SpeechRecognizer]) and text-to-speech (Android [TextToSpeech]).
 *
 * All public functions must be called on the main thread; all [Listener] callbacks are
 * delivered on the main thread. Nothing here fabricates input or output: transcriptions come
 * only from the recognizer and speech only from the text passed to [speak].
 */
class VoiceAssistantManager(
    context: Context,
    private val listener: Listener
) : RecognitionListener {

    interface Listener {
        /** Final transcription of what the user said. */
        fun onTranscription(text: String)
        /** The message [messageId] was read aloud to the end (not called when stopped). */
        fun onSpeechCompleted(messageId: String)
        fun onVoiceError(error: VoiceError)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")
    /** Live (partial) transcription while listening. */
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _inputLevel = MutableStateFlow(0f)
    /** Real microphone level reported by the recognizer, 0..1. */
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private val _speakingMessageId = MutableStateFlow<String?>(null)
    /** Id of the message being read (speaking or paused), or null. */
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    // ------------------------------------------------------------------ speech-to-text

    private var speechRecognizer: SpeechRecognizer? = null

    /** Opens the microphone and recognizes speech in [languageTag] (BCP-47, e.g. "ar-SA"). */
    fun startListening(languageTag: String) {
        stopSpeaking()
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _state.value = VoiceState.IDLE
            listener.onVoiceError(VoiceError.RECOGNITION_UNAVAILABLE)
            return
        }
        releaseRecognizer()
        _partialText.value = ""
        _inputLevel.value = 0f
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer.setRecognitionListener(this)
            speechRecognizer = recognizer
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            }
            _state.value = VoiceState.LISTENING
            recognizer.startListening(intent)
        } catch (e: Exception) {
            releaseRecognizer()
            _state.value = VoiceState.IDLE
            listener.onVoiceError(VoiceError.RECOGNITION_FAILED)
        }
    }

    /** Closes the microphone and discards anything heard so far (nothing is transcribed or sent). */
    fun cancelListening() {
        val wasListening = _state.value == VoiceState.LISTENING || _state.value == VoiceState.RECOGNIZING
        releaseRecognizer()
        _partialText.value = ""
        _inputLevel.value = 0f
        if (wasListening) _state.value = VoiceState.IDLE
    }

    private fun releaseRecognizer() {
        speechRecognizer?.let { recognizer ->
            try {
                recognizer.setRecognitionListener(null)
                recognizer.cancel()
                recognizer.destroy()
            } catch (e: Exception) {
                // The recognition service may already be gone; nothing else to release.
            }
        }
        speechRecognizer = null
    }

    private fun finishListening() {
        releaseRecognizer()
        _inputLevel.value = 0f
        if (_state.value == VoiceState.LISTENING || _state.value == VoiceState.RECOGNIZING) {
            _state.value = VoiceState.IDLE
        }
    }

    // RecognitionListener (called on the main thread by the framework)

    override fun onReadyForSpeech(params: Bundle?) {
        _state.value = VoiceState.LISTENING
    }

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(rmsdB: Float) {
        _inputLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        if (_state.value == VoiceState.LISTENING) _state.value = VoiceState.RECOGNIZING
    }

    override fun onError(error: Int) {
        finishListening()
        _partialText.value = ""
        listener.onVoiceError(
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.MIC_PERMISSION
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError.NO_SPEECH
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> VoiceError.NETWORK
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError.RECOGNIZER_BUSY
                ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> VoiceError.LANGUAGE_UNAVAILABLE
                else -> VoiceError.RECOGNITION_FAILED
            }
        )
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty().trim()
        finishListening()
        _partialText.value = ""
        if (text.isEmpty()) listener.onVoiceError(VoiceError.NO_SPEECH) else listener.onTranscription(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty()
        if (text.isNotBlank()) _partialText.value = text
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ------------------------------------------------------------------ text-to-speech

    private enum class TtsStatus { NOT_STARTED, INITIALIZING, READY, FAILED }

    private class Speech(val messageId: String, val chunks: List<String>, val locales: List<Locale>) {
        /** Index of the chunk being spoken (or to speak next). */
        var position = 0
    }

    private var tts: TextToSpeech? = null
    private var ttsStatus = TtsStatus.NOT_STARTED
    private var current: Speech? = null
    /** Bumped whenever queued utterances become stale, so their late callbacks are ignored. */
    private var generation = 0
    private var focusRequest: AudioFocusRequest? = null

    private val speechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (Build.VERSION.SDK_INT >= 26) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A call, alarm or another app took the audio: pause instead of talking over it.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            main.post { pauseSpeaking() }
        }
    }

    /**
     * Reads [text] aloud for message [messageId], trying [locales] in order (most specific
     * first). Markdown is removed and the text is queued sentence by sentence, so nothing is cut
     * off by the engine's input limit. Any current speech or listening is stopped first.
     */
    fun speak(messageId: String, text: String, locales: List<Locale>) {
        cancelListening()
        stopSpeaking()
        val maxLength = minOf(TextToSpeech.getMaxSpeechInputLength(), MAX_CHUNK_LENGTH)
        val chunks = VoiceText.splitForSpeech(VoiceText.stripMarkdown(text), maxLength)
        if (chunks.isEmpty()) {
            listener.onSpeechCompleted(messageId)
            return
        }
        current = Speech(messageId, chunks, locales)
        _speakingMessageId.value = messageId
        _state.value = VoiceState.SPEAKING
        when (ttsStatus) {
            TtsStatus.READY -> startCurrentSpeech()
            TtsStatus.INITIALIZING -> Unit // started from the init callback
            TtsStatus.NOT_STARTED, TtsStatus.FAILED -> initTts()
        }
    }

    private fun initTts() {
        ttsStatus = TtsStatus.INITIALIZING
        tts = TextToSpeech(appContext) { status ->
            main.post {
                if (ttsStatus != TtsStatus.INITIALIZING) return@post // shut down meanwhile
                if (status == TextToSpeech.SUCCESS) {
                    ttsStatus = TtsStatus.READY
                    tts?.setAudioAttributes(speechAttributes)
                    tts?.setOnUtteranceProgressListener(progressListener)
                    if (current != null) startCurrentSpeech()
                } else {
                    ttsStatus = TtsStatus.FAILED
                    tts = null
                    failSpeech(VoiceError.TTS_UNAVAILABLE)
                }
            }
        }
    }

    private fun startCurrentSpeech() {
        val speech = current ?: return
        val engine = tts ?: return failSpeech(VoiceError.TTS_UNAVAILABLE)
        val languageSet = speech.locales.any { locale ->
            val result = try {
                engine.setLanguage(locale)
            } catch (e: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (!languageSet) return failSpeech(VoiceError.TTS_LANGUAGE_UNAVAILABLE)
        if (!requestAudioFocus()) return failSpeech(VoiceError.TTS_FAILED)
        enqueueFrom(speech)
    }

    private fun enqueueFrom(speech: Speech) {
        val engine = tts ?: return failSpeech(VoiceError.TTS_UNAVAILABLE)
        generation++
        _state.value = VoiceState.SPEAKING
        for (i in speech.position until speech.chunks.size) {
            val mode = if (i == speech.position) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = engine.speak(speech.chunks[i], mode, null, "$generation:$i")
            if (result != TextToSpeech.SUCCESS) return failSpeech(VoiceError.TTS_FAILED)
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            onUtterance(utteranceId) { speech, index -> speech.position = index }
        }

        override fun onDone(utteranceId: String?) {
            onUtterance(utteranceId) { speech, index ->
                speech.position = index + 1
                if (index >= speech.chunks.lastIndex) {
                    val finishedId = speech.messageId
                    clearSpeech()
                    listener.onSpeechCompleted(finishedId)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onUtterance(utteranceId) { _, _ -> failSpeech(VoiceError.TTS_FAILED) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onUtterance(utteranceId) { _, _ -> failSpeech(VoiceError.TTS_FAILED) }
        }

        /** Engine callbacks arrive on a binder thread: hop to main and drop stale utterances. */
        private fun onUtterance(utteranceId: String?, block: (Speech, Int) -> Unit) {
            val parts = utteranceId?.split(':') ?: return
            val utteranceGeneration = parts.getOrNull(0)?.toIntOrNull() ?: return
            val index = parts.getOrNull(1)?.toIntOrNull() ?: return
            main.post {
                val speech = current ?: return@post
                if (utteranceGeneration == generation && _state.value == VoiceState.SPEAKING) block(speech, index)
            }
        }
    }

    /** Pauses reading; [resumeSpeaking] continues from the sentence that was interrupted. */
    fun pauseSpeaking() {
        if (current == null || _state.value != VoiceState.SPEAKING) return
        generation++
        try {
            tts?.stop()
        } catch (e: Exception) {
            // Engine died: the paused state still lets the user resume or stop.
        }
        abandonAudioFocus()
        _state.value = VoiceState.PAUSED
    }

    fun resumeSpeaking() {
        val speech = current ?: return
        if (_state.value != VoiceState.PAUSED) return
        if (ttsStatus != TtsStatus.READY) return failSpeech(VoiceError.TTS_UNAVAILABLE)
        if (!requestAudioFocus()) return failSpeech(VoiceError.TTS_FAILED)
        enqueueFrom(speech)
    }

    /** Stops reading (speaking or paused) and forgets the position. */
    fun stopSpeaking() {
        if (current == null) return
        generation++
        try {
            tts?.stop()
        } catch (e: Exception) {
            // Nothing more to stop.
        }
        clearSpeech()
    }

    private fun clearSpeech() {
        current = null
        _speakingMessageId.value = null
        abandonAudioFocus()
        if (_state.value == VoiceState.SPEAKING || _state.value == VoiceState.PAUSED) {
            _state.value = VoiceState.IDLE
        }
    }

    private fun failSpeech(error: VoiceError) {
        generation++
        try {
            tts?.stop()
        } catch (e: Exception) {
            // Already failing; report the original error below.
        }
        clearSpeech()
        listener.onVoiceError(error)
    }

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(speechAttributes)
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
                .also { focusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Releases the microphone, the recognizer and the speech engine. */
    fun shutdown() {
        cancelListening()
        stopSpeaking()
        ttsStatus = TtsStatus.NOT_STARTED
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            // Engine already released.
        }
        tts = null
        main.removeCallbacksAndMessages(null)
        _state.value = VoiceState.IDLE
    }

    private companion object {
        /** Keeps utterances short so pause/resume restarts close to where it stopped. */
        const val MAX_CHUNK_LENGTH = 600

        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31+).
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
