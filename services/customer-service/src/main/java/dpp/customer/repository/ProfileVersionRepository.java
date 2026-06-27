package dpp.customer.repository;

import dpp.customer.entity.ProfileVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileVersionRepository extends JpaRepository<ProfileVersion, UUID> {
    List<ProfileVersion> findByCustomerProfile_CustomerIdOrderByEffectiveAtDesc(UUID customerId);

    List<ProfileVersion> findByCustomerProfile_CustomerIdAndLineOrderByEffectiveAtDesc(UUID customerId, String line);

    @Query("""
        SELECT v FROM ProfileVersion v
        WHERE v.customerProfile.customerId = :customerId
          AND v.effectiveAt = (
              SELECT MAX(v2.effectiveAt) FROM ProfileVersion v2
              WHERE v2.customerProfile.customerId = :customerId
                AND v2.line = v.line
          )
        ORDER BY v.line ASC
    """)
    List<ProfileVersion> findLatestPerLine(@Param("customerId") UUID customerId);

    @Query("""
        SELECT v FROM ProfileVersion v
        WHERE v.customerProfile.customerId = :customerId
          AND v.line = :line
          AND v.effectiveAt = (
              SELECT MAX(v2.effectiveAt) FROM ProfileVersion v2
              WHERE v2.customerProfile.customerId = :customerId
                AND v2.line = :line
          )
    """)
    Optional<ProfileVersion> findLatestByLine(@Param("customerId") UUID customerId, @Param("line") String line);
}

