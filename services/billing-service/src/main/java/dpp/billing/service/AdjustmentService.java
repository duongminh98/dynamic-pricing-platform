package dpp.billing.service;

import dpp.billing.entity.*;
import dpp.billing.repository.AdjustmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class AdjustmentService {

    private final AdjustmentRepository adjustmentRepository;

    public AdjustmentService(AdjustmentRepository adjustmentRepository) {
        this.adjustmentRepository = adjustmentRepository;
    }

    @Transactional
    public void applyEndorsement(UUID policyId, long premiumOld, long premiumNew, long remainingDays, long termDays) {
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
    }

    @Transactional
    public void applyCancellation(UUID policyId, long finalPremiumVnd, long remainingDays, long termDays) {
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
    }
}
