package dpp.customer.client;

import dpp.customer.dto.TokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class KeycloakClient {

    private final RestTemplate restTemplate;
    private final String authServerUrl;
    private final String realm;
    private final String adminUsername;
    private final String adminPassword;
    private final String loginClientId;

    public KeycloakClient(RestTemplate restTemplate,
                          @Value("${dpp.keycloak.auth-server-url}") String authServerUrl,
                          @Value("${dpp.keycloak.realm}") String realm,
                          @Value("${dpp.keycloak.admin-username}") String adminUsername,
                          @Value("${dpp.keycloak.admin-password}") String adminPassword,
                          @Value("${dpp.keycloak.login-client-id}") String loginClientId) {
        this.restTemplate = restTemplate;
        this.authServerUrl = authServerUrl;
        this.realm = realm;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.loginClientId = loginClientId;
    }

    public String getAdminToken() {
        String url = authServerUrl + "/realms/master/protocol/openid-connect/token";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "password");
        body.add("client_id", "admin-cli");
        body.add("username", adminUsername);
        body.add("password", adminPassword);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);
        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("Failed to obtain Keycloak admin token");
        }
        return (String) response.get("access_token");
    }

    @SuppressWarnings("unchecked")
    public String createUser(String email, String password) {
        String adminToken = getAdminToken();
        String url = authServerUrl + "/admin/realms/" + realm + "/users";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> user = Map.of(
                "username", email,
                "email", email,
                "enabled", true,
                "emailVerified", true,
                "credentials", List.of(Map.of(
                        "type", "password",
                        "value", password,
                        "temporary", false
                ))
        );

        URI location = restTemplate.postForLocation(url, new HttpEntity<>(user, headers));

        String userId;
        if (location != null) {
            String path = location.getPath();
            userId = path.substring(path.lastIndexOf('/') + 1);
        } else {
            userId = searchUserByEmail(email, adminToken);
        }

        try {
            assignRealmRole(userId, adminToken);
        } catch (Exception e) {
            log.warn("Failed to assign role to Keycloak user {}, cleaning up: {}", userId, e.getMessage());
            deleteUser(userId);
            throw e;
        }

        return userId;
    }

    @SuppressWarnings("unchecked")
    private String searchUserByEmail(String email, String adminToken) {
        String searchUrl = authServerUrl + "/admin/realms/" + realm
                + "/users?email=" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8)
                + "&exact=true";

        HttpHeaders searchHeaders = new HttpHeaders();
        searchHeaders.setBearerAuth(adminToken);
        searchHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                searchUrl,
                HttpMethod.GET,
                new HttpEntity<>(null, searchHeaders),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        List<Map<String, Object>> users = response.getBody();
        if (users == null || users.isEmpty()) {
            throw new IllegalStateException("Created Keycloak user not found: " + email);
        }
        return (String) users.get(0).get("id");
    }

    @SuppressWarnings("unchecked")
    private void assignRealmRole(String userId, String adminToken) {
        String roleUrl = authServerUrl + "/admin/realms/" + realm + "/roles/Customer";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, Object> roleRepresentation = restTemplate.exchange(
                roleUrl,
                HttpMethod.GET,
                new HttpEntity<>(null, headers),
                Map.class).getBody();
        if (roleRepresentation == null) {
            throw new IllegalStateException("Role 'Customer' not found in realm " + realm);
        }

        String mappingUrl = authServerUrl + "/admin/realms/" + realm
                + "/users/" + userId + "/role-mappings/realm";

        List<Map<String, Object>> rolePayload = List.of(roleRepresentation);
        restTemplate.postForLocation(mappingUrl, new HttpEntity<>(rolePayload, headers));
    }

    public void deleteUser(String keycloakUserId) {
        try {
            String adminToken = getAdminToken();
            String url = authServerUrl + "/admin/realms/" + realm + "/users/" + keycloakUserId;
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(adminToken);
            restTemplate.delete(url, new HttpEntity<>(null, headers));
        } catch (Exception e) {
            log.warn("Failed to delete Keycloak user {}: {}", keycloakUserId, e.getMessage());
        }
    }

    public TokenResponse login(String email, String password) {
        String url = authServerUrl + "/realms/" + realm + "/protocol/openid-connect/token";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "password");
        body.add("client_id", loginClientId);
        body.add("username", email);
        body.add("password", password);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);
            if (response == null) {
                return null;
            }
            String accessToken = (String) response.get("access_token");
            Number expiresIn = (Number) response.get("expires_in");
            if (accessToken == null) {
                return null;
            }
            return new TokenResponse(accessToken, expiresIn != null ? expiresIn.intValue() : 0);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                return null;
            }
            throw e;
        }
    }
}
