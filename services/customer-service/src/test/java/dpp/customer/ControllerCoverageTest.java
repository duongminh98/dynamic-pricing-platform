package dpp.customer;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.controller.AuthController;
import dpp.customer.controller.ProfileController;
import dpp.customer.dto.LoginRequest;
import dpp.customer.dto.ProfileRequest;
import dpp.customer.dto.ProfileResponse;
import dpp.customer.dto.RegisterRequest;
import dpp.customer.dto.TokenResponse;
import dpp.customer.service.AuthService;
import dpp.customer.service.ProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControllerCoverageTest {

    private Jwt jwtFor(String subject) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(subject);
        return jwt;
    }

    // ── AuthController ──

    @Test
    void authControllerRegisterDelegatesToService() {
        AuthService authService = mock(AuthService.class);
        AuthController controller = new AuthController(authService);

        RegisterRequest req = new RegisterRequest();
        req.setEmail("test@example.com");
        req.setPassword("password123");

        controller.register(req);

        verify(authService, times(1)).register(req);
    }

    @Test
    void authControllerLoginReturnsToken() {
        AuthService authService = mock(AuthService.class);
        AuthController controller = new AuthController(authService);

        LoginRequest req = new LoginRequest();
        req.setEmail("test@example.com");
        req.setPassword("password123");

        TokenResponse mockToken = new TokenResponse("access-token-123", 3600);
        when(authService.login(req)).thenReturn(mockToken);

        TokenResponse result = controller.login(req);

        assertEquals("access-token-123", result.getAccessToken());
        assertEquals(3600, result.getExpiresIn());
    }

    // ── ProfileController ──

    @Test
    void profileControllerUpdateDelegatesToService() {
        ProfileService profileService = mock(ProfileService.class);
        ProfileController controller = new ProfileController(profileService);

        ProfileRequest req = new ProfileRequest();
        req.setLine("health");
        req.setAge(30);
        req.setGender("male");
        req.setProvince("HN");
        req.setRegion("north");
        req.setUrbanTier("urban");
        req.setOccupation("engineer");
        req.setIncomeLevel("middle");
        req.setMonthlyIncomeVnd(20_000_000L);
        req.setMaritalStatus("single");
        req.setLineAttributes(Map.of("smoker", "no", "chronic_disease", "no", "diabetes", "no",
                "blood_pressure_problem", "no", "hospitalized_last_12m", "no"));

        ProfileResponse mockResp = new ProfileResponse();
        mockResp.setCustomerId(UUID.randomUUID());
        when(profileService.upsertProfile("subject-123", req)).thenReturn(mockResp);

        ProfileResponse result = controller.updateProfile(jwtFor("subject-123"), req);

        assertNotNull(result);
        verify(profileService, times(1)).upsertProfile("subject-123", req);
    }

    @Test
    void profileControllerGetProfileDelegatesToService() {
        ProfileService profileService = mock(ProfileService.class);
        ProfileController controller = new ProfileController(profileService);

        ProfileResponse mockResp = new ProfileResponse();
        mockResp.setCustomerId(UUID.randomUUID());
        when(profileService.getLatestProfile("subject-456")).thenReturn(mockResp);

        ProfileResponse result = controller.getProfile(jwtFor("subject-456"));

        assertNotNull(result);
        verify(profileService, times(1)).getLatestProfile("subject-456");
    }
}
