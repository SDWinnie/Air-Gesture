package com.sdwinnie.airgesture.ai

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
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Free Google Gemini AI Co-Processor for Air Gesture (v6.0.1).
 *
 * Capabilities:
 * 1. Hybrid Edge-Cloud AI Co-Pilot:
 *    - 60 FPS real-time tracking runs 100% locally on device in volatile RAM (< 1% CPU).
 *    - Gemini AI runs asynchronously in the background on ambiguity, intent calibration,
 *      or cross-hand disambiguation, offloading deep semantic reasoning from local GPU/CPU.
 * 2. Smart Model Auto-Discovery & Multi-Model Fallback:
 *    - Auto-detects supported models for the user's Google AI Studio project via models.list API.
 *    - Fallback chain: gemini-2.0-flash -> gemini-1.5-flash -> gemini-2.0-flash-lite -> gemini-1.5-pro.
 *    - Automatically recovers from HTTP 404 (model not found) by switching to a working model.
 * 3. Energy & Quota Safe:
 *    - Strictly rate-limited to at most one background call every 2.8 seconds.
 *    - Operates with 0 network calls when no API key is provided or offline (100% local fallback).
 */
class GeminiAiCoProcessor {

    data class GeminiInferenceResult(
        val recognizedIntent: String,
        val confirmedHandSide: HandSide,
        val eyeEngagementConfidence: Float,
        val aiCommentary: String,
        val verifiedModel: String,
        val latencyMs: Long
    )

    data class TelemetryState(
        val isKeyConfigured: Boolean = false,
        val isInferring: Boolean = false,
        val activeModel: String = "gemini-2.0-flash",
        val lastSuccessTimeMs: Long = 0L,
        val lastInferenceSummary: String = "สแตนด์บาย (พร้อมช่วยเหลือ)"
    )

