package com.sdwinnie.airgesture.ai

import android.graphics.Bitmap
import com.sdwinnie.airgesture.model.HandPosture
import com.sdwinnie.airgesture.model.HandSide
import com.sdwinnie.airgesture.security.AirLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Free Google Gemini AI Co-Processor for Air Gesture (v6.0).
 *
 * Capabilities:
 * 1. Hybrid Edge-Cloud AI Co-Pilot:
 *    - 60 FPS real-time tracking runs 100% locally on device in volatile RAM (< 1% CPU).
 *    - Gemini AI runs asynchronously in the background on ambiguity, intent calibration,
 *      or cross-hand disambiguation, offloading deep semantic reasoning from local GPU/CPU.
 * 2. Energy & Quota Safe:
 *    - Strictly rate-limited to at most one background call every 2.5 - 3.5 seconds.
 *    - Operates with 0 network calls when no API key is provided or offline (100% local fallback).
 * 3. Uses official free Google AI Studio Gemini API (gemini-2.5-flash / gemini-1.5-flash).
 */
class GeminiAiCoProcessor {

    data class GeminiInferenceResult(
        val recognizedIntent: String,
        val confirmedHandSide: HandSide,
        val eyeEngagementConfidence: Float,
        val aiCommentary: String,
        val latencyMs: Long
    )

