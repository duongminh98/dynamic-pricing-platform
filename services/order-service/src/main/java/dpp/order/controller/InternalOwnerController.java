package dpp.order.controller;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.order.dto.OwnerResponse;
import dpp.order.repository.OrderRepository;
import dpp.order.repository.PolicyRepository;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * INTERNAL, network-only owner-lookup endpoints used for server-to-server
 * resolution of the owning customer_id (e.g. billing-service OrderClient).
 *
 * <p>These endpoints do NOT require a customer JWT because they carry no
 * customer context of their own; callers are trusted peer services on the
 * internal docker network. They are intentionally NOT added to the public
 * Kong routes (infra/kong/kong.yml routes only /orders, /policies,
 * /admin/orders for order-service), so they are unreachable from outside.
 *
 * <p>PRODUCTION NOTE: /internal must be restricted to the private network
 * (e.g. network policy / firewall / mesh authz) and never exposed at the
 * public gateway.
 */
@RestController
@RequestMapping("/internal")
public class InternalOwnerController {

    private final OrderRepository orderRepository;
    private final PolicyRepository policyRepository;

    public InternalOwnerController(OrderRepository orderRepository, PolicyRepository policyRepository) {
        this.orderRepository = orderRepository;
        this.policyRepository = policyRepository;
    }

    @GetMapping("/orders/{id}/owner")
    public OwnerResponse getOrderOwner(@PathVariable UUID id) {
        return orderRepository.findById(id)
                .map(o -> new OwnerResponse(o.getCustomerId()))
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found", null));
    }

    @GetMapping("/policies/{id}/owner")
    public OwnerResponse getPolicyOwner(@PathVariable UUID id) {
        return policyRepository.findById(id)
                .map(p -> new OwnerResponse(p.getCustomerId()))
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
    }
}
