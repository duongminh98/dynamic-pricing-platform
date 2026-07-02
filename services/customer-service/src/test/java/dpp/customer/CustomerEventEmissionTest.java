package dpp.customer;

import dpp.common.outbox.OutboxPublisher;
import dpp.customer.dto.BaseProfileRequest;
import dpp.customer.entity.Account;
import dpp.customer.entity.CustomerProfile;
import dpp.customer.repository.AccountRepository;
import dpp.customer.repository.CustomerProfileRepository;
import dpp.customer.repository.ProfileVersionRepository;
import dpp.customer.service.ProfileService;
import dpp.customer.validator.ProfileValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CustomerEventEmissionTest {

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

    @Test
    void updateBaseProfileNewAccountEmitsCustomerCreatedAndProfileUpdated() throws Exception {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        ProfileVersionRepository versionRepo = mock(ProfileVersionRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        Account[] savedAccount = new Account[1];
        when(accountRepo.findByKeycloakSubject("sub-1")).thenAnswer(inv -> savedAccount[0] == null ? Optional.empty() : Optional.of(savedAccount[0]));
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> {
            savedAccount[0] = inv.getArgument(0);
            return savedAccount[0];
        });
        when(profileRepo.findByAccount_AccountId(any(UUID.class))).thenReturn(null, profile("u@example.com"));
        when(profileRepo.save(any(CustomerProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.findLatestPerLine(any(UUID.class))).thenReturn(List.of());

        ProfileService svc = new ProfileService(profileRepo, versionRepo, accountRepo, new ProfileValidator(), outbox);
        svc.updateBaseProfile("sub-1", "u@example.com", validBaseRequest());

        verify(outbox).enqueue(eq("CustomerCreated"), contains("\"email\":\"u@example.com\""));
        verify(outbox).enqueue(eq("CustomerProfileUpdated"), contains("\"email\":\"u@example.com\""));
    }

    @Test
    void getProfileEmailChangeEmitsCustomerEmailUpdated() throws Exception {
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        ProfileVersionRepository versionRepo = mock(ProfileVersionRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        Account account = new Account();
        account.setAccountId(UUID.randomUUID());
        account.setKeycloakSubject("sub-2");
        account.setEmail("old@example.com");

        CustomerProfile profile = profile("new@example.com");
        profile.setAccount(account);

        when(accountRepo.findByKeycloakSubject("sub-2")).thenReturn(Optional.of(account));
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(profileRepo.findByAccount_AccountId(account.getAccountId())).thenReturn(profile);
        when(versionRepo.findLatestPerLine(any(UUID.class))).thenReturn(List.of());

        ProfileService svc = new ProfileService(profileRepo, versionRepo, accountRepo, new ProfileValidator(), outbox);
        svc.getProfile("sub-2", "new@example.com");

        verify(outbox).enqueue(eq("CustomerEmailUpdated"), contains("\"email\":\"new@example.com\""));
    }

    private CustomerProfile profile(String email) {
        Account account = new Account();
        account.setAccountId(UUID.randomUUID());
        account.setKeycloakSubject("subject");
        account.setEmail(email);
        CustomerProfile profile = new CustomerProfile();
        profile.setCustomerId(UUID.randomUUID());
        profile.setAccount(account);
        profile.setAge(30);
        profile.setGender("male");
        profile.setProvince("Ha Noi");
        profile.setRegion("Red River Delta");
        profile.setUrbanTier("tier1");
        profile.setOccupation("engineer");
        profile.setIncomeLevel("middle");
        profile.setMonthlyIncomeVnd(20_000_000L);
        profile.setMaritalStatus("single");
        profile.setUpdatedAt(java.time.OffsetDateTime.now());
        return profile;
    }
}
