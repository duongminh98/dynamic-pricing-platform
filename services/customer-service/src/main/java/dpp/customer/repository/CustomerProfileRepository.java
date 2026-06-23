package dpp.customer.repository;

import dpp.customer.entity.CustomerProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, UUID> {
    CustomerProfile findByAccount_AccountId(UUID accountId);
}

