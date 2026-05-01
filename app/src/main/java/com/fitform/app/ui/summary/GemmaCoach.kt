package com.fitform.app.ui.summary

import android.content.Context
import android.util.Log
import com.fitform.app.model.SessionSummary
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thin wrapper around MediaPipe LlmInference (Gemma 3 1B IT INT4).
 *
 * Model must be placed at:
 *   /sdcard/Android/data/com.fitform.app/files/gemma/gemma-3-1b-it-int4.bin
 *
 * `adb push gemma-3-1b-it-int4.bin /sdcard/Android/data/com.fitform.app/files/gemma/`
 *
 * If the file is absent the function returns null and the UI falls back to
 * a "download model" message — the app still works without Gemma.
 */
object GemmaCoach {

    private const val TAG = "FitForm/Gemma"
    private const val MODEL_FILENAME = "gemma3-1B-it-int4.task"

    fun modelFile(context: Context): File =
        File(context.getExternalFilesDir(null), "gemma/$MODEL_FILENAME")

    fun isAvailable(context: Context): Boolean = modelFile(context).exists()

    /**
     * Generates a 2-sentence coaching tip from the session data.
     * Runs entirely on [Dispatchers.IO]; safe to call from a coroutine.
     * Returns the generated string, or null if the model file is missing
     * or inference throws.
     */
    suspend fun generate(context: Context, summary: SessionSummary): String? =
        withContext(Dispatchers.IO) {
            val file = modelFile(context)
            if (!file.exists()) {
                Log.w(TAG, "Model not found at ${file.absolutePath}")
                return@withContext null
            }

            Log.i(TAG, "Loading Gemma for session ${summary.sessionId}…")
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(180)
                .build()

            var llm: LlmInference? = null
            try {
                llm = LlmInference.createFromOptions(context, opts)
                Log.i(TAG, "Gemma loaded — generating…")
                val result = llm.generateResponse(buildPrompt(summary))
                Log.i(TAG, "Gemma done: ${result.length} chars")
                result.trim()
            } catch (t: Throwable) {
                Log.e(TAG, "Gemma inference failed", t)
                null
            } finally {
                try { llm?.close() } catch (_: Throwable) {}
            }
        }

    private fun buildPrompt(s: SessionSummary): String {
        val exercise = if (s.mode == "gym") "squat" else "basketball jump shot"
        val issues = s.topCues(3).joinToString(", ").ifBlank { "no major issues" }
        // Gemma 3 instruction format
        return "<start_of_turn>user\n" +
            "You are a concise athletic coach. Write exactly 2 short sentences of advice. " +
            "Do not use headers, bullet points, or any formatting — plain sentences only.\n" +
            "Exercise: $exercise | Score: ${s.averageScore}% | Reps: ${s.repCount}\n" +
            "Main issues this set: $issues\n" +
            "<end_of_turn>\n" +
            "<start_of_turn>model\n"
    }
}
