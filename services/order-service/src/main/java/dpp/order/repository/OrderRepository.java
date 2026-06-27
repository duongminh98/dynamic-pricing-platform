package dpp.order.repository;

import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
    Optional<OrderEntity> findByQuoteId(UUID quoteId);
    List<OrderEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    @Query("SELECT o FROM OrderEntity o WHERE " +
           "(:status IS NULL OR o.status = :status) AND " +
           "(:customerId IS NULL OR o.customerId = :customerId) AND " +
           "(:line IS NULL OR o.line = :line)")
    Page<OrderEntity> findFiltered(@Param("status") OrderStatus status,
                                   @Param("customerId") UUID customerId,
                                   @Param("line") String line,
                                   Pageable pageable);

    @Query("SELECT o FROM OrderEntity o WHERE o.status = :status AND (:line IS NULL OR o.line = :line)")
    Page<OrderEntity> findByStatus(@Param("status") OrderStatus status,
                                   @Param("line") String line,
                                   Pageable pageable);

    default Page<OrderEntity> findByStatus(OrderStatus status, Pageable pageable) {
        return findByStatus(status, null, pageable);
    }

    default Page<OrderEntity> findByStatusAndLine(OrderStatus status, String line, Pageable pageable) {
        return findByStatus(status, line, pageable);
    }
}
