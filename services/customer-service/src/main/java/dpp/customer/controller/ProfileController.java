package dpp.customer.controller;

import dpp.customer.dto.BaseProfileRequest;
import dpp.customer.dto.LineProfileRequest;
import dpp.customer.dto.LineProfileResponse;
import dpp.customer.dto.ProfileResponse;
import dpp.customer.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/customers/me/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @PutMapping
    public ProfileResponse updateBaseProfile(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BaseProfileRequest request) {
        return profileService.updateBaseProfile(jwt.getSubject(), email(jwt), request);
    }

    @GetMapping
    public ProfileResponse getProfile(@AuthenticationPrincipal Jwt jwt) {
        return profileService.getProfile(jwt.getSubject(), email(jwt));
    }

    @PutMapping("/lines/{line}")
    public LineProfileResponse upsertLineProfile(@AuthenticationPrincipal Jwt jwt, @PathVariable String line,
                                                  @Valid @RequestBody LineProfileRequest request) {
        return profileService.upsertLineProfile(jwt.getSubject(), line, request);
    }

    @GetMapping("/lines/{line}")
    public LineProfileResponse getLineProfile(@AuthenticationPrincipal Jwt jwt, @PathVariable String line) {
        return profileService.getLineProfile(jwt.getSubject(), line);
    }

    private String email(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getClaimAsString("preferred_username");
    }
}
