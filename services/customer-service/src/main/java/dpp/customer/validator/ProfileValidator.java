package dpp.customer.validator;

import dpp.common.api.ErrorCode;
import dpp.customer.dto.ProfileRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import dpp.common.api.ServiceException;

/**
 * Profile validation (R2.5, R2.6, R2.9, R2.14-R2.16). Runs BEFORE any DB write
 * so a bad value never reaches a DB CHECK (which would surface as 500). Numeric
 * fields out of range -> PROFILE_FIELD_OUT_OF_RANGE; bad categorical value ->
 * INVALID_CATEGORICAL_VALUE; missing required field -> MISSING_REQUIRED_FIELDS.
 */
@Component
public class ProfileValidator {

    private static final Set<String> VALID_LINES = Set.of("health", "motorbike", "car", "home", "accident", "travel");

    // Base demographic categoricals (dataset-aligned).
    // R2.2: gender ∈ {male, female, other} (English, lowercase — project convention).
    private static final Set<String> GENDERS = Set.of("male", "female", "other");
    private static final Set<String> URBAN_TIERS = Set.of("tier1", "urban", "rural");
    private static final Set<String> INCOME_LEVELS = Set.of("low", "lower_middle", "middle", "upper_middle", "high");
    private static final Set<String> MARITAL = Set.of("single", "married", "divorced_widowed");

    public void validate(ProfileRequest request) {
        if (!VALID_LINES.contains(request.getLine())) {
            throw categorical("line", request.getLine());
        }

        // Base demographics: range + categorical (R2.5/R2.6).
        checkRange("age", request.getAge(), 18, 100);
        checkRange("monthly_income_vnd", request.getMonthlyIncomeVnd(), 0, 999_999_999_999L);
        checkCategorical("gender", request.getGender(), GENDERS);
        checkCategorical("urban_tier", request.getUrbanTier(), URBAN_TIERS);
        checkCategorical("income_level", request.getIncomeLevel(), INCOME_LEVELS);
        checkCategorical("marital_status", request.getMaritalStatus(), MARITAL);

        Map<String, Object> attrs = request.getLineAttributes();
        if (attrs == null) {
            throw new ServiceException(ErrorCode.MISSING_REQUIRED_FIELDS, "lineAttributes missing", null);
        }

        switch (request.getLine()) {
            case "health" -> validateHealth(attrs);
            case "motorbike" -> validateMotorbike(attrs);
            case "car" -> validateCar(attrs);
            case "home" -> validateHome(attrs);
            case "accident" -> validateAccident(attrs);
            case "travel" -> validateTravel(attrs);
            default -> { }
        }
    }

    private void validateHealth(Map<String, Object> attrs) {
        checkNumberRange(attrs, "height_cm", 50, 250);
        checkNumberRange(attrs, "weight_kg", 2, 500);
        checkNumberRange(attrs, "bmi", 5, 100);
        checkRequired(attrs, "smoker");
        checkRequired(attrs, "chronic_disease");
        checkRequired(attrs, "diabetes");
        checkRequired(attrs, "blood_pressure_problem");
        checkNumberRange(attrs, "major_surgeries_count", 0, 50);
        checkRequired(attrs, "hospitalized_last_12m");
        checkNumberRange(attrs, "medical_visit_count_12m", 0, 365);
    }

    private void validateMotorbike(Map<String, Object> attrs) {
        checkRequired(attrs, "vehicle_brand");
        checkRequired(attrs, "vehicle_model");
        checkCategoricalAttr(attrs, "vehicle_segment", Set.of("standard", "mid", "premium", "economy", "luxury"));
        checkNumberRange(attrs, "vehicle_age", 0, 50);
        checkNumberRange(attrs, "vehicle_value_vnd", 0, 100_000_000_000L);
        checkNumberRange(attrs, "engine_capacity_cc", 0, 10_000);
        checkNumberRange(attrs, "driving_experience_years", 0, 90);
        checkNumberRange(attrs, "annual_mileage_km", 0, 500_000);
        checkNumberRange(attrs, "traffic_violation_count_12m", 0, 1000);
        checkCategoricalAttr(attrs, "parking_location", Set.of("yard", "indoor", "street", "garage"));
        checkRequired(attrs, "anti_theft_device");
        checkCategoricalAttr(attrs, "primary_use", Set.of("personal", "commute", "delivery", "business", "ride_hailing"));
    }

