package dpp.customer.repository;

import dpp.customer.entity.ProfileVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ProfileVersionRepository extends JpaRepository<ProfileVersion, UUID> {
    List<ProfileVersion> findByCustomerProfile_CustomerIdOrderByEffectiveAtDesc(UUID customerId);
}

