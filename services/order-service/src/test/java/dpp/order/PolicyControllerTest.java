package dpp.order;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.security.CustomerId;
import dpp.order.controller.PolicyController;
import dpp.order.dto.CancelRequest;
import dpp.order.dto.EndorsementRequest;
import dpp.order.dto.EndorsementResult;
import dpp.order.dto.ExposureSegmentResponse;
import dpp.order.dto.PolicyDocumentResponse;
import dpp.order.dto.PolicyResponse;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyDocument;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PolicyControllerTest {

    private Jwt jwtFor(String subject) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(subject);
        return jwt;
    }

    private Policy policy(UUID id, UUID customerId) {
        Policy p = new Policy();
        p.setPolicyId(id);
        p.setOrderId(UUID.randomUUID());
        p.setCustomerId(customerId);
        p.setProductId("motor-basic");
        p.setStatus(PolicyStatus.active);
        p.setPolicyEffectiveDate(OffsetDateTime.now().minusDays(30));
        p.setPolicyExpirationDate(OffsetDateTime.now().plusDays(335));
        p.setRenewalNumber(0);
        p.setRenewal(false);
        p.setYearsSinceFirstPolicy(0);
        p.setPolicyCountPrior(0);
        p.setFinalPremiumVnd(1_000_000L);
        p.setCreatedAt(OffsetDateTime.now());
        return p;
    }

    @Test
    void myPoliciesReturnsPoliciesForCaller() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        PolicyLifecycleService lifecycleService = mock(PolicyLifecycleService.class);
        String subject = "keycloak-sub-123";
        UUID customerId = CustomerId.fromSubject(subject);
        Policy p = policy(UUID.randomUUID(), customerId);
        when(policyRepo.findByCustomerIdOrderByCreatedAtDesc(customerId)).thenReturn(List.of(p));

        PolicyResponse mockResp = new PolicyResponse();
        mockResp.setPolicyId(p.getPolicyId());
        when(lifecycleService.toResponse(p)).thenReturn(mockResp);

        PolicyController controller = new PolicyController(lifecycleService, policyRepo,
                mock(PolicyDocumentRepository.class), mock(ExposureSegmentRepository.class));
        List<PolicyResponse> result = controller.myPolicies(jwtFor(subject));

        assertEquals(1, result.size());
        assertEquals(p.getPolicyId(), result.get(0).getPolicyId());
    }

    @Test
    void getPolicyReturnsPolicyForOwner() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        PolicyLifecycleService lifecycleService = mock(PolicyLifecycleService.class);
        UUID policyId = UUID.randomUUID();
        String subject = "keycloak-sub-456";
        UUID customerId = CustomerId.fromSubject(subject);
        Policy p = policy(policyId, customerId);
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(p));

        PolicyResponse mockResp = new PolicyResponse();
        mockResp.setPolicyId(policyId);
        when(lifecycleService.toResponse(p)).thenReturn(mockResp);

        PolicyController controller = new PolicyController(lifecycleService, policyRepo,
                mock(PolicyDocumentRepository.class), mock(ExposureSegmentRepository.class));
        PolicyResponse result = controller.getPolicy(jwtFor(subject), policyId);

        assertEquals(policyId, result.getPolicyId());
    }

    @Test
    void getPolicyRejectsNonOwner() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Policy p = policy(policyId, customerId);
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(p));

        PolicyController controller = new PolicyController(mock(PolicyLifecycleService.class), policyRepo,
                mock(PolicyDocumentRepository.class), mock(ExposureSegmentRepository.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getPolicy(jwtFor("other-subject"), policyId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getPolicyRejectsUnknownPolicy() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        UUID policyId = UUID.randomUUID();
        when(policyRepo.findById(policyId)).thenReturn(Optional.empty());

        PolicyController controller = new PolicyController(mock(PolicyLifecycleService.class), policyRepo,
                mock(PolicyDocumentRepository.class), mock(ExposureSegmentRepository.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getPolicy(jwtFor("subject"), policyId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getDocumentReturnsDocumentForOwner() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        UUID policyId = UUID.randomUUID();
        String subject = "keycloak-sub-789";
        UUID customerId = CustomerId.fromSubject(subject);
        Policy p = policy(policyId, customerId);
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(p));

        PolicyDocument doc = new PolicyDocument();
        doc.setDocumentId(UUID.randomUUID());
        doc.setPolicyId(policyId);
        doc.setVersion(1);
        doc.setContent("{\"policy\":\"data\"}");
        doc.setCreatedAt(OffsetDateTime.now());
        when(docRepo.findLatestByPolicyId(policyId)).thenReturn(Optional.of(doc));

        PolicyController controller = new PolicyController(mock(PolicyLifecycleService.class), policyRepo,
                docRepo, mock(ExposureSegmentRepository.class));
        PolicyDocumentResponse result = controller.getDocument(jwtFor(subject), policyId);

        assertEquals(policyId, result.getPolicyId());
        assertEquals(1, result.getVersion());
    }

    @Test
    void getDocumentRejectsNonOwner() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Policy p = policy(policyId, customerId);
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(p));

        PolicyController controller = new PolicyController(mock(PolicyLifecycleService.class), policyRepo,
                mock(PolicyDocumentRepository.class), mock(ExposureSegmentRepository.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getDocument(jwtFor("other-subject"), policyId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getDocumentRejectsWhenNoDocument() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        UUID policyId = UUID.randomUUID();
        String subject = "keycloak-sub-no-doc";
        UUID customerId = CustomerId.fromSubject(subject);
        Policy p = policy(policyId, customerId);
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(p));
        when(docRepo.findLatestByPolicyId(policyId)).thenReturn(Optional.empty());

        PolicyController controller = new PolicyController(mock(PolicyLifecycleService.class), policyRepo,
                docRepo, mock(ExposureSegmentRepository.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getDocument(jwtFor(subject), policyId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void endorseDelegatesToLifecycleService() {
        PolicyLifecycleService lifecycleService = mock(PolicyLifecycleService.class);
        UUID policyId = UUID.randomUUID();
        EndorsementRequest req = new EndorsementRequest();
        req.setChange(java.util.Map.of("coverage_amount_vnd", 200_000_000L));
        req.setEffectiveDate(OffsetDateTime.now().plusDays(10));

        EndorsementResult mockResult = EndorsementResult.applied(new PolicyResponse());
        when(lifecycleService.endorse(eq(policyId), eq(req), anyString())).thenReturn(mockResult);

        PolicyController controller = new PolicyController(lifecycleService, mock(PolicyRepository.class),
                mock(PolicyDocumentRepository.class), mock(ExposureSegmentRepository.class));
        EndorsementResult result = controller.endorse(jwtFor("subject"), policyId, req);

        assertNotNull(result);
        assertEquals("applied", result.getStatus());
    }

    @Test
    void renewDelegatesToLifecycleService() {
        PolicyLifecycleService lifecycleService = mock(PolicyLifecycleService.class);
        UUID policyId = UUID.randomUUID();
        PolicyResponse mockResp = new PolicyResponse();
        mockResp.setPolicyId(policyId);
        when(lifecycleService.renew(eq(policyId), anyString())).thenReturn(mockResp);

        PolicyController controller = new PolicyController(lifecycleService, mock(PolicyRepository.class),
                mock(PolicyDocumentRepository.class), mock(ExposureSegmentRepository.class));
        PolicyResponse result = controller.renew(jwtFor("subject"), policyId);

        assertEquals(policyId, result.getPolicyId());
    }

    @Test
    void cancelDelegatesToLifecycleService() {
        PolicyLifecycleService lifecycleService = mock(PolicyLifecycleService.class);
        UUID policyId = UUID.randomUUID();
        CancelRequest req = new CancelRequest();
        req.setCancelDate(OffsetDateTime.now());

        PolicyResponse mockResp = new PolicyResponse();
        mockResp.setPolicyId(policyId);
        when(lifecycleService.cancel(eq(policyId), eq(req), anyString())).thenReturn(mockResp);

        PolicyController controller = new PolicyController(lifecycleService, mock(PolicyRepository.class),
                mock(PolicyDocumentRepository.class), mock(ExposureSegmentRepository.class));
        PolicyResponse result = controller.cancel(jwtFor("subject"), policyId, req);

        assertEquals(policyId, result.getPolicyId());
    }

    @Test
    void exposureSegmentsReturnsSegmentsForOwner() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        UUID policyId = UUID.randomUUID();
        String subject = "keycloak-sub-segs";
        UUID customerId = CustomerId.fromSubject(subject);
        Policy p = policy(policyId, customerId);
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(p));

        ExposureSegment seg = new ExposureSegment();
        seg.setSegmentId(UUID.randomUUID());
        seg.setPolicyId(policyId);
        seg.setExposureSegmentSeq(0);
        seg.setSegmentStart(OffsetDateTime.now().minusDays(30));
        seg.setSegmentEnd(OffsetDateTime.now().plusDays(335));
        seg.setEarnedExposureYears(0.0);
        seg.setCoverageAmountVnd(100_000_000L);
        seg.setDeductibleVnd(0L);
        seg.setRiskSnapshot("{}");
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId)).thenReturn(List.of(seg));

        PolicyController controller = new PolicyController(mock(PolicyLifecycleService.class), policyRepo,
                mock(PolicyDocumentRepository.class), segRepo);
        List<ExposureSegmentResponse> result = controller.exposureSegments(
                jwtFor(subject), policyId);

        assertEquals(1, result.size());
        assertEquals(100_000_000L, result.get(0).getCoverageAmountVnd());
    }

    @Test
    void exposureSegmentsRejectsNonOwner() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Policy p = policy(policyId, customerId);
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(p));

        PolicyController controller = new PolicyController(mock(PolicyLifecycleService.class), policyRepo,
                mock(PolicyDocumentRepository.class), mock(ExposureSegmentRepository.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.exposureSegments(jwtFor("other-subject"), policyId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }
}
