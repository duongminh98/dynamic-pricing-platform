package dpp.customer;

import dpp.customer.client.KeycloakClient;
import dpp.customer.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KeycloakClientCoverageTest {

    private KeycloakClient clientWith(RestTemplate restTemplate) {
        return new KeycloakClient(restTemplate,
                "http://localhost:8180", "dpp", "admin", "admin", "dpp-client");
    }

    @Test
    void getAdminTokenReturnsToken() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(Map.of("access_token", "admin-token-xyz"));

        KeycloakClient client = clientWith(rt);
        String token = client.getAdminToken();

        assertEquals("admin-token-xyz", token);
    }

    @Test
    void getAdminTokenThrowsWhenResponseNull() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(null);

        KeycloakClient client = clientWith(rt);
        assertThrows(IllegalStateException.class, client::getAdminToken);
    }

    @Test
    void getAdminTokenThrowsWhenTokenMissing() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(Map.of());

        KeycloakClient client = clientWith(rt);
        assertThrows(IllegalStateException.class, client::getAdminToken);
    }

    @Test
    void createUserReturnsUserIdFromLocation() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(Map.of("access_token", "admin-token"));
        when(rt.postForLocation(anyString(), any()))
                .thenReturn(URI.create("http://localhost:8180/admin/realms/dpp/users/abc-123"));

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> roleResp = new ResponseEntity<>(Map.of("id", "role-id", "name", "Customer"), HttpStatus.OK);
        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(roleResp);

        KeycloakClient client = clientWith(rt);
        String userId = client.createUser("test@example.com", "password123");

        assertEquals("abc-123", userId);
    }

    private String fakeJwt(String subject, List<String> roles) {
        String payload = "{\"sub\":\"" + subject + "\",\"realm_access\":{\"roles\":" + rolesAsString(roles) + "}}";
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return "header." + encodedPayload + ".signature";
    }

    private String rolesAsString(List<String> roles) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < roles.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(roles.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    @Test
    void loginReturnsTokenResponse() {
        RestTemplate rt = mock(RestTemplate.class);
        String jwt = fakeJwt("test-subject", List.of("Customer"));
        when(rt.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(Map.of("access_token", jwt, "expires_in", 3600));

        KeycloakClient client = clientWith(rt);
        TokenResponse result = client.login("test@example.com", "password");

        assertNotNull(result);
        assertEquals(jwt, result.getAccessToken());
        assertEquals(3600, result.getExpiresIn());
        assertEquals("Bearer", result.getTokenType());
        assertEquals(List.of("Customer"), result.getRoles());
        assertEquals("test-subject", result.getSubject());
    }

    @Test
    void loginReturnsNullOnNullResponse() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(null);

        KeycloakClient client = clientWith(rt);
        TokenResponse result = client.login("test@example.com", "password");

        assertNull(result);
    }

    @Test
    void loginReturnsNullWhenAccessTokenMissing() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(Map.of("expires_in", 3600));

        KeycloakClient client = clientWith(rt);
        TokenResponse result = client.login("test@example.com", "password");

        assertNull(result);
    }

    @Test
    void loginReturnsNullOnUnauthorized() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "401", HttpHeaders.EMPTY, new byte[0], null));

        KeycloakClient client = clientWith(rt);
        TokenResponse result = client.login("test@example.com", "wrong");

        assertNull(result);
    }

    @Test
    void loginReturnsNullOnBadRequest() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "400", HttpHeaders.EMPTY, new byte[0], null));

        KeycloakClient client = clientWith(rt);
        TokenResponse result = client.login("test@example.com", "wrong");

        assertNull(result);
    }

    @Test
    void loginThrowsOnServerError() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "500", HttpHeaders.EMPTY, new byte[0], null));

        KeycloakClient client = clientWith(rt);
        assertThrows(HttpClientErrorException.class, () -> client.login("test@example.com", "password"));
    }

    @Test
    void deleteUserSilentlyHandlesException() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(Map.of("access_token", "admin-token"));
        doThrow(new RuntimeException("Network error"))
                .when(rt).delete(anyString(), any(HttpEntity.class));

        KeycloakClient client = clientWith(rt);
        assertDoesNotThrow(() -> client.deleteUser("user-id-123"));
    }
}
