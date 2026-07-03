package dpp.customer.service;

import dpp.common.api.ErrorCode;
import dpp.common.security.CustomerId;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.customer.dto.AdminCustomerResponse;
import dpp.customer.dto.BaseProfileRequest;
import dpp.customer.dto.LineProfileRequest;
import dpp.customer.dto.LineProfileResponse;
import dpp.customer.dto.PageResponse;
import dpp.customer.dto.ProfileResponse;
import dpp.customer.entity.Account;
import dpp.customer.entity.CustomerProfile;
import dpp.customer.entity.ProfileVersion;
import dpp.customer.repository.AccountRepository;
import dpp.customer.repository.CustomerProfileRepository;
import dpp.customer.repository.ProfileVersionRepository;
import dpp.customer.validator.ProfileValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ProfileService {

    private final CustomerProfileRepository profileRepository;
    private final ProfileVersionRepository versionRepository;
    private final AccountRepository accountRepository;
    private final ProfileValidator profileValidator;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    @Autowired
    public ProfileService(CustomerProfileRepository profileRepository,
                          ProfileVersionRepository versionRepository,
                          AccountRepository accountRepository,
                          ProfileValidator profileValidator,
                          OutboxPublisher outboxPublisher) {
        this.profileRepository = profileRepository;
        this.versionRepository = versionRepository;
        this.accountRepository = accountRepository;
        this.profileValidator = profileValidator;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = new ObjectMapper();
    }

    public ProfileService(CustomerProfileRepository profileRepository,
                          ProfileVersionRepository versionRepository,
                          AccountRepository accountRepository,
                          ProfileValidator profileValidator) {
        this(profileRepository, versionRepository, accountRepository, profileValidator, null);
    }

    @Transactional
    public ProfileResponse updateBaseProfile(String keycloakSubject, BaseProfileRequest request) {
        return updateBaseProfile(keycloakSubject, null, request);
    }

    @Transactional
    public ProfileResponse updateBaseProfile(String keycloakSubject, String email, BaseProfileRequest request) {
        profileValidator.validateBase(request);

        Account account = resolveOrProvisionAccount(keycloakSubject, email);

        CustomerProfile profile = profileRepository.findByAccount_AccountId(account.getAccountId());

        if (profile == null) {
            profile = new CustomerProfile();
            profile.setCustomerId(CustomerId.fromSubject(keycloakSubject));
            profile.setAccount(account);
        }

        profile.setAge(request.getAge());
        profile.setGender(request.getGender());
        profile.setProvince(request.getProvince());
        profile.setRegion(profileValidator.deriveRegion(request.getProvince()));
        profile.setUrbanTier(profileValidator.deriveUrbanTier(request.getProvince()));
        profile.setOccupation(request.getOccupation());
        profile.setIncomeLevel(profileValidator.deriveIncomeLevel(request.getMonthlyIncomeVnd()));
        profile.setMonthlyIncomeVnd(request.getMonthlyIncomeVnd());
        profile.setMaritalStatus(request.getMaritalStatus());
        profile.setUpdatedAt(OffsetDateTime.now());

        profile = profileRepository.save(profile);
        enqueueProfileUpdated(profile, null);

        return getProfile(keycloakSubject);
    }

    @Transactional
    public LineProfileResponse upsertLineProfile(String keycloakSubject, String line, LineProfileRequest request) {
        profileValidator.validateLine(line, request.getLineAttributes());

        Account account = resolveAccount(keycloakSubject);

        CustomerProfile profile = profileRepository.findByAccount_AccountId(account.getAccountId());
        if (profile == null) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND,
                    "Base profile not found. Please update your base profile first.", null);
        }

        ProfileVersion version = new ProfileVersion();
        version.setVersionId(UUID.randomUUID());
        version.setCustomerProfile(profile);
        version.setLine(line);
        version.setLineAttributes(request.getLineAttributes());
        version.setEffectiveAt(OffsetDateTime.now());

        version = versionRepository.save(version);
        enqueueProfileUpdated(profile, version);

        return toLineResponse(version);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(String keycloakSubject) {
        return getProfile(keycloakSubject, null);
    }

    @Transactional
    public ProfileResponse getProfile(String keycloakSubject, String email) {
        Account account = resolveOrProvisionAccount(keycloakSubject, email);

        CustomerProfile profile = profileRepository.findByAccount_AccountId(account.getAccountId());
        if (profile == null) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Profile not found", null);
        }

        List<ProfileVersion> latestPerLine = versionRepository.findLatestPerLine(profile.getCustomerId());

        ProfileResponse response = mapBaseToResponse(profile);
        response.setLines(latestPerLine.stream().map(this::toLineResponse).collect(Collectors.toList()));
        return response;
    }

    @Transactional(readOnly = true)
    public LineProfileResponse getLineProfile(String keycloakSubject, String line) {
        Account account = resolveAccount(keycloakSubject);

        CustomerProfile profile = profileRepository.findByAccount_AccountId(account.getAccountId());
        if (profile == null) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Profile not found", null);
        }

        ProfileVersion version = versionRepository.findLatestByLine(profile.getCustomerId(), line)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No profile found for line: " + line, null));

        return toLineResponse(version);
    }

    private Account resolveAccount(String keycloakSubject) {
        // The account is JIT-provisioned at login (see CustomerController#getMe). A miss
        // here is a data/state problem, not an authentication failure — mapping it to 401
        // would make the SPA bounce the user back into the Keycloak login loop.
        return accountRepository.findByKeycloakSubject(keycloakSubject)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Account not found for subject", null));
    }

    private Account resolveOrProvisionAccount(String keycloakSubject, String email) {
        return resolveOrProvisionAccount(keycloakSubject, email, null);
    }

    private Account resolveOrProvisionAccount(String keycloakSubject, String email, String fullName) {
        return accountRepository.findByKeycloakSubject(keycloakSubject)
                .map(account -> syncAccountIdentity(account, email, fullName))
                .orElseGet(() -> provisionAccount(keycloakSubject, email, fullName));
    }

    @Transactional
    public Account ensureAccount(String keycloakSubject, String email) {
        return resolveOrProvisionAccount(keycloakSubject, email, null);
    }

    @Transactional
    public Account ensureAccount(String keycloakSubject, String email, String fullName) {
        return resolveOrProvisionAccount(keycloakSubject, email, fullName);
    }

    private Account provisionAccount(String keycloakSubject, String email, String fullName) {
        if (email == null || email.isBlank()) {
            // A validated JWT reached us but carries no email/preferred_username claim —
            // that's a bad request against a mis-scoped token, not an expired session.
            // Using UNAUTHENTICATED here would trigger the SPA's re-login loop.
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Email claim is required to provision an account", null);
        }
        Account account = new Account();
        account.setAccountId(UUID.randomUUID());
        account.setKeycloakSubject(keycloakSubject);
        account.setEmail(email);
        account.setFullName(trimToNull(fullName));
        account.setCreatedAt(OffsetDateTime.now());
        account.setFailedLoginCount(0);
        try {
            Account saved = accountRepository.save(account);
            enqueueCustomerCreated(saved);
            return saved;
        } catch (DataIntegrityViolationException e) {
            return accountRepository.findByKeycloakSubject(keycloakSubject)
                    .map(existing -> syncAccountIdentity(existing, email, fullName))
                    .orElseThrow(() -> new ServiceException(ErrorCode.INTERNAL_ERROR));
        }
    }

    /** Keep the locally cached email/name in step with the authoritative Keycloak identity. */
    private Account syncAccountIdentity(Account account, String email, String fullName) {
        boolean changed = false;
        boolean emailChanged = false;
        if (email != null && !email.isBlank() && !email.equals(account.getEmail())) {
            account.setEmail(email);
            changed = true;
            emailChanged = true;
        }
        String name = trimToNull(fullName);
        if (name != null && !name.equals(account.getFullName())) {
            account.setFullName(name);
            changed = true;
        }
        if (!changed) {
            return account;
        }
        Account saved = accountRepository.save(account);
        if (emailChanged) {
            enqueueCustomerEmailUpdated(saved);
        }
        return saved;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ProfileResponse mapBaseToResponse(CustomerProfile profile) {
        ProfileResponse response = new ProfileResponse();
        response.setCustomerId(profile.getCustomerId());
        response.setAge(profile.getAge());
        response.setGender(profile.getGender());
        response.setProvince(profile.getProvince());
        response.setRegion(profile.getRegion());
        response.setUrbanTier(profile.getUrbanTier());
        response.setOccupation(profile.getOccupation());
        response.setIncomeLevel(profile.getIncomeLevel());
        response.setMonthlyIncomeVnd(profile.getMonthlyIncomeVnd());
        response.setMaritalStatus(profile.getMaritalStatus());
        response.setUpdatedAt(profile.getUpdatedAt());
        return response;
    }

    private LineProfileResponse toLineResponse(ProfileVersion version) {
        LineProfileResponse resp = new LineProfileResponse();
        resp.setVersionId(version.getVersionId());
        resp.setLine(version.getLine());
        resp.setLineAttributes(version.getLineAttributes());
        resp.setEffectiveAt(version.getEffectiveAt());
        return resp;
    }

    private void enqueueCustomerCreated(Account account) {
        if (outboxPublisher == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", "CustomerCreated");
        payload.put("schema_version", 1);
        payload.put("producer", "customer-service");
        payload.put("customer_id", CustomerId.fromSubject(account.getKeycloakSubject()).toString());
        payload.put("account_id", account.getAccountId().toString());
        payload.put("email", account.getEmail());
        payload.put("updated_at", OffsetDateTime.now().toString());
        try {
            outboxPublisher.enqueue("CustomerCreated", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue CustomerCreated", e);
        }
    }

    private void enqueueCustomerEmailUpdated(Account account) {
        if (outboxPublisher == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", "CustomerEmailUpdated");
        payload.put("schema_version", 1);
        payload.put("producer", "customer-service");
        payload.put("customer_id", CustomerId.fromSubject(account.getKeycloakSubject()).toString());
        payload.put("account_id", account.getAccountId().toString());
        payload.put("email", account.getEmail());
        payload.put("updated_at", OffsetDateTime.now().toString());
        try {
            outboxPublisher.enqueue("CustomerEmailUpdated", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue CustomerEmailUpdated", e);
        }
    }

    private void enqueueProfileUpdated(CustomerProfile profile, ProfileVersion lineVersion) {
        if (outboxPublisher == null) {
            return;
        }
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("age", profile.getAge());
        common.put("gender", profile.getGender());
        common.put("province", profile.getProvince());
        common.put("region", profile.getRegion());
        common.put("urban_tier", profile.getUrbanTier());
        common.put("occupation", profile.getOccupation());
        common.put("income_level", profile.getIncomeLevel());
        common.put("monthly_income_vnd", profile.getMonthlyIncomeVnd());
        common.put("marital_status", profile.getMaritalStatus());

        Map<String, Object> lineAttrs = new LinkedHashMap<>();
        List<ProfileVersion> latestPerLine = versionRepository.findLatestPerLine(profile.getCustomerId());
        for (ProfileVersion version : latestPerLine) {
            lineAttrs.put(version.getLine(), version.getLineAttributes());
        }
        if (lineVersion != null) {
            lineAttrs.put(lineVersion.getLine(), lineVersion.getLineAttributes());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", "CustomerProfileUpdated");
        payload.put("schema_version", 1);
        payload.put("producer", "customer-service");
        payload.put("customer_id", profile.getCustomerId().toString());
        payload.put("email", profile.getAccount().getEmail());
        payload.put("profile_version", Math.max(1, latestPerLine.size() + (lineVersion == null ? 0 : 1)));
        payload.put("effective_at", OffsetDateTime.now().toString());
        payload.put("common_risk_attributes", common);
        payload.put("line_risk_attributes", lineAttrs);
        try {
            outboxPublisher.enqueue("CustomerProfileUpdated", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue CustomerProfileUpdated", e);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminCustomerResponse> adminListCustomers(String q, String province, Boolean locked, int page, int size) {
        size = Math.min(size, 100);
        if (province != null && !province.isBlank()) {
            // createdAt lives on Account, not CustomerProfile — sort via the joined path
            // or Hibernate throws PathElementException resolving 'createdAt' on the profile.
            Pageable pageable = PageRequest.of(page, size, Sort.by("account.createdAt").ascending());
            Page<CustomerProfile> profiles = profileRepository.findFiltered(q, province, locked, OffsetDateTime.now(), pageable);
            return PageResponse.from(profiles.map(this::toAdminCustomerResponse));
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        Page<Account> accounts = accountRepository.findFiltered(q, locked, OffsetDateTime.now(), pageable);
        return PageResponse.from(accounts.map(this::toAdminCustomerResponse));
    }

    @Transactional(readOnly = true)
    public AdminCustomerResponse adminGetCustomer(UUID customerId) {
        return profileRepository.findById(customerId)
                .map(this::toAdminCustomerResponse)
                .orElseGet(() -> accountRepository.findById(customerId)
                        .map(this::toAdminCustomerResponse)
                        .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Customer not found", null)));
    }

    @Transactional
    public AdminCustomerResponse adminLockCustomer(UUID customerId, int hours) {
        if (hours < 1 || hours > 876000) {
            throw new ServiceException(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE, "hours out of range",
                    java.util.Map.of("field", "hours", "min", 1, "max", 876000));
        }
        Account account = resolveAdminAccount(customerId);
        account.setLockedUntil(OffsetDateTime.now().plusHours(hours));
        account = accountRepository.save(account);
        return toAdminCustomerResponse(account);
    }

    @Transactional
    public AdminCustomerResponse adminUnlockCustomer(UUID customerId) {
        Account account = resolveAdminAccount(customerId);
        account.setLockedUntil(null);
        account.setFailedLoginCount(0);
        account.setFirstFailedAt(null);
        account = accountRepository.save(account);
        return toAdminCustomerResponse(account);
    }

    private Account resolveAdminAccount(UUID id) {
        return profileRepository.findById(id)
                .map(CustomerProfile::getAccount)
                .orElseGet(() -> accountRepository.findById(id)
                        .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Customer not found", null)));
    }

    private AdminCustomerResponse toAdminCustomerResponse(Account account) {
        CustomerProfile profile = profileRepository.findByAccount_AccountId(account.getAccountId());
        return profile != null ? toAdminCustomerResponse(profile) : toAdminCustomerResponse(account, null);
    }

    private AdminCustomerResponse toAdminCustomerResponse(CustomerProfile profile) {
        Account account = profile.getAccount();
        AdminCustomerResponse resp = new AdminCustomerResponse();
        resp.setAccountId(account.getAccountId());
        resp.setEmail(account.getEmail());
        resp.setFullName(account.getFullName());
        resp.setKeycloakSubject(account.getKeycloakSubject());
        resp.setFailedLoginCount(account.getFailedLoginCount());
        resp.setLockedUntil(account.getLockedUntil());
        resp.setCreatedAt(account.getCreatedAt());
        resp.setCustomerId(profile.getCustomerId());
        resp.setAge(profile.getAge());
        resp.setGender(profile.getGender());
        resp.setProvince(profile.getProvince());
        resp.setRegion(profile.getRegion());
        resp.setUrbanTier(profile.getUrbanTier());
        resp.setOccupation(profile.getOccupation());
        resp.setIncomeLevel(profile.getIncomeLevel());
        resp.setMonthlyIncomeVnd(profile.getMonthlyIncomeVnd());
        resp.setMaritalStatus(profile.getMaritalStatus());
        resp.setLineProfiles(versionRepository.findLatestPerLine(profile.getCustomerId()).stream()
                .map(this::toLineResponse)
                .collect(Collectors.toList()));
        resp.setUpdatedAt(profile.getUpdatedAt());
        return resp;
    }

    private AdminCustomerResponse toAdminCustomerResponse(Account account, CustomerProfile profile) {
        AdminCustomerResponse resp = new AdminCustomerResponse();
        resp.setAccountId(account.getAccountId());
        resp.setCustomerId(account.getAccountId());
        resp.setEmail(account.getEmail());
        resp.setFullName(account.getFullName());
        resp.setKeycloakSubject(account.getKeycloakSubject());
        resp.setFailedLoginCount(account.getFailedLoginCount());
        resp.setLockedUntil(account.getLockedUntil());
        resp.setCreatedAt(account.getCreatedAt());
        resp.setLineProfiles(Collections.emptyList());
        return resp;
    }
}



