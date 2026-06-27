package dpp.customer.service;

import dpp.common.api.ErrorCode;
import dpp.common.security.CustomerId;
import dpp.common.api.ServiceException;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProfileService {

    private final CustomerProfileRepository profileRepository;
    private final ProfileVersionRepository versionRepository;
    private final AccountRepository accountRepository;
    private final ProfileValidator profileValidator;

    public ProfileService(CustomerProfileRepository profileRepository,
                          ProfileVersionRepository versionRepository,
                          AccountRepository accountRepository,
                          ProfileValidator profileValidator) {
        this.profileRepository = profileRepository;
        this.versionRepository = versionRepository;
        this.accountRepository = accountRepository;
        this.profileValidator = profileValidator;
    }

    @Transactional
    public ProfileResponse updateBaseProfile(String keycloakSubject, BaseProfileRequest request) {
        profileValidator.validateBase(request);

        Account account = resolveAccount(keycloakSubject);

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

        return toLineResponse(version);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(String keycloakSubject) {
        Account account = resolveAccount(keycloakSubject);

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
        return accountRepository.findByKeycloakSubject(keycloakSubject)
                .orElseThrow(() -> new ServiceException(ErrorCode.UNAUTHENTICATED,
                        "Account not found for subject", null));
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

    @Transactional(readOnly = true)
    public PageResponse<AdminCustomerResponse> adminListCustomers(String q, String province, Boolean locked, int page, int size) {
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("account.createdAt").ascending());
        Page<CustomerProfile> profiles = profileRepository.findFiltered(q, province, locked, OffsetDateTime.now(), pageable);
        return PageResponse.from(profiles.map(this::toAdminCustomerResponse));
    }

    @Transactional(readOnly = true)
    public AdminCustomerResponse adminGetCustomer(UUID customerId) {
        CustomerProfile profile = profileRepository.findById(customerId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Customer not found", null));
        return toAdminCustomerResponse(profile);
    }

    @Transactional
    public AdminCustomerResponse adminLockCustomer(UUID customerId, int hours) {
        if (hours < 1 || hours > 8760) {
            throw new ServiceException(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE, "hours out of range",
                    java.util.Map.of("field", "hours", "min", 1, "max", 8760));
        }
        CustomerProfile profile = profileRepository.findById(customerId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Customer not found", null));
        Account account = profile.getAccount();
        account.setLockedUntil(OffsetDateTime.now().plusHours(hours));
        accountRepository.save(account);
        return toAdminCustomerResponse(profile);
    }

    @Transactional
    public AdminCustomerResponse adminUnlockCustomer(UUID customerId) {
        CustomerProfile profile = profileRepository.findById(customerId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Customer not found", null));
        Account account = profile.getAccount();
        account.setLockedUntil(null);
        account.setFailedLoginCount(0);
        account.setFirstFailedAt(null);
        accountRepository.save(account);
        return toAdminCustomerResponse(profile);
    }

    private AdminCustomerResponse toAdminCustomerResponse(CustomerProfile profile) {
        Account account = profile.getAccount();
        AdminCustomerResponse resp = new AdminCustomerResponse();
        resp.setAccountId(account.getAccountId());
        resp.setEmail(account.getEmail());
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
        resp.setUpdatedAt(profile.getUpdatedAt());
        return resp;
    }
}

