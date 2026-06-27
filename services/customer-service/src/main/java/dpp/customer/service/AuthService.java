package dpp.customer.service;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.client.KeycloakClient;
import dpp.customer.dto.LoginRequest;
import dpp.customer.dto.RegisterRequest;
import dpp.customer.dto.TokenResponse;
import dpp.customer.entity.Account;
import dpp.customer.repository.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
public class AuthService {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final int LOCK_WINDOW_MINUTES = 15;
    public static final int LOCK_DURATION_MINUTES = 15;

    private final AccountRepository accountRepository;
    private final KeycloakClient keycloakClient;
    private final FailedLoginService failedLoginService;

    public AuthService(AccountRepository accountRepository, KeycloakClient keycloakClient, FailedLoginService failedLoginService) {
        this.accountRepository = accountRepository;
        this.keycloakClient = keycloakClient;
        this.failedLoginService = failedLoginService;
    }

    @Transactional
    public void register(RegisterRequest request) {
        if (accountRepository.existsByEmail(request.getEmail())) {
            throw new ServiceException(ErrorCode.EMAIL_ALREADY_USED);
        }

        String keycloakUserId = keycloakClient.createUser(request.getEmail(), request.getPassword());

        Account account = new Account();
        account.setAccountId(UUID.randomUUID());
        account.setKeycloakSubject(keycloakUserId);
        account.setEmail(request.getEmail());
        account.setCreatedAt(OffsetDateTime.now());
        account.setFailedLoginCount(0);

        try {
            accountRepository.save(account);
        } catch (Exception e) {
            log.warn("Failed to save Account after Keycloak user created, rolling back Keycloak user {}", keycloakUserId);
            keycloakClient.deleteUser(keycloakUserId);
            throw e;
        }
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        OffsetDateTime now = OffsetDateTime.now();

        Account account = accountRepository.findByEmail(request.getEmail()).orElse(null);

        if (account != null && account.getLockedUntil() != null && account.getLockedUntil().isAfter(now)) {
            throw new ServiceException(ErrorCode.ACCOUNT_LOCKED);
        }

        TokenResponse auth = keycloakClient.login(request.getEmail(), request.getPassword());

        if (auth != null) {
            if (account == null) {
                account = provisionAccount(request.getEmail(), auth.getSubject(), now);
            } else {
                account.setFailedLoginCount(0);
                account.setLockedUntil(null);
                account.setFirstFailedAt(null);
                accountRepository.save(account);
            }
            return new TokenResponse(auth.getAccessToken(), auth.getExpiresIn(), "Bearer", auth.getRoles());
        }

        if (account != null) {
            failedLoginService.recordFailure(account, now);
        }
        throw new ServiceException(ErrorCode.INVALID_CREDENTIALS);
    }

    private Account provisionAccount(String email, String subject, OffsetDateTime now) {
        Account a = new Account();
        a.setAccountId(UUID.randomUUID());
        a.setKeycloakSubject(subject);
        a.setEmail(email);
        a.setCreatedAt(now);
        a.setFailedLoginCount(0);
        try {
            return accountRepository.save(a);
        } catch (DataIntegrityViolationException e) {
            return accountRepository.findByEmail(email)
                    .orElseThrow(() -> new ServiceException(ErrorCode.INTERNAL_ERROR));
        }
    }
}
