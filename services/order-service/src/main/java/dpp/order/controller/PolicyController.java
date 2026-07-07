package dpp.order.controller;

import dpp.order.dto.*;
import dpp.order.entity.*;
import dpp.order.repository.*;
import dpp.order.service.PolicyLifecycleService;
import dpp.common.security.CustomerId;
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
    private final ExposureSegmentRepository exposureSegmentRepository;

    public PolicyController(PolicyLifecycleService lifecycleService, PolicyRepository policyRepository,
                            PolicyDocumentRepository documentRepository,
                            ExposureSegmentRepository exposureSegmentRepository) {
        this.lifecycleService = lifecycleService;
        this.policyRepository = policyRepository;
        this.documentRepository = documentRepository;
        this.exposureSegmentRepository = exposureSegmentRepository;
    }

    @GetMapping
    public List<PolicyResponse> myPolicies(@AuthenticationPrincipal Jwt jwt) {
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        return policyRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(lifecycleService::toResponse).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public PolicyResponse getPolicy(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return lifecycleService.toResponse(ownedPolicyOr404(id, jwt));
    }

    @GetMapping("/{id}/document")
    public PolicyDocumentResponse getDocument(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        ownedPolicyOr404(id, jwt);
        PolicyDocument doc = documentRepository.findLatestByPolicyId(id)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Document not found", null));
        PolicyDocumentResponse resp = new PolicyDocumentResponse();
        resp.setDocumentId(doc.getDocumentId());
        resp.setPolicyId(doc.getPolicyId());
        resp.setVersion(doc.getVersion());
        resp.setContent(doc.getContent());
        resp.setCreatedAt(doc.getCreatedAt());
        return resp;
    }

    @PostMapping("/{id}/endorsements/preview")
    public EndorsementPreviewResponse previewEndorsement(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                          @Valid @RequestBody EndorsementRequest request) {
        return lifecycleService.previewEndorsement(id, request, jwt.getSubject());
    }

    @GetMapping("/{id}/endorsements/preview/{pricingRequestId}")
    public EndorsementPreviewResponse getEndorsementPreview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                             @PathVariable UUID pricingRequestId) {
        return lifecycleService.getEndorsementPreview(id, pricingRequestId, jwt.getSubject());
    }

    @PostMapping("/{id}/endorsements")
    public EndorsementResult endorse(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @Valid @RequestBody EndorsementRequest request) {
        return lifecycleService.endorse(id, request, jwt.getSubject());
    }

    @GetMapping("/{id}/endorsements")
    public PageResponse<EndorsementRequestResponse> listEndorsements(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        return lifecycleService.policyEndorsementsPaged(id, jwt.getSubject(),
                org.springframework.data.domain.PageRequest.of(page, size,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
    }

    @GetMapping("/{id}/endorsements/{endorsementId}")
    public EndorsementRequestResponse getEndorsement(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                      @PathVariable UUID endorsementId) {
        return lifecycleService.getEndorsement(id, endorsementId, jwt.getSubject());
    }

    @PostMapping("/{id}/endorsements/{endorsementId}/cancel")
    public EndorsementCancelResponse cancelEndorsement(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                       @PathVariable UUID endorsementId,
                                                       @RequestBody(required = false) CancelEndorsementRequest request) {
        String reason = request != null ? request.getReason() : null;
        return lifecycleService.cancelEndorsement(id, endorsementId, jwt.getSubject(), reason);
    }

    @PostMapping("/{id}/renew/preview")
    public RenewalPreviewResponse previewRenewal(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return lifecycleService.previewRenewal(id, jwt.getSubject());
    }

    @PostMapping("/{id}/renew")
    public RenewalResponse renew(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return lifecycleService.renew(id, jwt.getSubject());
    }

    @PostMapping("/{id}/cancel")
    public CancelResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                 @Valid @RequestBody CancelRequest request) {
        return lifecycleService.cancel(id, request, jwt.getSubject());
    }

    @GetMapping("/{id}/exposure-segments")
    public List<ExposureSegmentResponse> exposureSegments(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        ownedPolicyOr404(id, jwt);
        return exposureSegmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(id).stream()
                .map(this::toSegmentResponse).collect(Collectors.toList());
    }

    private Policy ownedPolicyOr404(UUID id, Jwt jwt) {
        Policy p = policyRepository.findById(id)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        if (!p.getCustomerId().equals(customerId)) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null);
        }
        return p;
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
