package dpp.customer.service;

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
import dpp.customer.validator.ProfileValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
    public ProfileResponse upsertProfile(String keycloakSubject, ProfileRequest request) {
        profileValidator.validate(request);

        Account account = accountRepository.findByKeycloakSubject(keycloakSubject)
                .orElseThrow(() -> new ServiceException(ErrorCode.UNAUTHENTICATED, "Account not found for subject", null));

        CustomerProfile profile = profileRepository.findByAccount_AccountId(account.getAccountId());
        
        if (profile == null) {
            profile = new CustomerProfile();
            profile.setCustomerId(UUID.randomUUID());
            profile.setAccount(account);
        }

        profile.setAge(request.getAge());
        profile.setGender(request.getGender());
        profile.setProvince(request.getProvince());
        profile.setRegion(request.getRegion());
        profile.setUrbanTier(request.getUrbanTier());
        profile.setOccupation(request.getOccupation());
        profile.setIncomeLevel(request.getIncomeLevel());
        profile.setMonthlyIncomeVnd(request.getMonthlyIncomeVnd());
        profile.setMaritalStatus(request.getMaritalStatus());
        profile.setUpdatedAt(OffsetDateTime.now());

        profile = profileRepository.save(profile);

        ProfileVersion version = new ProfileVersion();
        version.setVersionId(UUID.randomUUID());
        version.setCustomerProfile(profile);
        version.setLine(request.getLine());
        version.setLineAttributes(request.getLineAttributes());
        version.setEffectiveAt(OffsetDateTime.now());

        version = versionRepository.save(version);

        return mapToResponse(profile, version);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getLatestProfile(String keycloakSubject) {
        Account account = accountRepository.findByKeycloakSubject(keycloakSubject)
                .orElseThrow(() -> new ServiceException(ErrorCode.UNAUTHENTICATED, "Account not found for subject", null));

        CustomerProfile profile = profileRepository.findByAccount_AccountId(account.getAccountId());
        if (profile == null) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Profile not found", null);
        }

        List<ProfileVersion> versions = versionRepository.findByCustomerProfile_CustomerIdOrderByEffectiveAtDesc(profile.getCustomerId());
        ProfileVersion latestVersion = versions.isEmpty() ? null : versions.get(0);

        return mapToResponse(profile, latestVersion);
    }

    private ProfileResponse mapToResponse(CustomerProfile profile, ProfileVersion version) {
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

        if (version != null) {
            response.setVersionId(version.getVersionId());
            response.setLine(version.getLine());
            response.setLineAttributes(version.getLineAttributes());
            response.setEffectiveAt(version.getEffectiveAt());
        }

        return response;
    }
}

