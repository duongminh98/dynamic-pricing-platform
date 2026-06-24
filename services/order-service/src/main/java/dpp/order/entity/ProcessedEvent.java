package dpp.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Idempotency ledger for consumed events (R6.6). A row's presence means the
 * event was already handled; inserting within the same transaction as the
 * business write makes redelivery a safe no-op.
 */
@Entity
@Table(name = "processed_event")
@Getter
@Setter
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "consumer", nullable = false, length = 100)
    private String consumer;

    @Column(name = "processed_at", nullable = false)
    private OffsetDateTime processedAt;
}
