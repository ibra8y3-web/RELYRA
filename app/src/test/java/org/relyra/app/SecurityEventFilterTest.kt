package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.database.EventType
import org.relyra.app.core.database.SecurityEventEntity
import org.relyra.app.core.security.EventCategory
import org.relyra.app.core.security.EventStatus
import org.relyra.app.core.security.SecurityClassifier
import org.relyra.app.core.security.SecurityEventFilter
import org.relyra.app.core.security.Severity

class SecurityEventFilterTest {
    private val now = 10_000_000_000L
    private fun ev(id: Long, type: String, ageMs: Long = 0, detail: String = "", readAt: Long? = null, handled: Boolean = false) =
        SecurityEventEntity(id, now - ageMs, type, detail, readAt, handled)

    private val all = listOf(
        ev(1, EventType.KEY_CHANGED, detail = "alice"),
        ev(2, EventType.APP_UNLOCK_FAILED, ageMs = 2 * 86_400_000L, readAt = now),
        ev(3, EventType.CONTACT_ADDED, ageMs = 40 * 86_400_000L, handled = true),
        ev(4, EventType.PEER_BLOCKED_PACKET, detail = "ab12cd34"),
        ev(5, "SOMETHING_NEW"),
    )
    private val label = { t: String -> t.lowercase().replace('_', ' ') }

    @Test fun classification() {
        assertEquals(Severity.CRITICAL, SecurityClassifier.severity(EventType.KEY_CHANGED))
        assertEquals(Severity.WARNING, SecurityClassifier.severity(EventType.APP_UNLOCK_FAILED))
        assertEquals(Severity.INFO, SecurityClassifier.severity("SOMETHING_NEW"))
        assertEquals(EventCategory.ABUSE, SecurityClassifier.category(EventType.PEER_BLOCKED_PACKET))
        assertEquals(EventCategory.OTHER, SecurityClassifier.category("SOMETHING_NEW"))
    }

    @Test fun severityThresholdAndCategory() {
        val warn = SecurityEventFilter(minSeverity = Severity.WARNING).apply(all, label, now).map { it.id }
        assertEquals(listOf(1L, 2L, 4L), warn)
        val abuse = SecurityEventFilter(category = EventCategory.ABUSE).apply(all, label, now).map { it.id }
        assertEquals(listOf(4L), abuse)
    }

    @Test fun statusFilters() {
        assertEquals(listOf(1L, 4L, 5L), SecurityEventFilter(status = EventStatus.UNREAD).apply(all, label, now).map { it.id }.sorted().filter { it != 2L })
        assertEquals(listOf(3L), SecurityEventFilter(status = EventStatus.HANDLED).apply(all, label, now).map { it.id })
        assertTrue(SecurityEventFilter(status = EventStatus.OPEN).apply(all, label, now).none { it.handled })
    }

    @Test fun dateRangeAndSearch() {
        assertEquals(listOf(1L, 4L, 5L), SecurityEventFilter(sinceMs = 86_400_000L).apply(all, label, now).map { it.id })
        assertEquals(listOf(1L), SecurityEventFilter(query = "ALICE").apply(all, label, now).map { it.id })
        assertEquals(listOf(2L), SecurityEventFilter(query = "unlock failed").apply(all, label, now).map { it.id })
    }
}
