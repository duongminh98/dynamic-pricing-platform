package dpp.customer.repository;

import dpp.customer.entity.CustomerProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, UUID> {
    CustomerProfile findByAccount_AccountId(UUID accountId);

    @Query("select p from CustomerProfile p join p.account a " +
            "where (:q is null or lower(a.email) like lower(concat('%', cast(:q as string), '%'))) " +
            "and (:province is null or p.province = :province) " +
            "and (:locked is null " +
            "     or (:locked = true and a.lockedUntil is not null and a.lockedUntil > :now) " +
            "     or (:locked = false and (a.lockedUntil is null or a.lockedUntil <= :now)))")
    Page<CustomerProfile> findFiltered(@Param("q") String q,
                                       @Param("province") String province,
                                       @Param("locked") Boolean locked,
                                       @Param("now") OffsetDateTime now,
                                       Pageable pageable);
}

