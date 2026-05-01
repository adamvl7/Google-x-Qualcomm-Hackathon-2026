package com.fitform.app.storage

import android.content.Context
import com.fitform.app.model.SessionSummary

/**
 * Higher-level access to saved sessions for the History screen and
 * the Replay screen.
 */
class SessionRepository(context: Context) {
    val storage = SessionStorage(context)

    fun listSummaries(): List<SessionSummary> =
        storage.listSessionIds().mapNotNull { storage.loadAnalysis(it) }

    fun load(sessionId: String): SessionSummary? = storage.loadAnalysis(sessionId)

    fun save(sessionId: String, summary: SessionSummary) = storage.saveAnalysis(sessionId, summary)

    fun videoFile(sessionId: String) = storage.videoFile(sessionId)

    fun delete(sessionId: String): Boolean = storage.delete(sessionId)
}
