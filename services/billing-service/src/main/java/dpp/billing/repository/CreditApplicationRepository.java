package dpp.billing.repository;

import dpp.billing.entity.CreditApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CreditApplicationRepository extends JpaRepository<CreditApplication, UUID> {
    List<CreditApplication> findByCreditIdOrderByCreatedAtAsc(UUID creditId);
    List<CreditApplication> findByAppliedToInvoiceId(UUID invoiceId);
    List<CreditApplication> findByCreditIdInOrderByCreatedAtAsc(List<UUID> creditIds);
}
