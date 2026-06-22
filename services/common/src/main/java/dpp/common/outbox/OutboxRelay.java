package dpp.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled poller that reads NEW outbox entries and publishes them to RabbitMQ.
 *
 * <ul>
 *   <li>Publishes event with unique event_id for consumer dedup (R10.1)</li>
 *   <li>On MQ success: marks entry SENT (R10.5)</li>
 *   <li>On MQ failure: keeps entry NEW, retries next poll (R10.5)</li>
 *   <li>Each entry published independently; partial failures do not affect others</li>
 * </ul>
 *
 * <p>The relay runs asynchronously from the business transaction. The outbox
 * pattern works because business write + outbox INSERT happen atomically in
 * the service layer, then this relay polls and publishes asynchronously.</p>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxRelay(OutboxRepository outboxRepository,
                       RabbitTemplate rabbitTemplate) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Poll every 2 seconds for pending outbox entries.
     * Each entry is published individually so a single MQ failure
     * does not block the rest.
     */
    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void pollAndPublish() {
        List<OutboxEntity> pending = outboxRepository
                .findByStatusOrderByCreatedAtAsc(OutboxEntity.OutboxStatus.NEW);

        if (pending.isEmpty()) {
            return;
        }

        log.debug("Outbox relay: found {} pending events", pending.size());

        for (OutboxEntity entry : pending) {
            try {
                publishEvent(entry);
                entry.markSent();
                outboxRepository.save(entry);
                log.info("Outbox relay: published event {} type={}", entry.getEventId(), entry.getEventType());
            } catch (Exception e) {
                // Keep NEW — will retry on next poll (R10.5)
                log.warn("Outbox relay: failed to publish event {} type={}, will retry: {}",
                        entry.getEventId(), entry.getEventType(), e.getMessage());
            }
        }
    }

    private void publishEvent(OutboxEntity entry) {
        // Exchange = event_type (each event type has its own exchange per §2.4.2)
        String exchange = entry.getEventType();
        String routingKey = entry.getEventType();

        rabbitTemplate.convertAndSend(exchange, routingKey, entry.getPayload(), message -> {
            message.getMessageProperties().setCorrelationId(entry.getEventId());
            message.getMessageProperties().setHeader("X-Event-Id", entry.getEventId());
            message.getMessageProperties().setHeader("X-Event-Type", entry.getEventType());
            message.getMessageProperties().setHeader("X-Created-At", entry.getCreatedAt().toString());
            return message;
        });
    }
}
