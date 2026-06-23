package dpp.order.repository;

import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
    Optional<OrderEntity> findByQuoteId(UUID quoteId);
    List<OrderEntity> findByStatusOrderByCreatedAtAsc(OrderStatus status);
    List<OrderEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
}
