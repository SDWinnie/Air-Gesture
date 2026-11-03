package com.sdwinnie.airgesture.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.sdwinnie.airgesture.security.AirLogger
import java.util.Locale

/**
 * Experimental Voice Navigation & Speech Feedback Controller for Air Gesture (v6.0).
 *
 * Capabilities:
 * 1. Offline & Online Hybrid Voice Commands:
 *    - Recognizes continuous spoken commands in Thai and English:
 *      - "เลื่อนลง" / "scroll down"
 *      - "เลื่อนขึ้น" / "scroll up"
 *      - "กดใจ" / "ถูกใจ" / "like"
 *      - "ย้อนกลับ" / "back"
 *      - "เพิ่มเสียง" / "volume up"
 *      - "ลดเสียง" / "volume down"
 *      - "หยุด" / "เล่น" / "pause / play"
 * 2. TextToSpeech (TTS) Audible Confirmation:
 *    - Speaks natural Thai feedback when actions execute ("เลื่อนลงแล้วค่ะ", "กดใจเรียบร้อย", etc.).
 * 3. Acoustic Tone Feedback:
 *    - Instant tone beep confirms voice command capture.
 */
class AirVoiceController(
    private val context: Context,
    private val onCommandReceived: (VoiceCommand, String) -> Unit
) {

    enum class VoiceCommand(val thaiPhrase: String, val ttsFeedback: String) {
        SCROLL_DOWN("เลื่อนลง", "เลื่อนลงแล้วค่ะ"),
        SCROLL_UP("เลื่อนขึ้น", "เลื่อนขึ้นแล้วค่ะ"),
        SEND_HEART("กดใจ", "กดใจเรียบร้อยค่ะ"),
        QUICK_BACK("ย้อนกลับ", "ย้อนกลับแล้วค่ะ"),
        VOLUME_UP("เพิ่มเสียง", "เพิ่มเสียงค่ะ"),
        VOLUME_DOWN("ลดเสียง", "ลดเสียงค่ะ"),
        MEDIA_PLAY_PAUSE("เล่น/หยุด", "สลับการเล่นสื่อค่ะ"),
        APP_MENU("เมนูแอป", "เปิดวงกลมเลือกแอปค่ะ")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    private var toneGen: ToneGenerator? = null

    @Volatile
    var isListening = false
        private set

    @Volatile
    var isEnabled = false
        private set

    @Volatile
    var enableTtsFeedback = true

    init {
        runCatching {
            toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
        }
        initTts()
    }

    private fun initTts() {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val loc = Locale("th", "TH")
                val res = textToSpeech?.setLanguage(loc)
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) {
                    textToSpeech?.language = Locale.getDefault()
                    ttsReady = true
                }
                AirLogger.d("VoiceController", "TextToSpeech initialized (ready=$ttsReady)")
            }
        }
    }

    fun startListening() {
        if (!isEnabled) return
        mainHandler.post {
            ensureSpeechRecognizer()
            startListeningInternal()
        }
    }

    fun stopListening() {
        mainHandler.post {
            isListening = false
            runCatching {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (enabled) {
            startListening()
        } else {
            stopListening()
        }
    }

    fun speakFeedback(text: String) {
        if (!enableTtsFeedback) return
        mainHandler.post {
            playToneBeep()
            if (ttsReady && textToSpeech != null) {
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "air_voice_tts")
            }
        }
    }

    fun playToneBeep() {
        runCatching {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        }
    }

    private fun ensureSpeechRecognizer() {
        if (speechRecognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            AirLogger.w("VoiceController", "Speech recognition unavailable on device")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    // Schedule restart with gentle backoff if voice navigation is still enabled
                    if (isEnabled) {
                        mainHandler.postDelayed({
                            if (isEnabled) startListeningInternal()
                        }, 750L)
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        processRecognizedMatches(matches)
                    }
                    if (isEnabled) {
                        mainHandler.postDelayed({
                            if (isEnabled) startListeningInternal()
                        }, 300L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!partials.isNullOrEmpty()) {
                        processRecognizedMatches(partials, isPartial = true)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListeningInternal() {
        val recognizer = speechRecognizer ?: return
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            AirLogger.w("VoiceController", "RECORD_AUDIO permission missing")
            return
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            recognizer.startListening(intent)
            isListening = true
        } catch (t: Throwable) {
            AirLogger.e("VoiceController", "Failed to start listening", t)
        }
    }

    private var lastCommandFiredMs = 0L

    private fun processRecognizedMatches(matches: List<String>, isPartial: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastCommandFiredMs < 600L) return

        for (raw in matches) {
            val text = raw.lowercase().trim()

            val command = when {
                text.contains("เลื่อนลง") || text.contains("scroll down") || text.contains("ปัดลง") || text == "ลง" || text == "down" ->
                    VoiceCommand.SCROLL_DOWN

                text.contains("เลื่อนขึ้น") || text.contains("scroll up") || text.contains("ปัดขึ้น") || text == "ขึ้น" || text == "up" ->
                    VoiceCommand.SCROLL_UP

                text.contains("กดใจ") || text.contains("ถูกใจ") || text.contains("like") || text.contains("heart") || text.contains("หัวใจ") ->
                    VoiceCommand.SEND_HEART

                text.contains("ย้อนกลับ") || text.contains("กลับ") || text == "back" ->
                    VoiceCommand.QUICK_BACK

                text.contains("เพิ่มเสียง") || text.contains("ดังขึ้น") || text.contains("volume up") || text == "ดัง" ->
                    VoiceCommand.VOLUME_UP

                text.contains("ลดเสียง") || text.contains("เบาลง") || text.contains("volume down") || text == "เบา" ->
                    VoiceCommand.VOLUME_DOWN

                text.contains("หยุด") || text.contains("เล่น") || text.contains("pause") || text.contains("play") ->
                    VoiceCommand.MEDIA_PLAY_PAUSE

                text.contains("เลือกแอป") || text.contains("เมนูแอป") || text.contains("วงกลมแอป") || text == "menu" ->
                    VoiceCommand.APP_MENU

                else -> null
            }

            if (command != null) {
                lastCommandFiredMs = now
                AirLogger.d("VoiceController", "Matched VoiceCommand: ${command.name} from phrase: '$text'")
                speakFeedback(command.ttsFeedback)
                onCommandReceived(command, command.ttsFeedback)
                break
            }
        }
    }

    fun release() {
        stopListening()
        mainHandler.post {
            runCatching {
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
            runCatching {
                textToSpeech?.stop()
                textToSpeech?.shutdown()
                textToSpeech = null
            }
            runCatching {
                toneGen?.release()
                toneGen = null
            }
        }
    }
}