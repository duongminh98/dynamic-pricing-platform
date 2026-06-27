package dpp.billing.repository;

import dpp.billing.entity.Invoice;
import dpp.billing.entity.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    Optional<Invoice> findByOrderId(UUID orderId);
    Optional<Invoice> findByPolicyId(UUID policyId);
    List<Invoice> findByPolicyIdOrderByCreatedAtAsc(UUID policyId);
    List<Invoice> findByStatusOrderByCreatedAtDesc(InvoiceStatus status);
    List<Invoice> findAllByOrderByCreatedAtDesc();
    List<Invoice> findByEndorsementRequestIdOrderByCreatedAtDesc(UUID endorsementRequestId);
}
