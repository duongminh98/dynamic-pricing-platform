package dpp.customer;

import dpp.customer.controller.ProfileController;
import dpp.customer.dto.BaseProfileRequest;
import dpp.customer.dto.LineProfileRequest;
import dpp.customer.dto.LineProfileResponse;
import dpp.customer.dto.ProfileResponse;
import dpp.customer.service.ProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControllerCoverageTest {

    private Jwt jwtFor(String subject) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(subject);
        when(jwt.getClaimAsString("email")).thenReturn("test@example.com");
        return jwt;
    }

    @Test
    void profileControllerUpdateBaseDelegatesToService() {
        ProfileService profileService = mock(ProfileService.class);
        ProfileController controller = new ProfileController(profileService);

        BaseProfileRequest req = new BaseProfileRequest();
        req.setAge(30);
        req.setGender("male");
        req.setProvince("Ha Noi");
        req.setOccupation("engineer");
        req.setMonthlyIncomeVnd(20_000_000L);
        req.setMaritalStatus("single");

        ProfileResponse mockResp = new ProfileResponse();
        mockResp.setCustomerId(UUID.randomUUID());
        when(profileService.updateBaseProfile("subject-123", "test@example.com", req)).thenReturn(mockResp);

        ProfileResponse result = controller.updateBaseProfile(jwtFor("subject-123"), req);

        assertNotNull(result);
        verify(profileService, times(1)).updateBaseProfile("subject-123", "test@example.com", req);
    }

    @Test
    void profileControllerGetProfileDelegatesToService() {
        ProfileService profileService = mock(ProfileService.class);
        ProfileController controller = new ProfileController(profileService);

        ProfileResponse mockResp = new ProfileResponse();
        mockResp.setCustomerId(UUID.randomUUID());
        when(profileService.getProfile("subject-456", "test@example.com")).thenReturn(mockResp);

        ProfileResponse result = controller.getProfile(jwtFor("subject-456"));

        assertNotNull(result);
        verify(profileService, times(1)).getProfile("subject-456", "test@example.com");
    }

    @Test
    void profileControllerUpsertLineDelegatesToService() {
        ProfileService profileService = mock(ProfileService.class);
        ProfileController controller = new ProfileController(profileService);

        LineProfileRequest req = new LineProfileRequest();
        req.setLineAttributes(Map.of("height_cm", 170, "weight_kg", 65, "bmi", 22.5,
                "smoker", false, "chronic_disease", false, "diabetes", false,
                "blood_pressure_problem", false, "major_surgeries_count", 0,
                "hospitalized_last_12m", false, "medical_visit_count_12m", 1));

        LineProfileResponse mockResp = new LineProfileResponse();
        mockResp.setLine("health");
        when(profileService.upsertLineProfile("subject-789", "health", req)).thenReturn(mockResp);

        LineProfileResponse result = controller.upsertLineProfile(jwtFor("subject-789"), "health", req);

        assertNotNull(result);
        assertEquals("health", result.getLine());
        verify(profileService, times(1)).upsertLineProfile("subject-789", "health", req);
    }
}
