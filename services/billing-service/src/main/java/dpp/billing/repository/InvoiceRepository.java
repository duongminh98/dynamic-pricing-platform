package dpp.billing.repository;

import dpp.billing.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    Optional<Invoice> findByOrderId(UUID orderId);
    List<Invoice> findByPolicyIdOrderByCreatedAtAsc(UUID policyId);
}
