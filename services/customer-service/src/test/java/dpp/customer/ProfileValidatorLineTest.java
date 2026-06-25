package dpp.customer;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.dto.ProfileRequest;
import dpp.customer.validator.ProfileValidator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProfileValidatorLineTest {

    private final ProfileValidator validator = new ProfileValidator();

    private ProfileRequest request(String line, Map<String, Object> attrs) {
        ProfileRequest r = new ProfileRequest();
        r.setAge(30);
        r.setGender("male");
        r.setProvince("Ha Noi");
        r.setRegion("Red River Delta");
        r.setUrbanTier("tier1");
        r.setOccupation("engineer");
        r.setIncomeLevel("middle");
        r.setMonthlyIncomeVnd(20_000_000L);
        r.setMaritalStatus("single");
        r.setLine(line);
        r.setLineAttributes(attrs);
        return r;
    }

    private Map<String, Object> validMotorbikeAttrs() {
        Map<String, Object> a = new HashMap<>();
        a.put("vehicle_brand", "Honda");
        a.put("vehicle_model", "Wave");
        a.put("vehicle_segment", "standard");
        a.put("vehicle_age", 3);
        a.put("vehicle_value_vnd", 40_000_000L);
        a.put("engine_capacity_cc", 125);
        a.put("driving_experience_years", 5);
        a.put("annual_mileage_km", 8000);
        a.put("traffic_violation_count_12m", 0);
        a.put("parking_location", "yard");
        a.put("anti_theft_device", true);
        a.put("primary_use", "personal");
        return a;
    }

    private Map<String, Object> validHomeAttrs() {
        Map<String, Object> a = new HashMap<>();
        a.put("property_type", "apartment");
        a.put("floor_area_m2", 80);
        a.put("number_of_floors", 1);
        a.put("building_age", 5);
        a.put("construction_type", "reinforced_concrete");
        a.put("roof_type", "concrete");
        a.put("flood_risk_zone", "low");
        a.put("fire_protection", true);
        a.put("has_fire_alarm", true);
        a.put("has_sprinkler", false);
        a.put("security_system", false);
        a.put("declared_property_value_vnd", 2_000_000_000L);
        return a;
    }

    private Map<String, Object> validAccidentAttrs() {
        Map<String, Object> a = new HashMap<>();
        a.put("occupation_class", "low");
        a.put("workplace_risk_level", "low");
        a.put("commute_mode", "motorbike");
        a.put("commute_distance_km", 10);
        a.put("sport_activity_flag", false);
        a.put("sport_risk_level", "none");
        a.put("hazardous_activity_exclusion_flag", true);
        return a;
    }

    private Map<String, Object> validTravelAttrs() {
        Map<String, Object> a = new HashMap<>();
        a.put("domestic_or_international", "domestic");
        a.put("destination_region", "North");
        a.put("destination_country", "Vietnam");
        a.put("trip_duration_days", 5);
        a.put("traveler_count", 2);
        a.put("trip_cost_vnd", 5_000_000L);
        a.put("travel_purpose", "leisure");
        a.put("has_baggage_cover", true);
        a.put("has_trip_cancellation_cover", false);
        return a;
    }

    @Test
    void validMotorbikeProfileIsAccepted() {
        assertDoesNotThrow(() -> validator.validate(request("motorbike", validMotorbikeAttrs())));
    }

    @Test
    void validCarProfileIsAccepted() {
        Map<String, Object> attrs = validMotorbikeAttrs();
        attrs.put("driver_count", 1);
        attrs.put("garage_repair_option", "authorized");
        attrs.put("loan_or_leasing_flag", false);
        assertDoesNotThrow(() -> validator.validate(request("car", attrs)));
    }

    @Test
    void validHomeProfileIsAccepted() {
        assertDoesNotThrow(() -> validator.validate(request("home", validHomeAttrs())));
    }

    @Test
    void validAccidentProfileIsAccepted() {
        assertDoesNotThrow(() -> validator.validate(request("accident", validAccidentAttrs())));
    }

    @Test
    void validTravelProfileIsAccepted() {
        assertDoesNotThrow(() -> validator.validate(request("travel", validTravelAttrs())));
    }

    @Test
    void homeInvalidPropertyTypeRejected() {
        Map<String, Object> attrs = validHomeAttrs();
        attrs.put("property_type", "mansion");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(request("home", attrs)));
        assertEquals(ErrorCode.INVALID_CATEGORICAL_VALUE, ex.getErrorCode());
    }

    @Test
    void accidentInvalidOccupationClassRejected() {
        Map<String, Object> attrs = validAccidentAttrs();
        attrs.put("occupation_class", "extreme");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(request("accident", attrs)));
        assertEquals(ErrorCode.INVALID_CATEGORICAL_VALUE, ex.getErrorCode());
    }

    @Test
    void travelMissingDestinationRejected() {
        Map<String, Object> attrs = validTravelAttrs();
        attrs.remove("destination_country");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(request("travel", attrs)));
        assertEquals(ErrorCode.MISSING_REQUIRED_FIELDS, ex.getErrorCode());
    }

    @Test
    void motorbikeOutOfRangeVehicleAgeRejected() {
        Map<String, Object> attrs = validMotorbikeAttrs();
        attrs.put("vehicle_age", 999);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(request("motorbike", attrs)));
        assertEquals(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE, ex.getErrorCode());
    }

    @Test
    void carMissingDriverCountRejected() {
        Map<String, Object> attrs = validMotorbikeAttrs();
        attrs.put("garage_repair_option", "standard");
        attrs.put("loan_or_leasing_flag", true);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(request("car", attrs)));
        assertEquals(ErrorCode.MISSING_REQUIRED_FIELDS, ex.getErrorCode());
    }

    @Test
    void invalidIncomeLevelRejected() {
        ProfileRequest r = request("health", validMotorbikeAttrs());
        r.setLineAttributes(new HashMap<>(Map.of("height_cm", 170, "weight_kg", 65, "bmi", 22.5,
                "smoker", false, "chronic_disease", false, "diabetes", false,
                "blood_pressure_problem", false, "major_surgeries_count", 0,
                "hospitalized_last_12m", false, "medical_visit_count_12m", 1)));
        r.setIncomeLevel("ultra_rich");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(r));
        assertEquals(ErrorCode.INVALID_CATEGORICAL_VALUE, ex.getErrorCode());
    }

    @Test
    void invalidMaritalStatusRejected() {
        ProfileRequest r = request("health", new HashMap<>(Map.of(
                "height_cm", 170, "weight_kg", 65, "bmi", 22.5,
                "smoker", false, "chronic_disease", false, "diabetes", false,
                "blood_pressure_problem", false, "major_surgeries_count", 0,
                "hospitalized_last_12m", false, "medical_visit_count_12m", 1)));
        r.setMaritalStatus("complicated");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(r));
        assertEquals(ErrorCode.INVALID_CATEGORICAL_VALUE, ex.getErrorCode());
    }

    @Test
    void invalidUrbanTierRejected() {
        ProfileRequest r = request("health", new HashMap<>(Map.of(
                "height_cm", 170, "weight_kg", 65, "bmi", 22.5,
                "smoker", false, "chronic_disease", false, "diabetes", false,
                "blood_pressure_problem", false, "major_surgeries_count", 0,
                "hospitalized_last_12m", false, "medical_visit_count_12m", 1)));
        r.setUrbanTier("metropolis");
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(r));
        assertEquals(ErrorCode.INVALID_CATEGORICAL_VALUE, ex.getErrorCode());
    }

    @Test
    void outOfRangeMonthlyIncomeRejected() {
        ProfileRequest r = request("health", new HashMap<>(Map.of(
                "height_cm", 170, "weight_kg", 65, "bmi", 22.5,
                "smoker", false, "chronic_disease", false, "diabetes", false,
                "blood_pressure_problem", false, "major_surgeries_count", 0,
                "hospitalized_last_12m", false, "medical_visit_count_12m", 1)));
        r.setMonthlyIncomeVnd(-1L);
        ServiceException ex = assertThrows(ServiceException.class, () -> validator.validate(r));
        assertEquals(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE, ex.getErrorCode());
    }

    @Test
    void numberRangeWithNonNumericValueRejected() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("height_cm", "not-a-number");
        attrs.put("weight_kg", 65);
        attrs.put("bmi", 22.5);
        attrs.put("smoker", false);
        attrs.put("chronic_disease", false);
        attrs.put("diabetes", false);
        attrs.put("blood_pressure_problem", false);
        attrs.put("major_surgeries_count", 0);
        attrs.put("hospitalized_last_12m", false);
        attrs.put("medical_visit_count_12m", 1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(request("health", attrs)));
        assertEquals(ErrorCode.INVALID_CATEGORICAL_VALUE, ex.getErrorCode());
    }
}
