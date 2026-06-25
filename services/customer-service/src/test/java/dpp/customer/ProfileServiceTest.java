package dpp.customer;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.dto.ProfileRequest;
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

    private ProfileRequest validRequest() {
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

        ProfileRequest r = new ProfileRequest();
        r.setAge(30);
        r.setGender("male");
        r.setProvince("Ha Noi");
        r.setRegion("Red River Delta");
        r.setUrbanTier("tier1");
        r.setOccupation("engineer");
        r.setIncomeLevel("middle");
        r.setMonthlyIncomeVnd(20_000_000L);
        r.setMaritalStatus("single");
        r.setLine("health");
        r.setLineAttributes(attrs);
        return r;
    }

    @Test
    void upsertProfileCreatesNewWhenNoneExists() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        ProfileVersionRepository versionRepo = mock(ProfileVersionRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        String subject = "test-subject-1";
        Account acc = account(subject);

        when(accountRepo.findByKeycloakSubject(subject)).thenReturn(Optional.of(acc));
        when(profileRepo.findByAccount_AccountId(acc.getAccountId())).thenReturn(null);
        when(profileRepo.save(any(CustomerProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any(ProfileVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        ProfileService svc = new ProfileService(profileRepo, versionRepo, accountRepo, new ProfileValidator());
        ProfileResponse resp = svc.upsertProfile(subject, validRequest());

        assertNotNull(resp);
        assertEquals(30, resp.getAge());
        assertEquals("male", resp.getGender());
        assertEquals("health", resp.getLine());
        verify(profileRepo, times(1)).save(any());
        verify(versionRepo, times(1)).save(any());
    }

    @Test
    void upsertProfileUpdatesExisting() {
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
        when(versionRepo.save(any(ProfileVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        ProfileService svc = new ProfileService(profileRepo, versionRepo, accountRepo, new ProfileValidator());
        ProfileRequest req = validRequest();
        req.setAge(35);
        ProfileResponse resp = svc.upsertProfile(subject, req);

        assertEquals(35, resp.getAge());
        verify(profileRepo, times(1)).save(existing);
    }

    @Test
    void upsertProfileRejectsUnknownSubject() {
        AccountRepository accountRepo = mock(AccountRepository.class);
        when(accountRepo.findByKeycloakSubject("ghost")).thenReturn(Optional.empty());

        ProfileService svc = new ProfileService(
                mock(CustomerProfileRepository.class), mock(ProfileVersionRepository.class),
                accountRepo, new ProfileValidator());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.upsertProfile("ghost", validRequest()));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
    }

    @Test
    void getLatestProfileReturnsProfileAndVersion() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        ProfileVersionRepository versionRepo = mock(ProfileVersionRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        String subject = "test-subject-3";
        Account acc = account(subject);
        CustomerProfile profile = new CustomerProfile();
        profile.setCustomerId(UUID.randomUUID());
        profile.setAccount(acc);
        profile.setAge(30);
        profile.setGender("male");
        profile.setProvince("Ha Noi");
        profile.setRegion("Red River Delta");
        profile.setUrbanTier("tier1");
        profile.setOccupation("engineer");
        profile.setIncomeLevel("middle");
        profile.setMonthlyIncomeVnd(20_000_000L);
        profile.setMaritalStatus("single");

        ProfileVersion version = new ProfileVersion();
        version.setVersionId(UUID.randomUUID());
        version.setCustomerProfile(profile);
        version.setLine("health");
        version.setLineAttributes(Map.of("bmi", 22.5));
        version.setEffectiveAt(OffsetDateTime.now());

        when(accountRepo.findByKeycloakSubject(subject)).thenReturn(Optional.of(acc));
        when(profileRepo.findByAccount_AccountId(acc.getAccountId())).thenReturn(profile);
        when(versionRepo.findByCustomerProfile_CustomerIdOrderByEffectiveAtDesc(profile.getCustomerId()))
                .thenReturn(List.of(version));

        ProfileService svc = new ProfileService(profileRepo, versionRepo, accountRepo, new ProfileValidator());
        ProfileResponse resp = svc.getLatestProfile(subject);

        assertEquals(30, resp.getAge());
        assertEquals("health", resp.getLine());
        assertNotNull(resp.getVersionId());
    }

    @Test
    void getLatestProfileRejectsWhenNoProfile() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        String subject = "test-subject-4";
        Account acc = account(subject);

        when(accountRepo.findByKeycloakSubject(subject)).thenReturn(Optional.of(acc));
        when(profileRepo.findByAccount_AccountId(acc.getAccountId())).thenReturn(null);

        ProfileService svc = new ProfileService(
                profileRepo, mock(ProfileVersionRepository.class), accountRepo, new ProfileValidator());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.getLatestProfile(subject));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getLatestProfileRejectsUnknownSubject() {
        AccountRepository accountRepo = mock(AccountRepository.class);
        when(accountRepo.findByKeycloakSubject("ghost")).thenReturn(Optional.empty());

        ProfileService svc = new ProfileService(
                mock(CustomerProfileRepository.class), mock(ProfileVersionRepository.class),
                accountRepo, new ProfileValidator());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.getLatestProfile("ghost"));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
    }
}
