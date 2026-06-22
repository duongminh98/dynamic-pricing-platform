package dpp.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
 *
 * <p>Events are published to the shared topic exchange {@code platform.events}
 * (declared in {@code infra/rabbitmq/definitions.json}, §2.4.2) using the event
 * type as the routing key; queues bind by routing key and dead-letter to
 * {@code platform.events.dlx} after {@code x-delivery-limit} redeliveries.</p>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    /** Shared topic exchange that fans events out to per-event-type queues (§2.4.2). */
    private final String eventsExchange;

    public OutboxRelay(OutboxRepository outboxRepository,
                       RabbitTemplate rabbitTemplate,
                       @Value("${dpp.events.exchange:platform.events}") String eventsExchange) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.eventsExchange = eventsExchange;
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
        // Publish to the shared topic exchange; the event type is the routing key
        // so queues bound by routing key receive it (§2.4.2, definitions.json).
        String routingKey = entry.getEventType();

        rabbitTemplate.convertAndSend(eventsExchange, routingKey, entry.getPayload(), message -> {
            MessageProperties props = message.getMessageProperties();
            // Payload is a JSON string (jsonb column) — tag it so consumers deserialize correctly.
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setContentEncoding(StandardCharsets.UTF_8.name());
            // Survive broker restarts to match the durable quorum queues.
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            props.setMessageId(entry.getEventId());
            props.setCorrelationId(entry.getEventId());
            props.setHeader("X-Event-Id", entry.getEventId());
            props.setHeader("X-Event-Type", entry.getEventType());
            props.setHeader("X-Created-At", entry.getCreatedAt().toString());
            return message;
        });
    }
}