    data class TelemetryState(
        val isKeyConfigured: Boolean = false,
        val isInferring: Boolean = false,
        val lastSuccessTimeMs: Long = 0L,
        val lastInferenceSummary: String = "สแตนด์บาย (พร้อมช่วยเหลือ)"
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isBusy = false

    @Volatile
    private var lastInferenceTimeMs = 0L

    @Volatile
    var currentState = TelemetryState()
        private set

    var onStateChanged: ((TelemetryState) -> Unit)? = null

    fun release() {
        scope.cancel()
    }

    /**
     * Non-blocking request to Gemini AI Co-Processor to assist hand/eye disambiguation.
     * Rate-limited to max 1 call per 2800ms.
     */
    fun evaluateAmbiguityAsync(
        apiKey: String?,
        modelName: String = "gemini-2.5-flash",
        candidatePosture: HandPosture,
        observedHandSide: HandSide,
        leftEar: Float,
        rightEar: Float,
        isEyeLocked: Boolean,
        onResult: (GeminiInferenceResult?) -> Unit
    ) {
        val key = apiKey?.trim().orEmpty()
        if (key.isBlank()) {
            currentState = currentState.copy(
                isKeyConfigured = false,
                lastInferenceSummary = "100% Edge AI (ไม่มี API Key ทำงานบนเครื่องล้วน)"
            )
            onStateChanged?.invoke(currentState)
            onResult(null)
            return
        }

        val now = System.currentTimeMillis()
        if (isBusy || (now - lastInferenceTimeMs) < 2800L) {
            return
        }

        isBusy = true
        currentState = currentState.copy(isKeyConfigured = true, isInferring = true)
        onStateChanged?.invoke(currentState)

        scope.launch {
            val t0 = System.currentTimeMillis()
            val result = runCatching {
                queryGemini(
                    apiKey = key,
                    model = modelName.ifBlank { "gemini-2.5-flash" },
                    candidatePosture = candidatePosture,
                    observedHandSide = observedHandSide,
                    leftEar = leftEar,
                    rightEar = rightEar,
                    isEyeLocked = isEyeLocked
                )
            }.getOrNull()

            val dt = System.currentTimeMillis() - t0
            isBusy = false
            lastInferenceTimeMs = System.currentTimeMillis()

            if (result != null) {
                currentState = currentState.copy(
                    isInferring = false,
                    lastSuccessTimeMs = lastInferenceTimeMs,
                    lastInferenceSummary = "🤖 Gemini AI (${dt}ms): ${result.aiCommentary}"
                )
            } else {
                currentState = currentState.copy(
                    isInferring = false,
                    lastInferenceSummary = "🤖 Gemini AI: เชื่อมต่อไม่สำเร็จ (สลับใช้ระบบเครื่อง)"
                )
            }
            onStateChanged?.invoke(currentState)

            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    /**
     * Executes test ping to verify user's Google AI Studio Free Key.
     */
    fun testConnection(
        apiKey: String,
        modelName: String = "gemini-2.5-flash",
        onCompleted: (success: Boolean, message: String) -> Unit
    ) {
        scope.launch {
            try {
                val model = modelName.ifBlank { "gemini-2.5-flash" }
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                val conn = (url.openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6500
                    readTimeout = 6500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }

                val prompt = "Reply with exactly: {\"status\": \"OK\", \"engine\": \"AirGesture v6.0\"}"
                val json = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", prompt))
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.0)
                        put("maxOutputTokens", 40)
                    })
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(json.toString()) }

                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    AirLogger.d("GeminiCoProcessor", "Ping success: $body")
                    withContext(Dispatchers.Main) {
                        onCompleted(true, "✅ เชื่อมต่อ Gemini API สำเร็จ! พร้อมใช้งานเป็นผู้ช่วย AI")
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    AirLogger.w("GeminiCoProcessor", "Ping error code $code: $err")
                    withContext(Dispatchers.Main) {
                        onCompleted(false, "❌ รหัสตอบกลับ HTTP $code (โปรดตรวจสอบ API Key หรือ Quota)")
                    }
                }
                conn.disconnect()
            } catch (t: Throwable) {
                AirLogger.e("GeminiCoProcessor", "Connection test failed", t)
                withContext(Dispatchers.Main) {
                    onCompleted(false, "❌ การเชื่อมต่อล้มเหลว: ${t.message ?: "Network error"}")
                }
            }
        }
    }

    private fun queryGemini(
        apiKey: String,
        model: String,
        candidatePosture: HandPosture,
        observedHandSide: HandSide,
        leftEar: Float,
        rightEar: Float,
        isEyeLocked: Boolean
    ): GeminiInferenceResult? {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 5000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        val prompt = """
            Context: Android Air Gesture v6.0 touchless navigation co-processor.
            Vision telemetry:
            - Detected hand posture: ${candidatePosture.name} (${candidatePosture.thaiName})
            - Hand chirality: ${observedHandSide.name}
            - Left Eye EAR: $leftEar, Right Eye EAR: $rightEar
            - Eye tracking lock-on: $isEyeLocked
            
            Determine:
            1. Intent: SCROLL_DOWN, SCROLL_UP, SEND_HEART, QUICK_BACK, VOLUME_CHANGE, or IDLE.
            2. Confirmed hand: RIGHT, LEFT, or AUTO.
            3. Brief Thai confirmation summary (under 6 words).
            
            Respond ONLY with a JSON object:
            {
               "intent": "SCROLL_DOWN",
               "hand": "RIGHT",
               "eye_confidence": 0.95,
               "commentary": "เลื่อนหน้าจอลงสำเร็จ"
            }
        """.trimIndent()

        val json = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 120)
                put("responseMimeType", "application/json")
            })
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(json.toString()) }

        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            return null
        }

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return null
        if (parts.length() == 0) return null
        val text = parts.getJSONObject(0).optString("text", "")

        val parsed = JSONObject(text.trim())
        val intent = parsed.optString("intent", "IDLE")
        val handStr = parsed.optString("hand", "RIGHT")
        val handSide = when (handStr.uppercase()) {
            "LEFT" -> HandSide.LEFT
            "RIGHT" -> HandSide.RIGHT
            else -> HandSide.UNKNOWN
        }
        val eyeConf = parsed.optDouble("eye_confidence", 0.9).toFloat()
        val commentary = parsed.optString("commentary", "ผู้ช่วย AI พร้อมทำงาน")

        return GeminiInferenceResult(
            recognizedIntent = intent,
            confirmedHandSide = handSide,
            eyeEngagementConfidence = eyeConf,
            aiCommentary = commentary,
            latencyMs = 0L
        )
    }
}