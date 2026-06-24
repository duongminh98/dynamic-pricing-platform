package dpp.order.controller;

import dpp.order.dto.*;
import dpp.order.entity.*;
import dpp.order.repository.*;
import dpp.order.service.PolicyLifecycleService;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/policies")
public class PolicyController {

    private final PolicyLifecycleService lifecycleService;
    private final PolicyRepository policyRepository;
    private final PolicyDocumentRepository documentRepository;

    public PolicyController(PolicyLifecycleService lifecycleService, PolicyRepository policyRepository,
                            PolicyDocumentRepository documentRepository) {
        this.lifecycleService = lifecycleService;
        this.policyRepository = policyRepository;
        this.documentRepository = documentRepository;
    }

    @GetMapping
    public List<PolicyResponse> myPolicies(@AuthenticationPrincipal Jwt jwt) {
        UUID customerId = UUID.nameUUIDFromBytes(jwt.getSubject().getBytes());
        return policyRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(lifecycleService::toResponse).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public PolicyResponse getPolicy(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Policy p = policyRepository.findById(id)
                .orElseThrow(() -> new ServiceException(ErrorCode.FORBIDDEN_RESOURCE));
        UUID customerId = UUID.nameUUIDFromBytes(jwt.getSubject().getBytes());
        if (!p.getCustomerId().equals(customerId)) {
            throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return lifecycleService.toResponse(p);
    }

    @GetMapping("/{id}/document")
    public PolicyDocument getDocument(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Policy p = policyRepository.findById(id)
                .orElseThrow(() -> new ServiceException(ErrorCode.FORBIDDEN_RESOURCE));
        UUID customerId = UUID.nameUUIDFromBytes(jwt.getSubject().getBytes());
        if (!p.getCustomerId().equals(customerId)) {
            throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return documentRepository.findLatestByPolicyId(id)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Document not found", null));
    }

    @PostMapping("/{id}/endorsements")
    public PolicyResponse endorse(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                  @Valid @RequestBody EndorsementRequest request) {
        return lifecycleService.endorse(id, request, jwt.getSubject());
    }

    @PostMapping("/{id}/renew")
    public PolicyResponse renew(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return lifecycleService.renew(id, jwt.getSubject());
    }

    @PostMapping("/{id}/cancel")
    public PolicyResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                 @Valid @RequestBody CancelRequest request) {
        return lifecycleService.cancel(id, request, jwt.getSubject());
    }
}
