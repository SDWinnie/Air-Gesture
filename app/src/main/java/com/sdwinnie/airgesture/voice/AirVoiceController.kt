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
        SEND_HEART("กดใจ", "ส่งหัวใจเรียบร้อยค่ะ"),
        QUICK_BACK("ย้อนกลับ", "ย้อนกลับแล้วค่ะ"),
        GO_HOME("หน้าแรก", "กลับหน้าหลักแล้วค่ะ"),
        OPEN_RECENTS("สลับแอป", "เปิดหน้าสลับแอปค่ะ"),
        VOLUME_UP("เพิ่มเสียง", "เพิ่มเสียงค่ะ"),
        VOLUME_DOWN("ลดเสียง", "ลดเสียงค่ะ"),
        VOLUME_MUTE("ปิดเสียง", "ปิดเสียงแล้วค่ะ"),
        MEDIA_PLAY_PAUSE("เล่น/หยุด", "สลับการเล่นสื่อค่ะ"),
        APP_MENU("เมนูแอป", "เปิดวงกลมเลือกแอปค่ะ"),
        TAKE_SCREENSHOT("แคปหน้าจอ", "บันทึกภาพหน้าจอแล้วค่ะ"),
        LOCK_HAND("ล็อกมือ", "สลับโหมดล็อกมือค่ะ")
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

    @Volatile
    private var isTtsSpeaking = false

    @Volatile
    private var ttsFinishedTimeMs = 0L

    var onAppLaunchRequested: ((appPackageOrAction: String, appName: String) -> Unit)? = null

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
                textToSpeech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isTtsSpeaking = true
                    }
                    override fun onDone(utteranceId: String?) {
                        isTtsSpeaking = false
                        ttsFinishedTimeMs = System.currentTimeMillis()
                    }
                    override fun onError(utteranceId: String?) {
                        isTtsSpeaking = false
                        ttsFinishedTimeMs = System.currentTimeMillis()
                    }
                })
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "th-TH")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "th-TH")
                putExtra("android.speech.extra.ADDITIONAL_LANGUAGES", arrayOf("en-US", "th-TH"))
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra("android.speech.extra.DICTATION_MODE", true)
            }
            recognizer.startListening(intent)
            isListening = true
        } catch (t: Throwable) {
            AirLogger.e("VoiceController", "Failed to start listening", t)
        }
    }

    private var lastCommandFiredMs = 0L

    companion object {
        private val TONE_MARKS_REGEX = Regex("[\u0E48\u0E49\u0E4A\u0E4B\u0E4C\u0E47]")

        fun normalizeAcousticText(input: String): String {
            return input.lowercase()
                .replace(TONE_MARKS_REGEX, "")
                .replace(Regex("[\\s\\p{Punct}]+"), "")
        }

        private val COMMAND_KEYWORDS: List<Pair<VoiceCommand, List<String>>> = listOf(
            VoiceCommand.SCROLL_DOWN to listOf("เลื่อนลง", "เลือนลง", "ปัดลง", "รูดลง", "scrolldown", "scroll down", "ลง", "สโครลดาวน์"),
            VoiceCommand.SCROLL_UP to listOf("เลื่อนขึ้น", "เลือนขึ้น", "ปัดขึ้น", "รูดขึ้น", "scrollup", "scroll up", "ขึ้น", "สโครลอัพ"),
            VoiceCommand.SEND_HEART to listOf("กดใจ", "กอดใจ", "ถูกใจ", "กดไลค์", "ส่งใจ", "หัวใจ", "like", "heart", "love", "ไลค์"),
            VoiceCommand.QUICK_BACK to listOf("ย้อนกลับ", "ยอนกลับ", "กลับไป", "ย้อน", "กลับ", "แบ็ค", "back"),
            VoiceCommand.GO_HOME to listOf("หน้าแรก", "หน้าหลัก", "โฮม", "กลับหน้าหลัก", "home", "desktop"),
            VoiceCommand.OPEN_RECENTS to listOf("สลับแอป", "เปิดแอป", "แอปทั้งหมด", "แท็บ", "recent", "recents", "overview"),
            VoiceCommand.VOLUME_UP to listOf("เพิ่มเสียง", "เพิมเสียง", "ดังขึ้น", "เร่งเสียง", "volumeup", "volume up", "ดัง"),
            VoiceCommand.VOLUME_DOWN to listOf("ลดเสียง", "เบาลง", "หรี่เสียง", "volumedown", "volume down", "เบา"),
            VoiceCommand.VOLUME_MUTE to listOf("ปิดเสียง", "เงียบ", "มิ้ว", "มิวท์", "mute", "silence"),
            VoiceCommand.MEDIA_PLAY_PAUSE to listOf("เล่น", "หยุด", "เพลง", "พอส", "pause", "play", "เปิดเพลง", "หยุดเพลง"),
            VoiceCommand.APP_MENU to listOf("เลือกแอป", "เมนูแอป", "วงกลมแอป", "วงล้อ", "menu", "radial"),
            VoiceCommand.TAKE_SCREENSHOT to listOf("แคปหน้าจอ", "แคปจอ", "ถ่ายหน้าจอ", "บันทึกจอ", "screenshot", "capture"),
            VoiceCommand.LOCK_HAND to listOf("ล็อกมือ", "ปลดล็อกมือ", "สลับมือ", "ล็อก", "lock", "switch hand")
        )

        fun matchVoiceCommand(rawText: String): VoiceCommand? {
            val text = rawText.lowercase().trim()
            val normText = normalizeAcousticText(text)

            var bestCmd: VoiceCommand? = null
            var bestMatchLen = 0

            for ((cmd, keywords) in COMMAND_KEYWORDS) {
                for (kw in keywords) {
                    val normKw = normalizeAcousticText(kw)
                    if (text == kw || normText == normKw) {
                        return cmd // Exact match wins immediately
                    }
                    if (text.contains(kw) || normText.contains(normKw)) {
                        val matchLen = kw.length
                        if (matchLen > bestMatchLen) {
                            bestMatchLen = matchLen
                            bestCmd = cmd
                        }
                    }
                }
            }
            return bestCmd
        }
    }

    private fun processRecognizedMatches(matches: List<String>, isPartial: Boolean = false) {
        val now = System.currentTimeMillis()

        // 1. Acoustic Echo Suppression: Ignore mic input while TTS is actively speaking or dissipating
        if (isTtsSpeaking || (now - ttsFinishedTimeMs) < 850L) {
            AirLogger.d("VoiceController", "Suppressed voice input during TTS feedback to prevent double-trigger echo")
            return
        }

        // 2. Strict debounce cooldown (1400ms) to prevent double firing
        if (now - lastCommandFiredMs < 1400L) {
            AirLogger.d("VoiceController", "Voice debounce active: ${now - lastCommandFiredMs}ms < 1400ms")
            return
        }

        // 3. Never execute discrete actions on partial results
        if (isPartial) return

        // 4. System App & Action Controller: "เปิด YouTube", "เปิด Chrome", "เปิด กล้อง", "เปิด การตั้งค่า", etc.
        for (raw in matches) {
            val text = raw.lowercase().trim()
            val norm = normalizeAcousticText(text)
            val appTarget = when {
                norm.contains("เปิดยูทูป") || norm.contains("เปิดyoutube") || text.contains("open youtube") ->
                    "com.google.android.youtube" to "YouTube"
                norm.contains("เปิดติ๊กต๊อก") || norm.contains("เปิดtiktok") || text.contains("open tiktok") ->
                    "com.zhiliaoapp.musically" to "TikTok"
                norm.contains("เปิดเฟส") || norm.contains("เปิดfacebook") || text.contains("open facebook") ->
                    "com.facebook.katana" to "Facebook"
                norm.contains("เปิดไลน์") || norm.contains("เปิดline") || text.contains("open line") ->
                    "jp.naver.line.android" to "LINE"
                norm.contains("เปิดโครม") || norm.contains("เปิดchrome") || norm.contains("เปิดเว็บ") || text.contains("open chrome") ->
                    "com.android.chrome" to "Google Chrome"
                norm.contains("เปิดกล้อง") || text.contains("open camera") ->
                    "android.media.action.IMAGE_CAPTURE" to "กล้องถ่ายภาพ"
                norm.contains("เปิดการตั้งค่า") || norm.contains("เปิดตั้งค่า") || text.contains("open settings") ->
                    "android.settings.SETTINGS" to "การตั้งค่าระบบ"
                else -> null
            }
            if (appTarget != null) {
                lastCommandFiredMs = now
                val (pkg, name) = appTarget
                val feedback = "กำลังเปิด $name ค่ะ"
                AirLogger.d("VoiceController", "System Voice App Launched: $name ($pkg)")
                speakFeedback(feedback)
                onAppLaunchRequested?.invoke(pkg, name)
                return
            }
        }

        // 5. Match the 13 canonical touchless navigation commands
        for (raw in matches) {
            val command = matchVoiceCommand(raw)
            if (command != null) {
                lastCommandFiredMs = now
                AirLogger.d("VoiceController", "Acoustic AI Matched: ${command.name} from phrase: '$raw'")
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