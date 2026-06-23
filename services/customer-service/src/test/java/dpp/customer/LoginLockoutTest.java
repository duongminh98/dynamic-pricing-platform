package dpp.customer;

import dpp.customer.entity.Account;
import dpp.customer.repository.AccountRepository;
import dpp.customer.service.FailedLoginService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.UUID;

public class LoginLockoutTest {

    @Test
    public void testFailedLoginServiceLockout() {
        AccountRepository accountRepository = Mockito.mock(AccountRepository.class);
        FailedLoginService failedLoginService = new FailedLoginService(accountRepository);

        Account account = new Account();
        account.setAccountId(UUID.randomUUID());
        account.setEmail("test@example.com");

        OffsetDateTime now = OffsetDateTime.now();

        // 1st fail
        failedLoginService.recordFailure(account, now);
        Assertions.assertEquals(1, account.getFailedLoginCount());
        Assertions.assertNull(account.getLockedUntil());

        // 2nd, 3rd, 4th
        failedLoginService.recordFailure(account, now.plusMinutes(1));
        failedLoginService.recordFailure(account, now.plusMinutes(2));
        failedLoginService.recordFailure(account, now.plusMinutes(3));
        Assertions.assertEquals(4, account.getFailedLoginCount());
        Assertions.assertNull(account.getLockedUntil());

        // 5th fail -> Lock
        failedLoginService.recordFailure(account, now.plusMinutes(4));
        Assertions.assertEquals(5, account.getFailedLoginCount());
        Assertions.assertNotNull(account.getLockedUntil());
        Assertions.assertTrue(account.getLockedUntil().isAfter(now.plusMinutes(18))); // 4+15 = 19
        Assertions.assertTrue(account.getLockedUntil().isBefore(now.plusMinutes(20)));

        // Reset if window expires
        Account account2 = new Account();
        account2.setAccountId(UUID.randomUUID());
        account2.setEmail("test2@example.com");

        // 1st fail
        failedLoginService.recordFailure(account2, now);
        Assertions.assertEquals(1, account2.getFailedLoginCount());

        // 2nd fail after 16 minutes -> Should reset window and count
        failedLoginService.recordFailure(account2, now.plusMinutes(16));
        Assertions.assertEquals(1, account2.getFailedLoginCount()); // Count is 1 again
        Assertions.assertEquals(now.plusMinutes(16), account2.getFirstFailedAt());
    }
}
