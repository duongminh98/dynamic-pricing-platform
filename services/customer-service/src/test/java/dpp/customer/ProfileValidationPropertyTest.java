package dpp.customer;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.dto.BaseProfileRequest;
import dpp.customer.validator.ProfileValidator;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 6: profile validation. Valid health attributes pass; an out-of-range
 * numeric field is rejected with PROFILE_FIELD_OUT_OF_RANGE; an invalid line is
 * INVALID_CATEGORICAL_VALUE; a missing required field is MISSING_REQUIRED_FIELDS.
 * Requirements: R1.2-R1.4, R2.6, R2.9.
 */
@Tag("Feature: dynamic-pricing-platform, Property 6")
class ProfileValidationPropertyTest {

    private final ProfileValidator validator = new ProfileValidator();

    private Map<String, Object> validHealthAttrs() {
        Map<String, Object> a = new HashMap<>();
        a.put("height_cm", 170);
        a.put("weight_kg", 65);
        a.put("bmi", 22.5);
        a.put("smoker", false);
        a.put("chronic_disease", false);
        a.put("diabetes", false);
        a.put("blood_pressure_problem", false);
        a.put("major_surgeries_count", 0);
        a.put("hospitalized_last_12m", false);
        a.put("medical_visit_count_12m", 1);
        return a;
    }

    private BaseProfileRequest baseRequest() {
        BaseProfileRequest r = new BaseProfileRequest();
        r.setAge(30);
        r.setGender("male");
        r.setProvince("Ha Noi");
        r.setOccupation("engineer");
        r.setMonthlyIncomeVnd(20_000_000L);
        r.setMaritalStatus("single");
        return r;
    }

    @Property(tries = 100)
    void validHealthProfileIsAccepted(@ForAll @IntRange(min = 51, max = 249) int heightCm) {
        Map<String, Object> attrs = validHealthAttrs();
        attrs.put("height_cm", heightCm);
        assertDoesNotThrow(() -> validator.validateLine("health", attrs));
    }

    @Property(tries = 100)
    void outOfRangeHeightRejectedWithRangeError(
            @ForAll @IntRange(min = 251, max = 1000) int heightCm) {
        Map<String, Object> attrs = validHealthAttrs();
        attrs.put("height_cm", heightCm);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateLine("health", attrs));
        assertEquals(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE, ex.getErrorCode());
    }

    @Property(tries = 100)
    void invalidLineRejectedWithCategoricalError(@ForAll int seed) {
        String[] invalid = {"life", "pet", "cyber", "boat", "", "HEALTH"};
        String line = invalid[Math.floorMod(seed, invalid.length)];
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateLine(line, validHealthAttrs()));
        assertEquals(ErrorCode.INVALID_CATEGORICAL_VALUE, ex.getErrorCode());
    }

    @Property(tries = 100)
    void missingRequiredHealthFieldRejected(@ForAll int seed) {
        String[] required = {"bmi", "smoker", "chronic_disease", "diabetes",
                "blood_pressure_problem", "major_surgeries_count",
                "hospitalized_last_12m", "medical_visit_count_12m"};
        String drop = required[Math.floorMod(seed, required.length)];
        Map<String, Object> attrs = validHealthAttrs();
        attrs.remove(drop);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateLine("health", attrs));
        assertEquals(ErrorCode.MISSING_REQUIRED_FIELDS, ex.getErrorCode());
    }

    @Property(tries = 100)
    void ageOutOfRangeRejectedWithRangeError(
            @ForAll @IntRange(min = 101, max = 200) int age) {
        BaseProfileRequest r = baseRequest();
        r.setAge(age);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validateBase(r));
        assertEquals(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE, ex.getErrorCode());
    }

    @Property(tries = 100)
    void invalidGenderRejectedWithCategoricalError(@ForAll int seed) {
        String[] bad = {"M", "", "FEMALE", "nam", "unknown"};
        BaseProfileRequest r = baseRequest();
        r.setGender(bad[Math.floorMod(seed, bad.length)]);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validateBase(r));
        assertEquals(ErrorCode.INVALID_CATEGORICAL_VALUE, ex.getErrorCode());
    }

    @Property(tries = 100)
    void invalidMotorbikeCategoricalRejected(@ForAll int seed) {
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("vehicle_plate", "29A-12345");
        attrs.put("vehicle_brand", "Honda");
        attrs.put("vehicle_model", "commuter");
        attrs.put("vehicle_segment", "standard");
        attrs.put("vehicle_age", 3);
        attrs.put("vehicle_value_vnd", 40_000_000L);
        attrs.put("engine_capacity_cc", 125);
        attrs.put("driving_experience_years", 5);
        attrs.put("annual_mileage_km", 8000);
        attrs.put("traffic_violation_count_12m", 0);
        attrs.put("parking_location", "unknown_spot"); // invalid
        attrs.put("anti_theft_device", true);
        attrs.put("primary_use", "personal");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateLine("motorbike", attrs));
        assertEquals(ErrorCode.INVALID_CATEGORICAL_VALUE, ex.getErrorCode());
    }

    @Test
    void nullLineAttributesRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateLine("health", null));
        assertEquals(ErrorCode.MISSING_REQUIRED_FIELDS, ex.getErrorCode());
    }

    @Property(tries = 100)
    void invalidProvinceRejectedWithCategoricalError(@ForAll int seed) {
        String[] bad = {"Hanoi", "hanoi", "HA NOI", "Saigon", "Ho Chi Minh", "New York", ""};
        BaseProfileRequest r = baseRequest();
        r.setProvince(bad[Math.floorMod(seed, bad.length)]);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validateBase(r));
        assertEquals(ErrorCode.INVALID_CATEGORICAL_VALUE, ex.getErrorCode());
    }

    @Property(tries = 100)
    void invalidOccupationRejectedWithCategoricalError(@ForAll int seed) {
        String[] bad = {"Engineer", "ENGINEER", "doctor", "pilot", "salesman", ""};
        BaseProfileRequest r = baseRequest();
        r.setOccupation(bad[Math.floorMod(seed, bad.length)]);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validateBase(r));
        assertEquals(ErrorCode.INVALID_CATEGORICAL_VALUE, ex.getErrorCode());
    }
}
