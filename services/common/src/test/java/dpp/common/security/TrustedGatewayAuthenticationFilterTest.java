package dpp.common.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class TrustedGatewayAuthenticationFilterTest {

    private final TrustedGatewayAuthenticationFilter filter = new TrustedGatewayAuthenticationFilter();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsJwtAuthenticationFromTrustedHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/test");
        request.addHeader(TrustedGatewayAuthenticationFilter.SUBJECT_HEADER, "user-123");
        request.addHeader(TrustedGatewayAuthenticationFilter.ROLES_HEADER, "Customer, Administrator");
        request.addHeader(TrustedGatewayAuthenticationFilter.ISSUER_HEADER, "http://localhost:8080/realms/dynamic-pricing");
        request.addHeader(TrustedGatewayAuthenticationFilter.CLIENT_ID_HEADER, "mini-app");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(authentication);
            assertTrue(authentication.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals("ROLE_Administrator")));
            assertInstanceOf(Jwt.class, authentication.getPrincipal());
            Jwt jwt = (Jwt) authentication.getPrincipal();
            assertEquals("user-123", jwt.getSubject());
            assertEquals("mini-app", jwt.getClaimAsString("azp"));
        };

        filter.doFilter(request, response, chain);
    }

    @Test
    void forwardsEmailHeaderAsClaim() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customers/me");
        request.addHeader(TrustedGatewayAuthenticationFilter.SUBJECT_HEADER, "user-123");
        request.addHeader(TrustedGatewayAuthenticationFilter.ROLES_HEADER, "Customer");
        request.addHeader(TrustedGatewayAuthenticationFilter.EMAIL_HEADER, "new.user@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            assertEquals("new.user@example.com", jwt.getClaimAsString("email"));
            assertEquals("new.user@example.com", jwt.getClaimAsString("preferred_username"));
        };

        filter.doFilter(request, response, chain);
    }

    @Test
    void decodesBase64Utf8NameWithDiacritics() throws Exception {
        String name = "Trần Thị Bình";
        String b64 = Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customers/me");
        request.addHeader(TrustedGatewayAuthenticationFilter.SUBJECT_HEADER, "user-123");
        request.addHeader(TrustedGatewayAuthenticationFilter.ROLES_HEADER, "Customer");
        request.addHeader(TrustedGatewayAuthenticationFilter.NAME_B64_HEADER, b64);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            assertEquals(name, jwt.getClaimAsString("name"));
        };

        filter.doFilter(request, response, chain);
    }

    @Test
    void ignoresRequestsWithoutGatewaySubject() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertNull(SecurityContextHolder.getContext().getAuthentication()));
    }
}
