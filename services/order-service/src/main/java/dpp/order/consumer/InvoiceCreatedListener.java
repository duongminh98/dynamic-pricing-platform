package dpp.order.consumer;

import dpp.order.entity.EndorsementRequestEntity;
import dpp.order.entity.OrderEntity;
import dpp.order.repository.EndorsementRequestRepository;
import dpp.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

@Component
public class InvoiceCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceCreatedListener.class);

    private final OrderRepository orderRepository;
    private final EndorsementRequestRepository endorsementRequestRepository;
    private final ObjectMapper objectMapper;

    public InvoiceCreatedListener(OrderRepository orderRepository,
                                   EndorsementRequestRepository endorsementRequestRepository) {
        this.orderRepository = orderRepository;
        this.endorsementRequestRepository = endorsementRequestRepository;
        this.objectMapper = new ObjectMapper();
    }

    @RabbitListener(queues = "invoice.created.order.queue")
    @Transactional
    public void onInvoiceCreated(@Payload String message,
                                  @Header(name = "X-Event-Id", required = false) String eventId) {
        try {
            JsonNode node = objectMapper.readTree(message);
            UUID invoiceId = UUID.fromString(node.get("invoice_id").asText());

            // Branch 1: endorsement adjustment invoice
            if (node.has("endorsement_request_id") && !node.get("endorsement_request_id").isNull()) {
                UUID endorsementRequestId = UUID.fromString(node.get("endorsement_request_id").asText());
                Optional<EndorsementRequestEntity> opt = endorsementRequestRepository.findById(endorsementRequestId);
                if (opt.isPresent()) {
                    EndorsementRequestEntity req = opt.get();
                    if (req.getInvoiceId() == null) {
                        req.setInvoiceId(invoiceId);
                        endorsementRequestRepository.save(req);
                        log.info("InvoiceCreated: set invoice_id={} on endorsement {}", invoiceId, endorsementRequestId);
                    } else {
                        log.info("InvoiceCreated: endorsement {} already has invoice_id={}, ignoring",
                                endorsementRequestId, req.getInvoiceId());
                    }
                }
                return;
            }

            // Branch 2: order invoice
            UUID orderId = UUID.fromString(node.get("order_id").asText());
            Optional<OrderEntity> opt = orderRepository.findById(orderId);
            if (opt.isPresent()) {
                OrderEntity order = opt.get();
                if (order.getInvoiceId() == null) {
                    order.setInvoiceId(invoiceId);
                    orderRepository.save(order);
                    log.info("InvoiceCreated: set invoice_id={} on order {}", invoiceId, orderId);
                } else {
                    log.info("InvoiceCreated: order {} already has invoice_id={}, ignoring",
                            orderId, order.getInvoiceId());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to process InvoiceCreated", e);
        }
    }
}
