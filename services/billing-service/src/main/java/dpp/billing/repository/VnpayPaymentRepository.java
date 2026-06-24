package dpp.billing.repository;

import dpp.billing.entity.VnpayPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VnpayPaymentRepository extends JpaRepository<VnpayPayment, UUID> {
    Optional<VnpayPayment> findByVnpTxnRef(String vnpTxnRef);
    Optional<VnpayPayment> findByInvoiceId(UUID invoiceId);
}