    private void validateCar(Map<String, Object> attrs) {
        validateMotorbike(attrs);
        checkNumberRange(attrs, "driver_count", 1, 20);
        checkCategoricalAttr(attrs, "garage_repair_option", Set.of("authorized", "standard"));
        checkRequired(attrs, "loan_or_leasing_flag");
    }

    private void validateHome(Map<String, Object> attrs) {
        checkCategoricalAttr(attrs, "property_type", Set.of("rural_house", "detached_house", "townhouse", "apartment"));
        checkNumberRange(attrs, "floor_area_m2", 1, 100_000);
        checkNumberRange(attrs, "number_of_floors", 1, 200);
        checkNumberRange(attrs, "building_age", 0, 300);
        checkCategoricalAttr(attrs, "construction_type", Set.of("brick", "reinforced_concrete", "mixed", "wood"));
        checkCategoricalAttr(attrs, "roof_type", Set.of("tile", "concrete", "metal", "mixed"));
        checkCategoricalAttr(attrs, "flood_risk_zone", Set.of("low", "medium", "high"));
        checkRequired(attrs, "fire_protection");
        checkRequired(attrs, "has_fire_alarm");
        checkRequired(attrs, "has_sprinkler");
        checkRequired(attrs, "security_system");
        checkNumberRange(attrs, "declared_property_value_vnd", 0, 1_000_000_000_000L);
    }

    private void validateAccident(Map<String, Object> attrs) {
        checkCategoricalAttr(attrs, "occupation_class", Set.of("low", "medium", "medium_high", "high"));
        checkCategoricalAttr(attrs, "workplace_risk_level", Set.of("low", "medium", "medium_high", "high"));
        checkCategoricalAttr(attrs, "commute_mode", Set.of("motorbike", "public_transport", "car", "walk_bicycle"));
        checkNumberRange(attrs, "commute_distance_km", 0, 1000);
        checkRequired(attrs, "sport_activity_flag");
        checkCategoricalAttr(attrs, "sport_risk_level", Set.of("none", "low", "medium", "high"));
        checkRequired(attrs, "hazardous_activity_exclusion_flag");
    }

    private void validateTravel(Map<String, Object> attrs) {
        checkCategoricalAttr(attrs, "domestic_or_international", Set.of("domestic", "international"));
        checkRequired(attrs, "destination_region");
        checkRequired(attrs, "destination_country");
        checkNumberRange(attrs, "trip_duration_days", 1, 3650);
        checkNumberRange(attrs, "traveler_count", 1, 1000);
        checkNumberRange(attrs, "trip_cost_vnd", 0, 100_000_000_000L);
        checkCategoricalAttr(attrs, "travel_purpose", Set.of("leisure", "study", "business", "family"));
        checkRequired(attrs, "has_baggage_cover");
        checkRequired(attrs, "has_trip_cancellation_cover");
    }

    // ?? helpers ??????????????????????????????????????????????????????????

    private ServiceException categorical(String field, Object value) {
        return new ServiceException(ErrorCode.INVALID_CATEGORICAL_VALUE, "Invalid categorical value",
                Map.of("field", field, "value", String.valueOf(value)));
    }

    private void checkCategorical(String field, String value, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) {
            throw categorical(field, value);
        }
    }

    private void checkCategoricalAttr(Map<String, Object> attrs, String field, Set<String> allowed) {
        checkRequired(attrs, field);
        Object v = attrs.get(field);
        if (!(v instanceof String) || !allowed.contains(v)) {
            throw categorical(field, v);
        }
    }

    private void checkRequired(Map<String, Object> attrs, String field) {
        if (!attrs.containsKey(field) || attrs.get(field) == null) {
            throw new ServiceException(ErrorCode.MISSING_REQUIRED_FIELDS, "Missing required field", Map.of("field", field));
        }
    }

    private void checkRange(String field, double value, double min, double max) {
        if (value < min || value > max) {
            throw new ServiceException(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE, "Field out of range",
                    Map.of("field", field, "min", min, "max", max));
        }
    }

    private void checkNumberRange(Map<String, Object> attrs, String field, double min, double max) {
        checkRequired(attrs, field);
        Object val = attrs.get(field);
        double num;
        if (val instanceof Number) {
            num = ((Number) val).doubleValue();
        } else {
            try {
                num = Double.parseDouble(val.toString());
            } catch (NumberFormatException e) {
                throw categorical(field, val);
            }
        }
        if (num < min || num > max) {
            throw new ServiceException(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE, "Field out of range",
                    Map.of("field", field, "min", min, "max", max));
        }
    }
}
