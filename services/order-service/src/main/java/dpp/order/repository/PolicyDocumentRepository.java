package dpp.order.repository;

import dpp.order.entity.PolicyDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, UUID> {
    List<PolicyDocument> findByPolicyIdOrderByVersionDesc(UUID policyId);
    default Optional<PolicyDocument> findLatestByPolicyId(UUID policyId) {
        List<PolicyDocument> docs = findByPolicyIdOrderByVersionDesc(policyId);
        return docs.isEmpty() ? Optional.empty() : Optional.of(docs.get(0));
    }
}
