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

    @Test
    void loginReturnsTokenResponse() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(Map.of("access_token", "user-token", "expires_in", 3600));

        KeycloakClient client = clientWith(rt);
        TokenResponse result = client.login("test@example.com", "password");

        assertNotNull(result);
        assertEquals("user-token", result.getAccessToken());
        assertEquals(3600, result.getExpiresIn());
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
