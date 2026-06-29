package dpp.billing.service;

import dpp.billing.entity.*;
import dpp.billing.repository.AdjustmentRepository;
import dpp.billing.repository.InvoiceRepository;
import dpp.billing.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class AdjustmentService {

    static final String CONSUMER_ENDORSEMENT = "billing.endorsement-applied";
    static final String CONSUMER_CANCELLATION = "billing.policy-cancelled";

    private final AdjustmentRepository adjustmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final ProcessedEventRepository processedEventRepository;

    public AdjustmentService(AdjustmentRepository adjustmentRepository,
                             InvoiceRepository invoiceRepository,
                             ProcessedEventRepository processedEventRepository) {
        this.adjustmentRepository = adjustmentRepository;
        this.invoiceRepository = invoiceRepository;
        this.processedEventRepository = processedEventRepository;
    }

    @Transactional
    public void applyEndorsement(String eventId, UUID policyId, UUID orderId, long premiumOld, long premiumNew,
                                 long remainingDays, long termDays) {
        // R33.4: dedup on event_id within the same TX as the Adjustment insert. A
        // redelivered EndorsementApplied is a no-op (no duplicate additional charge / refund).
        if (alreadyProcessed(eventId)) {
            return;
        }
        double fraction = termDays > 0 ? remainingDays / (double) termDays : 0;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        long delta = Math.round((premiumNew - premiumOld) * fraction);
        Adjustment adj = new Adjustment();
        adj.setAdjustmentId(UUID.randomUUID());
        adj.setPolicyId(policyId);
        adj.setType(delta >= 0 ? AdjustmentType.additional_charge : AdjustmentType.refund);
        adj.setAmountVnd(Math.abs(delta));
        adj.setReason(AdjustmentReason.endorsement);
        adj.setCreatedAt(OffsetDateTime.now());
        adjustmentRepository.save(adj);
        // Invoice is now created upfront by PolicyLifecycleService when the endorsement
        // is approved (for premium increases) or not at all (for premium decreases —
        // refund is recorded as adjustment only, settled at renewal or manually).
        recordProcessed(eventId, CONSUMER_ENDORSEMENT);
    }

    @Transactional
    public long applyCancellation(String eventId, UUID policyId, long finalPremiumVnd,
                                   long remainingDays, long termDays) {
        // R33.4: dedup on event_id within the same TX as the Adjustment insert. A
        // redelivered PolicyCancelled is a no-op (no duplicate refund).
        if (alreadyProcessed(eventId)) {
            return 0L;
        }
        double fraction = termDays > 0 ? remainingDays / (double) termDays : 0;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        long refund = Math.round(finalPremiumVnd * fraction);
        Adjustment adj = new Adjustment();
        adj.setAdjustmentId(UUID.randomUUID());
        adj.setPolicyId(policyId);
        adj.setType(AdjustmentType.refund);
        adj.setAmountVnd(refund);
        adj.setReason(AdjustmentReason.cancellation);
        adj.setCreatedAt(OffsetDateTime.now());
        adjustmentRepository.save(adj);
        recordProcessed(eventId, CONSUMER_CANCELLATION);
        return refund;
    }

    private boolean alreadyProcessed(String eventId) {
        return eventId != null && processedEventRepository.existsById(eventId);
    }

    private void recordProcessed(String eventId, String consumer) {
        if (eventId == null) {
            return;
        }
        ProcessedEvent pe = new ProcessedEvent();
        pe.setEventId(eventId);
        pe.setConsumer(consumer);
        pe.setProcessedAt(OffsetDateTime.now());
        processedEventRepository.save(pe);
    }
}
