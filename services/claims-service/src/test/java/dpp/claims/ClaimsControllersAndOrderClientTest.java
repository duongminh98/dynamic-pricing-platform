package dpp.claims;

import dpp.claims.client.OrderClient;
import dpp.claims.controller.AdminClaimsController;
import dpp.claims.controller.ClaimsController;
import dpp.claims.dto.ApproveClaimRequest;
import dpp.claims.dto.ClaimResponse;
import dpp.claims.dto.FnolRequest;
import dpp.claims.dto.MisrepresentationRequest;
import dpp.claims.dto.RejectClaimRequest;
import dpp.claims.entity.ClaimExposureSegmentProjection;
import dpp.claims.entity.ClaimPolicyProjection;
import dpp.claims.entity.ClaimStatus;
import dpp.claims.repository.ClaimExposureSegmentProjectionRepository;
import dpp.claims.repository.ClaimPolicyProjectionRepository;
import dpp.claims.service.ClaimsService;
import dpp.common.api.ServiceException;
import dpp.common.dto.PageResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the thin controller delegates and the OrderClient projection
 * reader. Uses mocked ClaimsService / repositories — no web layer, no DB.
 */
@Tag("Feature: dynamic-pricing-platform")
class ClaimsControllersAndOrderClientTest {

    private Jwt jwtWithSubject(String subject) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(subject)
                .claim("scope", "openid")
                .build();
    }

    private Authentication authWith(String... roles) {
        Authentication auth = mock(Authentication.class);
        List<GrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r))
                .toList();
        doReturn(authorities).when(auth).getAuthorities();
        return auth;
    }

    // ── ClaimsController ──

    @Test
    void fnolAndSubmitDelegateToService() {
        ClaimsService service = mock(ClaimsService.class);
        ClaimsController controller = new ClaimsController(service);
        Jwt jwt = jwtWithSubject("customer-1");
        FnolRequest req = new FnolRequest();
        ClaimResponse resp = new ClaimResponse();
        when(service.fnol(eq("customer-1"), any())).thenReturn(resp);

        assertSame(resp, controller.fnol(jwt, req));
        assertSame(resp, controller.submitClaim(jwt, req));
        verify(service, times(2)).fnol("customer-1", req);
    }

    @Test
    void myClaimsDelegatesWithPaging() {
        ClaimsService service = mock(ClaimsService.class);
        ClaimsController controller = new ClaimsController(service);
        Jwt jwt = jwtWithSubject("customer-2");
        PageResponse<ClaimResponse> page = new PageResponse<>();
        when(service.myClaims("customer-2", 1, 50)).thenReturn(page);

        assertSame(page, controller.myClaims(jwt, 1, 50));
        verify(service).myClaims("customer-2", 1, 50);
    }

    @Test
    void getClaimPassesAdminFlagBasedOnAuthorities() {
        ClaimsService service = mock(ClaimsService.class);
        ClaimsController controller = new ClaimsController(service);
        Jwt jwt = jwtWithSubject("customer-3");
        UUID claimId = UUID.randomUUID();
        ClaimResponse resp = new ClaimResponse();
        when(service.getClaim(eq("customer-3"), eq(claimId), anyBoolean())).thenReturn(resp);

        controller.getClaim(jwt, authWith("ROLE_Administrator"), claimId);
        verify(service).getClaim("customer-3", claimId, true);

        controller.getClaim(jwt, authWith("ROLE_Customer"), claimId);
        verify(service).getClaim("customer-3", claimId, false);
    }

    @Test
    void approveRejectMisrepresentationSanctionDelegate() {
        ClaimsService service = mock(ClaimsService.class);
        ClaimsController controller = new ClaimsController(service);
        UUID claimId = UUID.randomUUID();

        ApproveClaimRequest approve = new ApproveClaimRequest();
        RejectClaimRequest reject = new RejectClaimRequest();
        MisrepresentationRequest misrep = new MisrepresentationRequest();
        ClaimResponse resp = new ClaimResponse();
        when(service.approve(claimId, approve)).thenReturn(resp);
        when(service.reject(claimId, reject)).thenReturn(resp);
        when(service.misrepresentation(eq(claimId), any())).thenReturn(resp);

        assertSame(resp, controller.approve(claimId, approve));
        assertSame(resp, controller.reject(claimId, reject));
        assertSame(resp, controller.misrepresentation(claimId, misrep));
        assertSame(resp, controller.sanction(claimId, misrep));

        verify(service).approve(claimId, approve);
        verify(service).reject(claimId, reject);
        verify(service, times(2)).misrepresentation(claimId, misrep);
    }

    // ── AdminClaimsController ──

    @Test
    void adminListAndGetDelegate() {
        ClaimsService service = mock(ClaimsService.class);
        AdminClaimsController controller = new AdminClaimsController(service);
        UUID customerId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        PageResponse<ClaimResponse> page = new PageResponse<>();
        ClaimResponse resp = new ClaimResponse();
        when(service.adminListClaims(ClaimStatus.pending, customerId, policyId, 0, 20)).thenReturn(page);
        when(service.getClaim(null, claimId, true)).thenReturn(resp);

        assertSame(page, controller.listClaims(ClaimStatus.pending, customerId, policyId, 0, 20));
        assertSame(resp, controller.getClaim(claimId));
        verify(service).adminListClaims(ClaimStatus.pending, customerId, policyId, 0, 20);
        verify(service).getClaim(null, claimId, true);
    }

    // ── OrderClient projection reader ──

    private ClaimPolicyProjection policyProjection(UUID policyId, UUID customerId, UUID quoteId) {
        ClaimPolicyProjection p = new ClaimPolicyProjection();
        p.setPolicyId(policyId);
        p.setCustomerId(customerId);
        p.setQuoteId(quoteId);
        p.setStatus("active");
        p.setLine("health");
        return p;
    }

    @Test
    void getPolicyReturnsMappedFields() {
        ClaimPolicyProjectionRepository policyRepo = mock(ClaimPolicyProjectionRepository.class);
        ClaimExposureSegmentProjectionRepository segmentRepo = mock(ClaimExposureSegmentProjectionRepository.class);
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(policyProjection(policyId, customerId, quoteId)));

        OrderClient client = new OrderClient(policyRepo, segmentRepo);
        Map<String, Object> result = client.getPolicy(policyId);

        assertEquals(customerId.toString(), result.get("customer_id"));
        assertEquals("active", result.get("status"));
        assertEquals(quoteId.toString(), result.get("quote_id"));
        assertEquals("health", result.get("line"));
    }

    @Test
    void getPolicyThrowsWhenMissing() {
        ClaimPolicyProjectionRepository policyRepo = mock(ClaimPolicyProjectionRepository.class);
        ClaimExposureSegmentProjectionRepository segmentRepo = mock(ClaimExposureSegmentProjectionRepository.class);
        UUID policyId = UUID.randomUUID();
        when(policyRepo.findById(policyId)).thenReturn(Optional.empty());

        OrderClient client = new OrderClient(policyRepo, segmentRepo);
        assertThrows(ServiceException.class, () -> client.getPolicy(policyId));
    }

    @Test
    void getQuoteIdByPolicyReturnsQuoteAndLine() {
        ClaimPolicyProjectionRepository policyRepo = mock(ClaimPolicyProjectionRepository.class);
        ClaimExposureSegmentProjectionRepository segmentRepo = mock(ClaimExposureSegmentProjectionRepository.class);
        UUID policyId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(policyProjection(policyId, UUID.randomUUID(), quoteId)));

        OrderClient client = new OrderClient(policyRepo, segmentRepo);
        Map<String, Object> result = client.getQuoteIdByPolicy(policyId);

        assertEquals(quoteId, result.get("quote_id"));
        assertEquals("health", result.get("line"));
    }

    @Test
    void getQuoteIdByPolicyThrowsWhenMissing() {
        ClaimPolicyProjectionRepository policyRepo = mock(ClaimPolicyProjectionRepository.class);
        ClaimExposureSegmentProjectionRepository segmentRepo = mock(ClaimExposureSegmentProjectionRepository.class);
        UUID policyId = UUID.randomUUID();
        when(policyRepo.findById(policyId)).thenReturn(Optional.empty());

        OrderClient client = new OrderClient(policyRepo, segmentRepo);
        assertThrows(ServiceException.class, () -> client.getQuoteIdByPolicy(policyId));
    }

    @Test
    void getExposureSegmentsMapsRows() {
        ClaimPolicyProjectionRepository policyRepo = mock(ClaimPolicyProjectionRepository.class);
        ClaimExposureSegmentProjectionRepository segmentRepo = mock(ClaimExposureSegmentProjectionRepository.class);
        UUID policyId = UUID.randomUUID();

        ClaimExposureSegmentProjection seg = new ClaimExposureSegmentProjection();
        seg.setPolicyId(policyId);
        seg.setExposureSegmentSeq(0);
        seg.setSegmentStart(OffsetDateTime.now().minusDays(10));
        seg.setSegmentEnd(OffsetDateTime.now().plusDays(355));
        seg.setCoverageAmountVnd(100_000_000L);
        seg.setDeductibleVnd(500_000L);
        when(segmentRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId)).thenReturn(List.of(seg));

        OrderClient client = new OrderClient(policyRepo, segmentRepo);
        List<Map<String, Object>> segments = client.getExposureSegments(policyId);

        assertEquals(1, segments.size());
        Map<String, Object> mapped = segments.get(0);
        assertEquals(0, mapped.get("exposure_segment_seq"));
        assertEquals(100_000_000L, mapped.get("coverage_amount_vnd"));
        assertEquals(500_000L, mapped.get("deductible_vnd"));
        assertEquals(seg.getSegmentStart().toString(), mapped.get("segment_start"));
        assertEquals(seg.getSegmentEnd().toString(), mapped.get("segment_end"));
    }
}
