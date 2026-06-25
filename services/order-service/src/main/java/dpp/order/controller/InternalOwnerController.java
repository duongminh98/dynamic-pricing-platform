package dpp.order.controller;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.order.dto.ExposureSegmentResponse;
import dpp.order.dto.OwnerResponse;
import dpp.order.dto.PolicyResponse;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.Policy;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.OrderRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyLifecycleService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final ExposureSegmentRepository exposureSegmentRepository;
    private final PolicyLifecycleService lifecycleService;

    public InternalOwnerController(OrderRepository orderRepository, PolicyRepository policyRepository,
                                   ExposureSegmentRepository exposureSegmentRepository,
                                   PolicyLifecycleService lifecycleService) {
        this.orderRepository = orderRepository;
        this.policyRepository = policyRepository;
        this.exposureSegmentRepository = exposureSegmentRepository;
        this.lifecycleService = lifecycleService;
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

    /**
     * Full policy detail for trusted peer services (e.g. claims-service FNOL,
     * which resolves the owning customer_id and coverage window). Returns the
     * same snake_case PolicyResponse contract as the public GET /policies/{id}
     * but without the per-customer ownership gate, since the caller is a trusted
     * internal service that enforces ownership itself.
     */
    @GetMapping("/policies/{id}")
    public PolicyResponse getPolicy(@PathVariable UUID id) {
        Policy p = policyRepository.findById(id)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
        return lifecycleService.toResponse(p);
    }

    /**
     * Exposure segments for a policy, used by claims-service to resolve the
     * covering segment + payout cap. snake_case contract identical to the public
     * GET /policies/{id}/exposure-segments.
     */
    @GetMapping("/policies/{id}/exposure-segments")
    public List<ExposureSegmentResponse> exposureSegments(@PathVariable UUID id) {
        if (!policyRepository.existsById(id)) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null);
        }
        return exposureSegmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(id).stream()
                .map(this::toSegmentResponse).collect(Collectors.toList());
    }

    private ExposureSegmentResponse toSegmentResponse(ExposureSegment seg) {
        ExposureSegmentResponse r = new ExposureSegmentResponse();
        r.setSegmentId(seg.getSegmentId());
        r.setPolicyId(seg.getPolicyId());
        r.setExposureSegmentSeq(seg.getExposureSegmentSeq());
        r.setSegmentStart(seg.getSegmentStart());
        r.setSegmentEnd(seg.getSegmentEnd());
        r.setEarnedExposureYears(seg.getEarnedExposureYears());
        r.setCoverageAmountVnd(seg.getCoverageAmountVnd());
        r.setDeductibleVnd(seg.getDeductibleVnd());
        return r;
    }
}