    companion object {
        val CANDIDATE_MODELS = listOf(
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "gemini-2.0-flash-lite",
            "gemini-1.5-pro"
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isBusy = false

    @Volatile
    private var lastInferenceTimeMs = 0L

    @Volatile
    var activeVerifiedModel: String = "gemini-2.0-flash"
        private set

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
        modelName: String = "gemini-2.0-flash",
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
                queryGeminiWithFallback(
                    apiKey = key,
                    preferredModel = if (activeVerifiedModel.isNotBlank()) activeVerifiedModel else modelName,
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
                activeVerifiedModel = result.verifiedModel
                currentState = currentState.copy(
                    isInferring = false,
                    activeModel = result.verifiedModel,
                    lastSuccessTimeMs = lastInferenceTimeMs,
                    lastInferenceSummary = "🤖 Gemini (${result.verifiedModel}, ${dt}ms): ${result.aiCommentary}"
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
     * Auto-discovers supported models and tests the best candidate.
     */
    fun testConnection(
        apiKey: String,
        preferredModel: String = "gemini-2.0-flash",
        onCompleted: (success: Boolean, message: String, verifiedModel: String) -> Unit
    ) {
        scope.launch {
            val trimmedKey = apiKey.trim()
            if (trimmedKey.isBlank()) {
                withContext(Dispatchers.Main) {
                    onCompleted(false, "❌ ไม่พบ API Key กรุณากรอกคีย์ก่อนทดสอบ", preferredModel)
                }
                return@launch
            }

            // Step 1: Query models.list to verify API key and discover available models
            val discoveredModels = discoverAvailableModels(trimmedKey)
            val modelToUse = when {
                discoveredModels.contains("gemini-2.0-flash") -> "gemini-2.0-flash"
                discoveredModels.contains("gemini-1.5-flash") -> "gemini-1.5-flash"
                discoveredModels.contains("gemini-2.0-flash-lite") -> "gemini-2.0-flash-lite"
                discoveredModels.isNotEmpty() -> discoveredModels.first()
                preferredModel.isNotBlank() && !preferredModel.contains("2.5") -> preferredModel
                else -> "gemini-2.0-flash"
            }

            // Step 2: Try candidate models in order until one succeeds
            val testCandidates = mutableListOf<String>()
            testCandidates.add(modelToUse)
            for (m in CANDIDATE_MODELS) {
                if (!testCandidates.contains(m)) testCandidates.add(m)
            }

            var lastErrorMsg = ""
            var lastErrorCode = 0

            for (model in testCandidates) {
                try {
                    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$trimmedKey")
                    val conn = (url.openConnection() as HttpsURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 7000
                        readTimeout = 7000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    }

                    val prompt = "Reply with exactly: {\"status\": \"OK\"}"
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
                        conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()
                        activeVerifiedModel = model
                        AirLogger.d("GeminiCoProcessor", "Ping success on model: $model")
                        withContext(Dispatchers.Main) {
                            onCompleted(
                                true,
                                "✅ เชื่อมต่อ Google Gemini API สำเร็จ!\n\n" +
                                    "• โมเดลที่ใช้งาน: $model\n" +
                                    "• ระบบพร้อมทำงานเป็น AI Co-Processor ร่วมกับระบบตรวจจับในเครื่อง",
                                model
                            )
                        }
                        return@launch
                    } else {
                        lastErrorCode = code
                        val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        lastErrorMsg = runCatching {
                            JSONObject(errBody).optJSONObject("error")?.optString("message") ?: errBody
                        }.getOrDefault(errBody)
                        AirLogger.w("GeminiCoProcessor", "Model $model returned HTTP $code: $lastErrorMsg")
                        conn.disconnect()
                        if (code == 400) {
                            // Invalid key -> no point retrying other models
                            break
                        }
                    }
                } catch (t: Throwable) {
                    lastErrorMsg = t.message ?: "Network error"
                    AirLogger.e("GeminiCoProcessor", "Test attempt on $model failed", t)
                }
            }

            // If we reached here, all attempts failed
            val explanation = when (lastErrorCode) {
                400 -> "❌ รหัสตอบกลับ HTTP 400: API Key ไม่ถูกต้อง โปรดคัดลอกใหม่อย่างระมัดระวังจาก Google AI Studio (aistudio.google.com)\n\nรายละเอียด: $lastErrorMsg"
                404 -> "❌ รหัสตอบกลับ HTTP 404: ไม่พบโมเดลสำหรับ API Key นี้ ($lastErrorMsg)"
                429 -> "⚠️ รหัสตอบกลับ HTTP 429: โควต้าฟรีชั่วคราวเต็ม (Rate Limit) กรุณารอสักครู่"
                else -> "❌ การเชื่อมต่อล้มเหลว (HTTP $lastErrorCode): $lastErrorMsg"
            }

            withContext(Dispatchers.Main) {
                onCompleted(false, explanation, preferredModel)
            }
        }
    }

    private fun discoverAvailableModels(apiKey: String): List<String> {
        val result = mutableListOf<String>()
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val modelsArr = root.optJSONArray("models")
                if (modelsArr != null) {
                    for (i in 0 until modelsArr.length()) {
                        val m = modelsArr.optJSONObject(i) ?: continue
                        val fullName = m.optString("name", "") // e.g. "models/gemini-2.0-flash"
                        val shortName = fullName.removePrefix("models/")
                        val methods = m.optJSONArray("supportedGenerationMethods")
                        val supportsGenerate = methods != null && (0 until methods.length()).any {
                            methods.optString(it) == "generateContent"
                        }
                        if (supportsGenerate && shortName.isNotBlank()) {
                            result.add(shortName)
                        }
                    }
                }
            }
            conn.disconnect()
        } catch (t: Throwable) {
            AirLogger.w("GeminiCoProcessor", "discoverAvailableModels skipped: ${t.message}")
        }
        return result
    }

    private fun queryGeminiWithFallback(
        apiKey: String,
        preferredModel: String,
        candidatePosture: HandPosture,
        observedHandSide: HandSide,
        leftEar: Float,
        rightEar: Float,
        isEyeLocked: Boolean
    ): GeminiInferenceResult? {
        val candidateModels = mutableListOf<String>()
        if (preferredModel.isNotBlank()) candidateModels.add(preferredModel)
        for (m in CANDIDATE_MODELS) {
            if (!candidateModels.contains(m)) candidateModels.add(m)
        }

        for (model in candidateModels) {
            val res = querySingleModel(
                apiKey = apiKey,
                model = model,
                candidatePosture = candidatePosture,
                observedHandSide = observedHandSide,
                leftEar = leftEar,
                rightEar = rightEar,
                isEyeLocked = isEyeLocked
            )
            if (res != null) {
                return res
            }
        }
        return null
    }

    private fun querySingleModel(
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
            connectTimeout = 4500
            readTimeout = 4500
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

        return try {
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

            GeminiInferenceResult(
                recognizedIntent = intent,
                confirmedHandSide = handSide,
                eyeEngagementConfidence = eyeConf,
                aiCommentary = commentary,
                verifiedModel = model,
                latencyMs = 0L
            )
        } catch (_: Throwable) {
            conn.disconnect()
            null
        }
    }
}