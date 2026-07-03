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

    // â”€â”€ List excludes admin accounts (only CustomerProfile rows appear) â”€â”€

    @Test
    void listReturnsAccountsEvenWithoutProfile() {
        Account acct = accountFor(UUID.randomUUID(), "alice@test.com", null);

        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        Page<Account> page = new PageImpl<>(List.of(acct), PageRequest.of(0, 20), 1);
        when(accountRepo.findFiltered(isNull(), isNull(), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(page);

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        PageResponse<AdminCustomerResponse> result = controller.listCustomers(0, 20, null, null, null);

        assertEquals(1, result.getContent().size());
        assertEquals("alice@test.com", result.getContent().get(0).getEmail());
        assertEquals(0, result.getPage());
        assertEquals(20, result.getSize());
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getTotalPages());
    }

    // â”€â”€ Pagination: size capped at 100 â”€â”€

    @Test
    void sizeCappedAt100() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        Page<Account> page = new PageImpl<>(List.of(), PageRequest.of(0, 100), 0);
        when(accountRepo.findFiltered(any(), any(), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(page);

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        PageResponse<AdminCustomerResponse> result = controller.listCustomers(0, 500, null, null, null);

        assertEquals(100, result.getSize());
    }

    // â”€â”€ Filter by q (email contains) â”€â”€

    @Test
    void filterByQPassesToRepository() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        Page<Account> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(accountRepo.findFiltered(eq("alice"), isNull(), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(page);

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        controller.listCustomers(0, 20, "alice", null, null);

        verify(accountRepo, times(1)).findFiltered(eq("alice"), isNull(), any(OffsetDateTime.class), any(Pageable.class));
    }

    // â”€â”€ Filter by province â”€â”€

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

    // â”€â”€ Filter by locked=true â”€â”€

    @Test
    void filterByLockedTruePassesToRepository() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        Page<Account> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(accountRepo.findFiltered(isNull(), eq(true), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(page);

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        controller.listCustomers(0, 20, null, null, true);

        verify(accountRepo, times(1)).findFiltered(isNull(), eq(true), any(OffsetDateTime.class), any(Pageable.class));
    }

    // â”€â”€ Lock validation: hours=0 rejected â”€â”€

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

    // â”€â”€ Lock validation: hours=999 rejected â”€â”€

    @Test
    void lockRejectsHoursTooLarge() {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.lockCustomer(UUID.randomUUID(), Map.of("hours", 876001)));
        assertEquals(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE, ex.getErrorCode());
    }

    // â”€â”€ Lock validation: hours=1 accepted (boundary) â”€â”€

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

    // â”€â”€ Lock validation: hours=8760 accepted (boundary) â”€â”€

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

    // â”€â”€ Lock validation: hours=8761 rejected â”€â”€

    @Test
    void lockAcceptsHours8761() {
        UUID custId = UUID.randomUUID();
        Account acct = accountFor(UUID.randomUUID(), "bob@test.com", null);
        CustomerProfile profile = profileFor(custId, acct, "Ha Noi");

        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        when(profileRepo.findById(custId)).thenReturn(Optional.of(profile));
        AccountRepository accountRepo = mock(AccountRepository.class);
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        AdminCustomerResponse result = controller.lockCustomer(custId, Map.of("hours", 8761));

        assertNotNull(result.getLockedUntil());
    }

    // â”€â”€ Lock default long duration when hours missing â”€â”€

    @Test
    void lockDefaultsToLongDurationWhenMissing() {
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

    // â”€â”€ Lock non-existent customer â†’ 404 â”€â”€

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

    // â”€â”€ Get customer returns profile data â”€â”€

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

    // â”€â”€ Unlock clears lockedUntil â”€â”€

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

    // â”€â”€ Combined filters pass through â”€â”€

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

    // â”€â”€ No N+1: toAdminCustomerResponse uses profile.getAccount() directly â”€â”€

    @Test
    void listDoesNotTriggerExtraFindByAccountQueries() {
        UUID custId = UUID.randomUUID();
        Account acct = accountFor(UUID.randomUUID(), "alice@test.com", null);

        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        Page<Account> page = new PageImpl<>(List.of(acct), PageRequest.of(0, 20), 1);
        when(accountRepo.findFiltered(any(), any(), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(page);

        ProfileService svc = serviceWith(profileRepo, accountRepo);
        AdminCustomerController controller = controllerWith(svc);

        PageResponse<AdminCustomerResponse> result = controller.listCustomers(0, 20, null, null, null);

        verify(profileRepo, times(1)).findByAccount_AccountId(acct.getAccountId());
        assertEquals("alice@test.com", result.getContent().get(0).getEmail());
    }
}
