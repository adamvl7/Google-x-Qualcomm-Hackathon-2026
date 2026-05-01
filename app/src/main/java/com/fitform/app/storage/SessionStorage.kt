package com.fitform.app.storage

import android.content.Context
import com.fitform.app.model.SessionSummary
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-disk layout (per spec):
 *
 *   sessions/
 *     session_YYYYMMDD_HHMMSS/
 *       video.mp4
 *       analysis.json
 *
 * All under [Context.getExternalFilesDir] which is private to the app
 * and cleared on uninstall — no cloud, no shared storage permission.
 */
class SessionStorage(private val context: Context) {

    val rootDir: File by lazy {
        File(context.getExternalFilesDir(null), "sessions").apply { mkdirs() }
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dirFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** Allocate a fresh session directory. */
    fun newSession(now: Date = Date()): SessionDir {
        val id = "session_${dirFormat.format(now)}"
        val dir = File(rootDir, id).apply { mkdirs() }
        return SessionDir(id = id, dir = dir)
    }

    fun videoFile(session: SessionDir) = File(session.dir, "video.mp4")
    fun analysisFile(session: SessionDir) = File(session.dir, "analysis.json")

    fun saveAnalysis(session: SessionDir, summary: SessionSummary) {
        analysisFile(session).writeText(json.encodeToString(summary))
    }

    fun saveAnalysis(sessionId: String, summary: SessionSummary) {
        val dir = File(rootDir, sessionId).apply { mkdirs() }
        File(dir, "analysis.json").writeText(json.encodeToString(summary))
    }

    fun loadAnalysis(sessionId: String): SessionSummary? {
        val file = File(File(rootDir, sessionId), "analysis.json")
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(SessionSummary.serializer(), file.readText())
        }.getOrNull()
    }

    fun videoFile(sessionId: String): File = File(File(rootDir, sessionId), "video.mp4")

    fun listSessionIds(): List<String> = rootDir
        .listFiles { f -> f.isDirectory && f.name.startsWith("session_") }
        ?.sortedByDescending { it.name }
        ?.map { it.name }
        ?: emptyList()

    fun delete(sessionId: String): Boolean = File(rootDir, sessionId).deleteRecursively()
}

data class SessionDir(val id: String, val dir: File)
