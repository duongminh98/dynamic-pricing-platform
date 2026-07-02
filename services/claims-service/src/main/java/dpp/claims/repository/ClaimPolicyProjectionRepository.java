package dpp.claims.repository;

import dpp.claims.entity.ClaimPolicyProjection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ClaimPolicyProjectionRepository extends JpaRepository<ClaimPolicyProjection, UUID> {
}
