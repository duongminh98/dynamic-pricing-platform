package dpp.billing.service;

import dpp.billing.entity.*;
import dpp.billing.repository.*;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Credit net-off engine and premium credit lifecycle (design §4-7).
 *
 * <p>Manages premium credits from RP endorsements, applies them FIFO against
 * new invoices (net-off), and reverses credit applications when invoices are voided.</p>
 */
@Service
public class CreditService {

    static final String CONSUMER_CREDIT_ISSUED = "billing.credit-issued";

    private final PremiumCreditRepository creditRepository;
    private final CreditApplicationRepository applicationRepository;
    private final InvoiceRepository invoiceRepository;
    private final ProcessedEventRepository processedEventRepository;

    public CreditService(PremiumCreditRepository creditRepository,
                         CreditApplicationRepository applicationRepository,
                         InvoiceRepository invoiceRepository,
                         ProcessedEventRepository processedEventRepository) {
        this.creditRepository = creditRepository;
        this.applicationRepository = applicationRepository;
        this.invoiceRepository = invoiceRepository;
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Create a premium credit from an RP endorsement (EndorsementCreditIssued event).
     * Idempotent on eventId via processed_event.
     */
    @Transactional
    public void createCredit(String eventId, UUID policyId, UUID customerId, UUID endorsementId, long amountVnd) {
        if (eventId != null && processedEventRepository.existsById(eventId)) {
            return;
        }
        PremiumCredit credit = new PremiumCredit();
        credit.setCreditId(UUID.randomUUID());
        credit.setPolicyId(policyId);
        credit.setCustomerId(customerId);
        credit.setSourceEndorsementId(endorsementId);
        credit.setOriginalAmountVnd(amountVnd);
        credit.setRemainingAmountVnd(amountVnd);
        credit.setStatus(CreditStatus.open);
        credit.setCreatedAt(OffsetDateTime.now());
        creditRepository.save(credit);
        if (eventId != null) {
            ProcessedEvent pe = new ProcessedEvent();
            pe.setEventId(eventId);
            pe.setConsumer(CONSUMER_CREDIT_ISSUED);
            pe.setProcessedAt(OffsetDateTime.now());
            processedEventRepository.save(pe);
        }
    }

    /**
     * Quote the net amount due after applying available credits FIFO.
     * Does NOT persist any credit applications — use applyCreditsToInvoice for that.
     * Called by order-service before creating an AP invoice to determine the net amount.
     */
    @Transactional
    public long applyCreditsToQuote(UUID customerId, long amountVnd) {
        List<PremiumCredit> availableCredits = creditRepository
                .findByCustomerIdAndStatusInOrderByCreatedAtAsc(customerId,
                        List.of(CreditStatus.open, CreditStatus.partially_applied));

        long remaining = amountVnd;
        for (PremiumCredit credit : availableCredits) {
            if (remaining <= 0) break;
            long apply = Math.min(remaining, credit.getRemainingAmountVnd());
            remaining -= apply;
        }
        return remaining;
    }

    /**
     * Apply available credits (FIFO) against an invoice amount.
     * Returns the net amount due after credit application.
     * Must be called within the same transaction as invoice creation.
     */
    @Transactional
    public long applyCreditsToInvoice(UUID invoiceId, UUID customerId, long amountVnd) {
        List<PremiumCredit> availableCredits = creditRepository
                .findByCustomerIdAndStatusInOrderByCreatedAtAsc(customerId,
                        List.of(CreditStatus.open, CreditStatus.partially_applied));

        long remaining = amountVnd;
        long totalApplied = 0;

        for (PremiumCredit credit : availableCredits) {
            if (remaining <= 0) break;
            long apply = Math.min(remaining, credit.getRemainingAmountVnd());
            if (apply <= 0) continue;

            CreditApplication app = new CreditApplication();
            app.setApplicationId(UUID.randomUUID());
            app.setCreditId(credit.getCreditId());
            app.setAppliedToInvoiceId(invoiceId);
            app.setAmountAppliedVnd(apply);
            app.setCreatedAt(OffsetDateTime.now());
            applicationRepository.save(app);

            credit.setRemainingAmountVnd(credit.getRemainingAmountVnd() - apply);
            updateCreditStatus(credit);
            creditRepository.save(credit);

            remaining -= apply;
            totalApplied += apply;
        }

        Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
        if (invoice != null) {
            invoice.setCreditAppliedVnd(totalApplied);
            invoice.setNetAmountVnd(amountVnd - totalApplied);
            invoiceRepository.save(invoice);
        }

        return amountVnd - totalApplied;
    }

    /**
     * Reverse credit applications when an invoice is voided.
     * Restores credit remaining amounts and re-evaluates status.
     */
    @Transactional
    public void reverseCreditsForInvoice(UUID invoiceId) {
        List<CreditApplication> applications = applicationRepository.findByAppliedToInvoiceId(invoiceId);
        for (CreditApplication app : applications) {
            PremiumCredit credit = creditRepository.findById(app.getCreditId()).orElse(null);
            if (credit != null) {
                credit.setRemainingAmountVnd(credit.getRemainingAmountVnd() + app.getAmountAppliedVnd());
                updateCreditStatus(credit);
                creditRepository.save(credit);
            }
            applicationRepository.delete(app);
        }
    }

    /**
     * Get all credits for a policy (for balance calculation and display).
     */
    @Transactional(readOnly = true)
    public List<PremiumCredit> getCreditsByPolicy(UUID policyId) {
        return creditRepository.findByPolicyIdOrderByCreatedAtAsc(policyId);
    }

    /**
     * Get credits with remaining balance for a policy.
     */
    @Transactional(readOnly = true)
    public List<PremiumCredit> getActiveCredits(UUID policyId) {
        return creditRepository.findByPolicyIdAndRemainingAmountVndGreaterThan(policyId, 0);
    }

    private void updateCreditStatus(PremiumCredit credit) {
        if (credit.getRemainingAmountVnd() <= 0) {
            credit.setStatus(CreditStatus.exhausted);
        } else if (credit.getRemainingAmountVnd() < credit.getOriginalAmountVnd()) {
            credit.setStatus(CreditStatus.partially_applied);
        } else {
            credit.setStatus(CreditStatus.open);
        }
    }
}
