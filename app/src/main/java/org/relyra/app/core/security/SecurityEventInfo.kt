package org.relyra.app.core.security

import org.relyra.app.core.database.EventType
import org.relyra.app.core.database.SecurityEventEntity

enum class Severity { INFO, NOTICE, WARNING, CRITICAL }
enum class EventCategory { IDENTITY, CONTACTS, DEVICES, ACCESS, BACKUP, ABUSE, NETWORK, OTHER }
enum class EventStatus { ALL, OPEN, UNREAD, HANDLED }

/** Severity, category and source of every event type. Unknown types are shown, never hidden. */
object SecurityClassifier {
    fun severity(type: String): Severity = when (type) {
        EventType.KEY_CHANGED -> Severity.CRITICAL
        EventType.APP_LOCK_OFF, EventType.RECOVERY_VIEWED, EventType.DEVICE_SYNC_CONFLICT,
        EventType.APP_UNLOCK_FAILED, EventType.PEER_BLOCKED_PACKET, EventType.NETWORK_ALERT -> Severity.WARNING
        EventType.CONTACT_UNVERIFIED, EventType.CONTACT_BLOCKED, EventType.CONTACT_DELETED,
        EventType.DEVICE_ENROLLMENT_PENDING, EventType.DEVICE_REVOKED, EventType.DEVICE_KEY_ROTATED,
        EventType.IDENTITY_RESTORED, EventType.BACKUP_RESTORE_STAGED -> Severity.NOTICE
        else -> Severity.INFO
    }

    fun category(type: String): EventCategory = when (type) {
        EventType.IDENTITY_CREATED, EventType.IDENTITY_RESTORED, EventType.RECOVERY_VIEWED -> EventCategory.IDENTITY
        EventType.CONTACT_ADDED, EventType.CONTACT_VERIFIED, EventType.CONTACT_UNVERIFIED, EventType.CONTACT_BLOCKED,
        EventType.CONTACT_UNBLOCKED, EventType.CONTACT_DELETED, EventType.KEY_CHANGED -> EventCategory.CONTACTS
        EventType.DEVICE_ENROLLMENT_PENDING, EventType.DEVICE_APPROVED, EventType.DEVICE_REVOKED,
        EventType.DEVICE_KEY_ROTATED, EventType.DEVICE_SYNC_CONFLICT -> EventCategory.DEVICES
        EventType.APP_LOCK_ON, EventType.APP_LOCK_OFF, EventType.APP_UNLOCK_FAILED -> EventCategory.ACCESS
        EventType.BACKUP_EXPORTED, EventType.BACKUP_RESTORE_STAGED -> EventCategory.BACKUP
        EventType.PEER_BLOCKED_PACKET -> EventCategory.ABUSE
        EventType.NETWORK_ALERT -> EventCategory.NETWORK
        else -> EventCategory.OTHER
    }

    /** Where the event came from, for the detail view. */
    fun source(type: String): String = when (category(type)) {
        EventCategory.IDENTITY -> "identity"
        EventCategory.CONTACTS -> "contacts"
        EventCategory.DEVICES -> "devices"
        EventCategory.ACCESS -> "app-lock"
        EventCategory.BACKUP -> "backup"
        EventCategory.ABUSE -> "delivery-engine"
        EventCategory.NETWORK -> "network"
        EventCategory.OTHER -> "unknown"
    }
}

/** Search/filter used by the Security Center. Pure so it can be unit-tested. */
data class SecurityEventFilter(
    val minSeverity: Severity? = null,
    val category: EventCategory? = null,
    val status: EventStatus = EventStatus.ALL,
    val sinceMs: Long? = null,
    val query: String = "",
) {
    fun apply(events: List<SecurityEventEntity>, labelOf: (String) -> String, now: Long = System.currentTimeMillis()): List<SecurityEventEntity> {
        val q = query.trim()
        return events.filter { e ->
            (minSeverity == null || SecurityClassifier.severity(e.type) >= minSeverity) &&
                (category == null || SecurityClassifier.category(e.type) == category) &&
                (sinceMs == null || e.timestamp >= now - sinceMs) &&
                when (status) {
                    EventStatus.ALL -> true
                    EventStatus.OPEN -> !e.handled
                    EventStatus.UNREAD -> e.readAt == null && !e.handled
                    EventStatus.HANDLED -> e.handled
                } &&
                (q.isEmpty() || e.type.contains(q, true) || e.detail.contains(q, true) || labelOf(e.type).contains(q, true))
        }
    }
}
