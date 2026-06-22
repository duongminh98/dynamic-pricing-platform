package dpp.customer.service;

import dpp.customer.entity.Account;
import dpp.customer.repository.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
@Service
public class FailedLoginService {

    private final AccountRepository accountRepository;

    public FailedLoginService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Account account, OffsetDateTime now) {
        if (account.getFirstFailedAt() == null
                || account.getFirstFailedAt().plusMinutes(AuthService.LOCK_WINDOW_MINUTES).isBefore(now)) {
            account.setFirstFailedAt(now);
            account.setFailedLoginCount(1);
        } else {
            account.setFailedLoginCount(account.getFailedLoginCount() + 1);
        }

        if (account.getFailedLoginCount() >= AuthService.MAX_FAILED_ATTEMPTS) {
            account.setLockedUntil(now.plusMinutes(AuthService.LOCK_DURATION_MINUTES));
            log.info("Account {} locked until {}", account.getEmail(), account.getLockedUntil());
        }

        accountRepository.save(account);
    }
}
