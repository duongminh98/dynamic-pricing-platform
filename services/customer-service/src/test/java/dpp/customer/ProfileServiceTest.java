package dpp.customer;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.dto.BaseProfileRequest;
import dpp.customer.dto.LineProfileRequest;
import dpp.customer.dto.LineProfileResponse;
import dpp.customer.dto.ProfileResponse;
import dpp.customer.entity.Account;
import dpp.customer.entity.CustomerProfile;
import dpp.customer.entity.ProfileVersion;
import dpp.customer.repository.AccountRepository;
import dpp.customer.repository.CustomerProfileRepository;
import dpp.customer.repository.ProfileVersionRepository;
import dpp.customer.service.ProfileService;
import dpp.customer.validator.ProfileValidator;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileServiceTest {

    private Account account(String subject) {
        Account a = new Account();
        a.setAccountId(UUID.randomUUID());
        a.setKeycloakSubject(subject);
        a.setEmail("user@example.com");
        a.setCreatedAt(OffsetDateTime.now());
        return a;
    }

    private BaseProfileRequest validBaseRequest() {
        BaseProfileRequest r = new BaseProfileRequest();
        r.setAge(30);
        r.setGender("male");
        r.setProvince("Ha Noi");
        r.setOccupation("engineer");
        r.setMonthlyIncomeVnd(20_000_000L);
        r.setMaritalStatus("single");
        return r;
    }

    private LineProfileRequest validLineRequest() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("height_cm", 170);
        attrs.put("weight_kg", 65);
        attrs.put("bmi", 22.5);
        attrs.put("smoker", false);
        attrs.put("chronic_disease", false);
        attrs.put("diabetes", false);
        attrs.put("blood_pressure_problem", false);
        attrs.put("major_surgeries_count", 0);
        attrs.put("hospitalized_last_12m", false);
        attrs.put("medical_visit_count_12m", 1);
        LineProfileRequest r = new LineProfileRequest();
        r.setLineAttributes(attrs);
        return r;
    }

    private CustomerProfile profileWithAge(int age) {
        CustomerProfile p = new CustomerProfile();
        p.setCustomerId(UUID.randomUUID());
        p.setAge(age);
        p.setGender("male");
        p.setProvince("Ha Noi");
        p.setRegion("Red River Delta");
        p.setUrbanTier("tier1");
        p.setOccupation("engineer");
        p.setIncomeLevel("middle");
        p.setMonthlyIncomeVnd(20_000_000L);
        p.setMaritalStatus("single");
        p.setUpdatedAt(OffsetDateTime.now());
        return p;
    }

    @Test
    void updateBaseProfileCreatesNewWhenNoneExists() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        ProfileVersionRepository versionRepo = mock(ProfileVersionRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        String subject = "test-subject-1";
        Account acc = account(subject);

        when(accountRepo.findByKeycloakSubject(subject)).thenReturn(Optional.of(acc));
        when(profileRepo.findByAccount_AccountId(acc.getAccountId()))
                .thenReturn(null)
                .thenReturn(profileWithAge(30));
        when(profileRepo.save(any(CustomerProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.findLatestPerLine(any(UUID.class))).thenReturn(List.of());

        ProfileService svc = new ProfileService(profileRepo, versionRepo, accountRepo, new ProfileValidator());
        ProfileResponse resp = svc.updateBaseProfile(subject, validBaseRequest());

        assertNotNull(resp);
        assertEquals(30, resp.getAge());
        assertEquals("male", resp.getGender());
        verify(profileRepo, times(1)).save(any());
    }

    @Test
    void updateBaseProfileUpdatesExisting() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        ProfileVersionRepository versionRepo = mock(ProfileVersionRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        String subject = "test-subject-2";
        Account acc = account(subject);
        CustomerProfile existing = new CustomerProfile();
        existing.setCustomerId(UUID.randomUUID());
        existing.setAccount(acc);
        existing.setAge(25);

        when(accountRepo.findByKeycloakSubject(subject)).thenReturn(Optional.of(acc));
        when(profileRepo.findByAccount_AccountId(acc.getAccountId())).thenReturn(existing);
        when(profileRepo.save(any(CustomerProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.findLatestPerLine(any(UUID.class))).thenReturn(List.of());

        ProfileService svc = new ProfileService(profileRepo, versionRepo, accountRepo, new ProfileValidator());
        BaseProfileRequest req = validBaseRequest();
        req.setAge(35);
        ProfileResponse resp = svc.updateBaseProfile(subject, req);

        assertEquals(35, resp.getAge());
        verify(profileRepo, times(1)).save(existing);
    }

    @Test
    void updateBaseProfileRejectsUnknownSubject() {
        AccountRepository accountRepo = mock(AccountRepository.class);
        when(accountRepo.findByKeycloakSubject("ghost")).thenReturn(Optional.empty());

        ProfileService svc = new ProfileService(
                mock(CustomerProfileRepository.class), mock(ProfileVersionRepository.class),
                accountRepo, new ProfileValidator());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.updateBaseProfile("ghost", validBaseRequest()));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
    }

    @Test
    void upsertLineProfileCreatesVersion() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        ProfileVersionRepository versionRepo = mock(ProfileVersionRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        String subject = "test-subject-3";
        Account acc = account(subject);
        CustomerProfile profile = new CustomerProfile();
        profile.setCustomerId(UUID.randomUUID());
        profile.setAccount(acc);

        when(accountRepo.findByKeycloakSubject(subject)).thenReturn(Optional.of(acc));
        when(profileRepo.findByAccount_AccountId(acc.getAccountId())).thenReturn(profile);
        when(versionRepo.save(any(ProfileVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        ProfileService svc = new ProfileService(profileRepo, versionRepo, accountRepo, new ProfileValidator());
        LineProfileResponse resp = svc.upsertLineProfile(subject, "health", validLineRequest());

        assertNotNull(resp);
        assertEquals("health", resp.getLine());
        verify(versionRepo, times(1)).save(any());
    }

    @Test
    void upsertLineProfileRejectsWhenNoBaseProfile() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        String subject = "test-subject-4";
        Account acc = account(subject);

        when(accountRepo.findByKeycloakSubject(subject)).thenReturn(Optional.of(acc));
        when(profileRepo.findByAccount_AccountId(acc.getAccountId())).thenReturn(null);

        ProfileService svc = new ProfileService(
                profileRepo, mock(ProfileVersionRepository.class), accountRepo, new ProfileValidator());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.upsertLineProfile(subject, "health", validLineRequest()));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getProfileRejectsUnknownSubject() {
        AccountRepository accountRepo = mock(AccountRepository.class);
        when(accountRepo.findByKeycloakSubject("ghost")).thenReturn(Optional.empty());

        ProfileService svc = new ProfileService(
                mock(CustomerProfileRepository.class), mock(ProfileVersionRepository.class),
                accountRepo, new ProfileValidator());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.getProfile("ghost"));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
    }

    @Test
    void getProfileRejectsWhenNoProfile() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        String subject = "test-subject-5";
        Account acc = account(subject);

        when(accountRepo.findByKeycloakSubject(subject)).thenReturn(Optional.of(acc));
        when(profileRepo.findByAccount_AccountId(acc.getAccountId())).thenReturn(null);

        ProfileService svc = new ProfileService(
                profileRepo, mock(ProfileVersionRepository.class), accountRepo, new ProfileValidator());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.getProfile(subject));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }
}
