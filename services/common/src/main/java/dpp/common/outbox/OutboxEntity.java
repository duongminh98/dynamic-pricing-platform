package dpp.common.outbox;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Outbox entity for the Transactional Outbox pattern (§2.4.3).
 * Written atomically with business data in the same transaction (R19.4).
 * Polled by {@link OutboxRelay} and published to RabbitMQ.
 *
 * Requirements: R10.1 (event_id unique), R10.5 (keep NEW on MQ error).
 */
@Entity
@Table(name = "outbox", indexes = {
        @Index(name = "idx_outbox_status", columnList = "status")
})
public class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Unique event identifier for dedup at consumer side (R10.1).
     * Set by the publisher, NOT auto-generated.
     */
    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    /**
     * NEW → not yet published; SENT → successfully published to MQ.
     * On MQ failure, remains NEW and is retried next poll (R10.5).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OutboxStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    // ── Constructors ──

    protected OutboxEntity() {
        // JPA
    }

    public OutboxEntity(String eventId, String eventType, String payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.NEW;
        this.createdAt = OffsetDateTime.now();
    }

    // ── Lifecycle ──

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.publishedAt = OffsetDateTime.now();
    }

    // ── Getters ──

    public UUID getId() { return id; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }

    public enum OutboxStatus {
        NEW, SENT
    }
}
