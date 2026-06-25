package dpp.customer;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.client.KeycloakClient;
import dpp.customer.dto.LoginRequest;
import dpp.customer.dto.RegisterRequest;
import dpp.customer.dto.TokenResponse;
import dpp.customer.entity.Account;
import dpp.customer.repository.AccountRepository;
import dpp.customer.service.AuthService;
import dpp.customer.service.FailedLoginService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private Account account(String email) {
        Account a = new Account();
        a.setAccountId(UUID.randomUUID());
        a.setKeycloakSubject(UUID.randomUUID().toString());
        a.setEmail(email);
        a.setCreatedAt(OffsetDateTime.now());
        a.setFailedLoginCount(0);
        return a;
    }

    @Test
    void registerRejectsDuplicateEmail() {
        AccountRepository repo = mock(AccountRepository.class);
        when(repo.existsByEmail("dup@example.com")).thenReturn(true);

        AuthService svc = new AuthService(repo, mock(KeycloakClient.class), mock(FailedLoginService.class));
        RegisterRequest req = new RegisterRequest();
        req.setEmail("dup@example.com");
        req.setPassword("password123");

        ServiceException ex = assertThrows(ServiceException.class, () -> svc.register(req));
        assertEquals(ErrorCode.EMAIL_ALREADY_USED, ex.getErrorCode());
        verify(repo, never()).save(any());
    }

    @Test
    void registerCreatesAccountSuccessfully() {
        AccountRepository repo = mock(AccountRepository.class);
        KeycloakClient keycloak = mock(KeycloakClient.class);
        when(repo.existsByEmail("new@example.com")).thenReturn(false);
        when(keycloak.createUser("new@example.com", "password123")).thenReturn("kc-uuid-123");
        when(repo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthService svc = new AuthService(repo, keycloak, mock(FailedLoginService.class));
        RegisterRequest req = new RegisterRequest();
        req.setEmail("new@example.com");
        req.setPassword("password123");

        assertDoesNotThrow(() -> svc.register(req));
        verify(repo, times(1)).save(any(Account.class));
    }

    @Test
    void registerRollsBackKeycloakOnDbFailure() {
        AccountRepository repo = mock(AccountRepository.class);
        KeycloakClient keycloak = mock(KeycloakClient.class);
        when(repo.existsByEmail("rb@example.com")).thenReturn(false);
        when(keycloak.createUser("rb@example.com", "password123")).thenReturn("kc-uuid-rb");
        when(repo.save(any(Account.class))).thenThrow(new RuntimeException("DB down"));

        AuthService svc = new AuthService(repo, keycloak, mock(FailedLoginService.class));
        RegisterRequest req = new RegisterRequest();
        req.setEmail("rb@example.com");
        req.setPassword("password123");

        assertThrows(RuntimeException.class, () -> svc.register(req));
        verify(keycloak, times(1)).deleteUser("kc-uuid-rb");
    }

    @Test
    void loginRejectsUnknownAccount() {
        AccountRepository repo = mock(AccountRepository.class);
        when(repo.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        AuthService svc = new AuthService(repo, mock(KeycloakClient.class), mock(FailedLoginService.class));
        LoginRequest req = new LoginRequest();
        req.setEmail("ghost@example.com");
        req.setPassword("password123");

        ServiceException ex = assertThrows(ServiceException.class, () -> svc.login(req));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, ex.getErrorCode());
    }

    @Test
    void loginRejectsLockedAccount() {
        AccountRepository repo = mock(AccountRepository.class);
        Account acc = account("locked@example.com");
        acc.setLockedUntil(OffsetDateTime.now().plusMinutes(10));
        when(repo.findByEmail("locked@example.com")).thenReturn(Optional.of(acc));

        AuthService svc = new AuthService(repo, mock(KeycloakClient.class), mock(FailedLoginService.class));
        LoginRequest req = new LoginRequest();
        req.setEmail("locked@example.com");
        req.setPassword("password123");

        ServiceException ex = assertThrows(ServiceException.class, () -> svc.login(req));
        assertEquals(ErrorCode.ACCOUNT_LOCKED, ex.getErrorCode());
    }

    @Test
    void loginSuccessResetsFailedCount() {
        AccountRepository repo = mock(AccountRepository.class);
        KeycloakClient keycloak = mock(KeycloakClient.class);
        Account acc = account("ok@example.com");
        acc.setFailedLoginCount(3);
        acc.setFirstFailedAt(OffsetDateTime.now().minusMinutes(5));
        when(repo.findByEmail("ok@example.com")).thenReturn(Optional.of(acc));
        when(keycloak.login("ok@example.com", "password123"))
                .thenReturn(new TokenResponse("access-token", 3600));
        when(repo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthService svc = new AuthService(repo, keycloak, mock(FailedLoginService.class));
        LoginRequest req = new LoginRequest();
        req.setEmail("ok@example.com");
        req.setPassword("password123");

        TokenResponse token = svc.login(req);
        assertNotNull(token);
        assertEquals("access-token", token.getAccessToken());
        assertEquals(0, acc.getFailedLoginCount());
        assertNull(acc.getLockedUntil());
        assertNull(acc.getFirstFailedAt());
    }

    @Test
    void loginFailureRecordsFailedAttempt() {
        AccountRepository repo = mock(AccountRepository.class);
        KeycloakClient keycloak = mock(KeycloakClient.class);
        FailedLoginService failedLoginService = mock(FailedLoginService.class);
        Account acc = account("fail@example.com");
        when(repo.findByEmail("fail@example.com")).thenReturn(Optional.of(acc));
        when(keycloak.login("fail@example.com", "wrongpass")).thenReturn(null);

        AuthService svc = new AuthService(repo, keycloak, failedLoginService);
        LoginRequest req = new LoginRequest();
        req.setEmail("fail@example.com");
        req.setPassword("wrongpass");

        ServiceException ex = assertThrows(ServiceException.class, () -> svc.login(req));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, ex.getErrorCode());
        verify(failedLoginService, times(1)).recordFailure(eq(acc), any(OffsetDateTime.class));
    }
}
