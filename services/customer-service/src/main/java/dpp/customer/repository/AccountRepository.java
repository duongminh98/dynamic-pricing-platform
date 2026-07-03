package dpp.customer.repository;

import dpp.customer.entity.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByEmail(String email);

    boolean existsByEmail(String email);
    Optional<Account> findByKeycloakSubject(String keycloakSubject);

    @Query("""
        select a from Account a
        where (:q is null or lower(a.email) like lower(concat('%', cast(:q as string), '%')))
          and (:locked is null
               or (:locked = true and a.lockedUntil is not null and a.lockedUntil > :now)
               or (:locked = false and (a.lockedUntil is null or a.lockedUntil <= :now)))
    """)
    Page<Account> findFiltered(@Param("q") String q,
                               @Param("locked") Boolean locked,
                               @Param("now") OffsetDateTime now,
                               Pageable pageable);
}


