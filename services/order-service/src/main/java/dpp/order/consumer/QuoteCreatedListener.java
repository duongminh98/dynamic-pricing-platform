package dpp.order.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dpp.order.entity.QuoteSnapshot;
import dpp.order.repository.QuoteSnapshotRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class QuoteCreatedListener {
    private final QuoteSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    public QuoteCreatedListener(QuoteSnapshotRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "quote.created.order.queue")
    @Transactional
    public void onQuoteCreated(@Payload String message) {
        try {
            JsonNode n = objectMapper.readTree(message);
            UUID quoteId = UUID.fromString(n.get("quote_id").asText());
            QuoteSnapshot snapshot = repository.findById(quoteId).orElseGet(QuoteSnapshot::new);
            snapshot.setQuoteId(quoteId);
            snapshot.setCustomerId(UUID.fromString(n.get("customer_id").asText()));
            snapshot.setProductId(n.get("product_id").asText());
            snapshot.setLine(n.hasNonNull("line") ? n.get("line").asText() : null);
            snapshot.setTripDurationDays(n.hasNonNull("trip_duration_days") ? n.get("trip_duration_days").asInt() : null);
            snapshot.setCoverageAmountVnd(n.hasNonNull("coverage_amount_vnd") ? n.get("coverage_amount_vnd").asLong() : null);
            snapshot.setDeductibleVnd(n.hasNonNull("deductible_vnd") ? n.get("deductible_vnd").asLong() : null);
            snapshot.setProfile(n.hasNonNull("profile") ? objectMapper.writeValueAsString(n.get("profile")) : null);
            snapshot.setFinalPremiumVnd(n.get("final_premium_vnd").asLong());
            snapshot.setExpiresAt(OffsetDateTime.parse(n.get("expires_at").asText()));
            snapshot.setCreatedAt(OffsetDateTime.parse(n.get("created_at").asText()));
            snapshot.setReceivedAt(OffsetDateTime.now());
            repository.save(snapshot);
        } catch (Exception e) {
            throw new RuntimeException("QuoteCreated processing failed", e);
        }
    }
}
