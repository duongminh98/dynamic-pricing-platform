package dpp.notification.repository;

import dpp.notification.entity.CustomerEmailProjection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CustomerEmailProjectionRepository extends JpaRepository<CustomerEmailProjection, UUID> {
}
