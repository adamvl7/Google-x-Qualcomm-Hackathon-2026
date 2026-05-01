package com.fitform.app.recap

import android.content.Context
import android.util.Log
import com.fitform.app.model.SessionSummary
import com.fitform.app.model.SetRecap
import com.fitform.app.model.Severity
import com.fitform.app.storage.SessionRepository
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SetRecapGenerator(
    private val context: Context,
    private val repository: SessionRepository,
) {
    fun generateAndSave(sessionId: String): SessionSummary? {
        val summary = repository.load(sessionId) ?: return null
        if (summary.recap != null) return summary

        val recap = generate(summary)
        val updated = summary.copy(recap = recap)
        repository.save(sessionId, updated)
        return updated
    }

    private fun generate(summary: SessionSummary): SetRecap {
        val metrics = SetMetrics.from(summary)
        val gemma = runCatching { generateWithGemma(metrics) }
            .onFailure { Log.w(TAG, "Gemma recap unavailable; using local fallback", it) }
            .getOrNull()

        return gemma ?: localFallback(metrics)
    }

    private fun generateWithGemma(metrics: SetMetrics): SetRecap? {
        val model = resolveGemmaModel() ?: return null
        val prompt = metrics.toPrompt()
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.absolutePath)
            .setMaxTokens(1024)
            .setMaxTopK(40)
            .build()

        LlmInference.createFromOptions(context, options).use { llm ->
            val response = llm.generateResponse(prompt).trim()
            if (response.isBlank()) return null
            return SetRecap(
                status = "generated",
                modelName = model.nameWithoutExtension,
                generatedAt = nowIso(),
                overall = response,
            )
        }
    }

    private fun resolveGemmaModel(): File? {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        modelDir.listFiles()
            ?.firstOrNull { it.isFile && it.name.lowercase(Locale.US).startsWith("gemma") && it.name.endsWith(".task", ignoreCase = true) }
            ?.let { return it }

        val assetName = runCatching {
            context.assets.list("")?.firstOrNull {
                it.lowercase(Locale.US).startsWith("gemma") && it.endsWith(".task", ignoreCase = true)
            }
        }.getOrNull() ?: return null

        val copiedModel = File(modelDir, assetName)
        if (copiedModel.exists()) return copiedModel

        context.assets.open(assetName).use { input ->
            copiedModel.outputStream().use { output -> input.copyTo(output) }
        }
        return copiedModel
    }

    private fun localFallback(metrics: SetMetrics): SetRecap {
        val topCue = metrics.topCues.firstOrNull()
        val good = metrics.averageScore >= 85 && metrics.redEvents == 0
        val overall = when {
            good -> "Strong ${metrics.exerciseName} session. Your average form score was ${metrics.averageScore}%, with ${metrics.repLabelLower} tracked cleanly and no major red flags."
            topCue != null -> "This ${metrics.exerciseName} session averaged ${metrics.averageScore}%. The biggest pattern was \"$topCue\", so the next set should focus there first."
            else -> "This ${metrics.exerciseName} session was recorded successfully. The set needs a little more consistent tracking before deeper coaching is available."
        }
        val wentWell = buildList {
            if (metrics.repCount > 0) add("Completed ${metrics.repCount} ${metrics.repLabelLower}.")
            if (metrics.averageScore >= 80) add("Kept the overall score in a solid range.")
            if (metrics.greenFramePercent >= 50) add("Most tracked frames stayed in the green zone.")
        }.ifEmpty { listOf("The full set was captured for replay review.") }
        val needsWork = metrics.topCues.take(3).ifEmpty { listOf("Keep your full body visible so the app can score every frame confidently.") }
        val tips = buildList {
            metrics.topCues.take(2).forEach { add(tipFor(it, metrics.exerciseName)) }
            add("Review the replay overlay and pause on the lowest-scoring moments.")
        }.distinct().take(3)

        return SetRecap(
            status = "fallback",
            modelName = "local-recap",
            generatedAt = nowIso(),
            overall = overall,
            wentWell = wentWell,
            needsWork = needsWork,
            tips = tips,
        )
    }

    private fun tipFor(cue: String, exerciseName: String): String = when {
        cue.contains("shallow", ignoreCase = true) || cue.contains("deeper", ignoreCase = true) ->
            "For squats, aim for a knee angle at 90 degrees or lower before standing up."
        cue.contains("chest", ignoreCase = true) || cue.contains("sit back", ignoreCase = true) ->
            "Match your torso angle to your shin angle so your back and legs move together."
        cue.contains("arc", ignoreCase = true) ->
            "For shots, aim for a release path near 45 degrees."
        cue.contains("elbow", ignoreCase = true) ->
            "Keep the shooting elbow stacked under the wrist through the set point."
        else -> "Repeat one slower $exerciseName rep while focusing on \"$cue\"."
    }

    private fun nowIso(): String = ISO.format(Date())

    companion object {
        private const val TAG = "FitForm/SetRecap"
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}

private data class SetMetrics(
    val exerciseName: String,
    val repLabelLower: String,
    val repCount: Int,
    val averageScore: Int,
    val topCues: List<String>,
    val redEvents: Int,
    val greenFramePercent: Int,
) {
    fun toPrompt(): String = """
        You are FitForm, an encouraging exercise coach. Write a concise post-set recap.
        Use the recorded metrics only. Do not invent injuries, medical advice, or unseen details.

        Exercise: $exerciseName
        Count: $repCount $repLabelLower
        Average score: $averageScore%
        Top repeated cues: ${topCues.joinToString().ifBlank { "none" }}
        Red event count: $redEvents
        Green frame percent: $greenFramePercent%

        Return:
        1. A short overall paragraph.
        2. "What went well" with 2 bullets.
        3. "What to improve" with 2 bullets.
        4. "Next set focus" with 2 practical tips.
    """.trimIndent()

    companion object {
        fun from(summary: SessionSummary): SetMetrics {
            val exercise = if (summary.exercise == "jumpshot") "jump shot" else summary.exercise
            val repLabel = if (summary.mode == "shot") "shots" else "reps"
            val cueCounts = summary.events
                .filter { it.severity != Severity.GREEN }
                .groupingBy { it.cue }
                .eachCount()
            val topCues = cueCounts.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
            val tracked = summary.frameData.filter { it.score > 0 || it.cue.isNotBlank() }
            val green = tracked.count { it.severity == Severity.GREEN }
            val greenPercent = if (tracked.isEmpty()) 0 else (green * 100 / tracked.size)

            return SetMetrics(
                exerciseName = exercise,
                repLabelLower = repLabel,
                repCount = summary.repCount,
                averageScore = summary.averageScore,
                topCues = topCues,
                redEvents = summary.events.count { it.severity == Severity.RED },
                greenFramePercent = greenPercent,
            )
        }
    }
}
