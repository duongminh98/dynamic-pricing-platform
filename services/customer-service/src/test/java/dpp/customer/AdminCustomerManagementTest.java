package dpp.customer;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.controller.AdminCustomerController;
import dpp.customer.dto.AdminCustomerResponse;
import dpp.customer.dto.PageResponse;
import dpp.customer.entity.Account;
import dpp.customer.entity.CustomerProfile;
import dpp.customer.repository.AccountRepository;
import dpp.customer.repository.CustomerProfileRepository;
import dpp.customer.service.ProfileService;
import dpp.customer.validator.ProfileValidator;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminCustomerManagementTest {

    private Account accountFor(UUID id, String email, OffsetDateTime lockedUntil) {
        Account a = new Account();
        a.setAccountId(id);
        a.setKeycloakSubject("subject-" + id);
        a.setEmail(email);
        a.setCreatedAt(OffsetDateTime.now().minusDays(1));
        a.setFailedLoginCount(0);
        a.setLockedUntil(lockedUntil);
        return a;
    }

    private CustomerProfile profileFor(UUID customerId, Account account, String province) {
        CustomerProfile p = new CustomerProfile();
        p.setCustomerId(customerId);
        p.setAccount(account);
        p.setAge(30);
        p.setGender("male");
        p.setProvince(province);
        p.setRegion("Red River Delta");
        p.setUrbanTier("tier1");
        p.setOccupation("engineer");
        p.setIncomeLevel("middle");
        p.setMonthlyIncomeVnd(20_000_000L);
        p.setMaritalStatus("single");
        p.setUpdatedAt(OffsetDateTime.now());
        return p;
    }

    private ProfileService serviceWith(CustomerProfileRepository profileRepo, AccountRepository accountRepo) {
        return new ProfileService(profileRepo, mock(dpp.customer.repository.ProfileVersionRepository.class),
                accountRepo, mock(ProfileValidator.class));
    }

    private AdminCustomerController controllerWith(ProfileService svc) {
        return new AdminCustomerController(svc);
    }

    // ── List excludes admin accounts (only CustomerProfile rows appear) ──

    @Test
    void listReturnsOnlyCustomersWithProfile() {
        UUID custId = UUID.randomUUID();
        Account acct = accountFor(UUID.randomUUID(), "alice@test.com", null);
        CustomerProfile profile = profileFor(custId, acct, "Ha Noi");

        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        Page<CustomerProfile> page = new PageImpl<>(List.of(profile), PageRequest.of(0, 20), 1);
        when(profileRepo.findFiltered(isNull(), isNull(), isNull(), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(page);

        ProfileService svc = serviceWith(profileRepo, mock(AccountRepository.class));
        AdminCustomerController controller = controllerWith(svc);

        PageResponse<AdminCustomerResponse> result = controller.listCustomers(0, 20, null, null, null);

        assertEquals(1, result.getContent().size());
        assertEquals("alice@test.com", result.getContent().get(0).getEmail());
        assertEquals(0, result.getPage());
        assertEquals(20, result.getSize());
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getTotalPages());
    }

    // ── Pagination: size capped at 100 ──

    @Test
    void sizeCappedAt100() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        Page<CustomerProfile> page = new PageImpl<>(List.of(), PageRequest.of(0, 100), 0);
        when(profileRepo.findFiltered(any(), any(), any(), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(page);

        ProfileService svc = serviceWith(profileRepo, mock(AccountRepository.class));
        AdminCustomerController controller = controllerWith(svc);

        PageResponse<AdminCustomerResponse> result = controller.listCustomers(0, 500, null, null, null);

        assertEquals(100, result.getSize());
    }

    // ── Filter by q (email contains) ──

    @Test
    void filterByQPassesToRepository() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        Page<CustomerProfile> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(profileRepo.findFiltered(eq("alice"), isNull(), isNull(), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(page);

        ProfileService svc = serviceWith(profileRepo, mock(AccountRepository.class));
        AdminCustomerController controller = controllerWith(svc);

        controller.listCustomers(0, 20, "alice", null, null);

        verify(profileRepo, times(1)).findFiltered(eq("alice"), isNull(), isNull(), any(OffsetDateTime.class), any(Pageable.class));
    }

    // ── Filter by province ──

    @Test
    void filterByProvincePassesToRepository() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        Page<CustomerProfile> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(profileRepo.findFiltered(isNull(), eq("Ha Noi"), isNull(), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(page);

        ProfileService svc = serviceWith(profileRepo, mock(AccountRepository.class));
        AdminCustomerController controller = controllerWith(svc);

        controller.listCustomers(0, 20, null, "Ha Noi", null);

        verify(profileRepo, times(1)).findFiltered(isNull(), eq("Ha Noi"), isNull(), any(OffsetDateTime.class), any(Pageable.class));
    }

    // ── Filter by locked=true ──

    @Test
    void filterByLockedTruePassesToRepository() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        Page<CustomerProfile> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(profileRepo.findFiltered(isNull(), isNull(), eq(true), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(page);

        ProfileService svc = serviceWith(profileRepo, mock(AccountRepository.class));
        AdminCustomerController controller = controllerWith(svc);

        controller.listCustomers(0, 20, null, null, true);

        verify(profileRepo, times(1)).findFiltered(isNull(), isNull(), eq(true), any(OffsetDateTime.class), any(Pageable.class));
    }

    // ── Lock validation: hours=0 rejected ──

    @Test
    void lockRejectsHoursZero() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.lockCustomer(UUID.randomUUID(), Map.of("hours", 0)));
        assertEquals(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE, ex.getErrorCode());
    }

    // ── Lock validation: hours=999 rejected ──

    @Test
    void lockRejectsHoursTooLarge() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.lockCustomer(UUID.randomUUID(), Map.of("hours", 10000)));
        assertEquals(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE, ex.getErrorCode());
    }

    // ── Lock validation: hours=1 accepted (boundary) ──

    @Test
    void lockAcceptsHoursOne() {
        UUID custId = UUID.randomUUID();
        Account acct = accountFor(UUID.randomUUID(), "bob@test.com", null);
        CustomerProfile profile = profileFor(custId, acct, "Ha Noi");

        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        when(profileRepo.findById(custId)).thenReturn(Optional.of(profile));
        AccountRepository accountRepo = mock(AccountRepository.class);
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        AdminCustomerResponse result = controller.lockCustomer(custId, Map.of("hours", 1));

        assertNotNull(result.getLockedUntil());
        verify(accountRepo, times(1)).save(any(Account.class));
    }

    // ── Lock validation: hours=8760 accepted (boundary) ──

    @Test
    void lockAcceptsHours8760() {
        UUID custId = UUID.randomUUID();
        Account acct = accountFor(UUID.randomUUID(), "bob@test.com", null);
        CustomerProfile profile = profileFor(custId, acct, "Ha Noi");

        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        when(profileRepo.findById(custId)).thenReturn(Optional.of(profile));
        AccountRepository accountRepo = mock(AccountRepository.class);
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        AdminCustomerResponse result = controller.lockCustomer(custId, Map.of("hours", 8760));

        assertNotNull(result.getLockedUntil());
    }

    // ── Lock validation: hours=8761 rejected ──

    @Test
    void lockRejectsHours8761() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.lockCustomer(UUID.randomUUID(), Map.of("hours", 8761)));
        assertEquals(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE, ex.getErrorCode());
    }

    // ── Lock default 24 when hours missing ──

    @Test
    void lockDefaultsTo24HoursWhenMissing() {
        UUID custId = UUID.randomUUID();
        Account acct = accountFor(UUID.randomUUID(), "bob@test.com", null);
        CustomerProfile profile = profileFor(custId, acct, "Ha Noi");

        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        when(profileRepo.findById(custId)).thenReturn(Optional.of(profile));
        AccountRepository accountRepo = mock(AccountRepository.class);
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        AdminCustomerResponse result = controller.lockCustomer(custId, Map.of());

        assertNotNull(result.getLockedUntil());
    }

    // ── Lock non-existent customer → 404 ──

    @Test
    void lockRejectsNonExistentCustomer() {
        UUID randomId = UUID.randomUUID();
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        when(profileRepo.findById(randomId)).thenReturn(Optional.empty());

        ProfileService svc = serviceWith(profileRepo, mock(AccountRepository.class));
        AdminCustomerController controller = controllerWith(svc);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.lockCustomer(randomId, Map.of("hours", 24)));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    // ── Get customer returns profile data ──

    @Test
    void getCustomerReturnsProfileData() {
        UUID custId = UUID.randomUUID();
        Account acct = accountFor(UUID.randomUUID(), "carol@test.com", null);
        CustomerProfile profile = profileFor(custId, acct, "Da Nang");

        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        when(profileRepo.findById(custId)).thenReturn(Optional.of(profile));

        ProfileService svc = serviceWith(profileRepo, mock(AccountRepository.class));
        AdminCustomerController controller = controllerWith(svc);

        AdminCustomerResponse result = controller.getCustomer(custId);

        assertEquals("carol@test.com", result.getEmail());
        assertEquals("Da Nang", result.getProvince());
        assertEquals(30, result.getAge());
    }

    // ── Unlock clears lockedUntil ──

    @Test
    void unlockClearsLock() {
        UUID custId = UUID.randomUUID();
        Account acct = accountFor(UUID.randomUUID(), "dave@test.com", OffsetDateTime.now().plusHours(5));
        CustomerProfile profile = profileFor(custId, acct, "Ha Noi");

        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        when(profileRepo.findById(custId)).thenReturn(Optional.of(profile));
        AccountRepository accountRepo = mock(AccountRepository.class);
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        AdminCustomerResponse result = controller.unlockCustomer(custId);

        assertNull(result.getLockedUntil());
        verify(accountRepo, times(1)).save(any(Account.class));
    }

    // ── Combined filters pass through ──

    @Test
    void combinedFiltersPassToRepository() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        Page<CustomerProfile> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(profileRepo.findFiltered(eq("ali"), eq("Ha Noi"), eq(true), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(page);

        ProfileService svc = serviceWith(profileRepo, mock(AccountRepository.class));
        AdminCustomerController controller = controllerWith(svc);

        controller.listCustomers(0, 20, "ali", "Ha Noi", true);

        verify(profileRepo, times(1)).findFiltered(eq("ali"), eq("Ha Noi"), eq(true), any(OffsetDateTime.class), any(Pageable.class));
    }

    // ── No N+1: toAdminCustomerResponse uses profile.getAccount() directly ──

    @Test
    void listDoesNotTriggerExtraFindByAccountQueries() {
        UUID custId = UUID.randomUUID();
        Account acct = accountFor(UUID.randomUUID(), "alice@test.com", null);
        CustomerProfile profile = profileFor(custId, acct, "Ha Noi");

        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        Page<CustomerProfile> page = new PageImpl<>(List.of(profile), PageRequest.of(0, 20), 1);
        when(profileRepo.findFiltered(any(), any(), any(), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(page);

        ProfileService svc = serviceWith(profileRepo, mock(AccountRepository.class));
        AdminCustomerController controller = controllerWith(svc);

        PageResponse<AdminCustomerResponse> result = controller.listCustomers(0, 20, null, null, null);

        // toAdminCustomerResponse must NOT call profileRepository.findByAccount_AccountId
        verify(profileRepo, never()).findByAccount_AccountId(any());
        assertEquals("alice@test.com", result.getContent().get(0).getEmail());
    }
}
