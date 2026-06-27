package dpp.customer.validator;

import dpp.common.api.ErrorCode;
import dpp.customer.dto.BaseProfileRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import dpp.common.api.ServiceException;

import java.util.HashMap;

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
    private static final Set<String> MARITAL = Set.of("single", "married", "divorced_widowed");

    // 34 provinces from geo_risk.csv — the only allowed values for province.
    private static final Set<String> PROVINCES = Set.of(
            "Ha Noi", "Hai Phong", "Hue", "Da Nang", "TP Ho Chi Minh", "Can Tho",
            "Tuyen Quang", "Lao Cai", "Thai Nguyen", "Phu Tho",
            "Bac Ninh", "Hung Yen", "Ninh Binh",
            "Quang Tri", "Nghe An", "Ha Tinh",
            "Quang Ngai", "Gia Lai", "Khanh Hoa", "Lam Dong", "Dak Lak",
            "Dong Nai", "Tay Ninh",
            "Vinh Long", "Dong Thap", "An Giang", "Ca Mau",
            "Lang Son", "Cao Bang", "Dien Bien", "Lai Chau", "Son La",
            "Thanh Hoa", "Quang Ninh"
    );

    private static final Set<String> OCCUPATIONS = Set.of(
            "office_worker", "teacher", "engineer", "driver", "factory_worker",
            "business_owner", "farmer", "student", "freelancer", "retired",
            "healthcare_worker", "construction_worker"
    );

    // province → region lookup (derived from geo_risk.csv).
    private static final Map<String, String> PROVINCE_REGION = new HashMap<>();
    static {
        PROVINCE_REGION.put("Ha Noi", "Red River Delta");
        PROVINCE_REGION.put("Hai Phong", "Red River Delta");
        PROVINCE_REGION.put("Bac Ninh", "Red River Delta");
        PROVINCE_REGION.put("Hung Yen", "Red River Delta");
        PROVINCE_REGION.put("Ninh Binh", "Red River Delta");
        PROVINCE_REGION.put("Quang Ninh", "Red River Delta");
        PROVINCE_REGION.put("Hue", "North Central Coast");
        PROVINCE_REGION.put("Quang Tri", "North Central Coast");
        PROVINCE_REGION.put("Nghe An", "North Central Coast");
        PROVINCE_REGION.put("Ha Tinh", "North Central Coast");
        PROVINCE_REGION.put("Thanh Hoa", "North Central Coast");
        PROVINCE_REGION.put("Da Nang", "South Central Coast");
        PROVINCE_REGION.put("Quang Ngai", "South Central Coast");
        PROVINCE_REGION.put("Khanh Hoa", "South Central Coast");
        PROVINCE_REGION.put("Gia Lai", "Central Highlands");
        PROVINCE_REGION.put("Lam Dong", "Central Highlands");
        PROVINCE_REGION.put("Dak Lak", "Central Highlands");
        PROVINCE_REGION.put("TP Ho Chi Minh", "Southeast");
        PROVINCE_REGION.put("Dong Nai", "Southeast");
        PROVINCE_REGION.put("Tay Ninh", "Southeast");
        PROVINCE_REGION.put("Can Tho", "Mekong Delta");
        PROVINCE_REGION.put("Vinh Long", "Mekong Delta");
        PROVINCE_REGION.put("Dong Thap", "Mekong Delta");
        PROVINCE_REGION.put("An Giang", "Mekong Delta");
        PROVINCE_REGION.put("Ca Mau", "Mekong Delta");
        PROVINCE_REGION.put("Tuyen Quang", "Northern Midlands");
        PROVINCE_REGION.put("Lao Cai", "Northern Midlands");
        PROVINCE_REGION.put("Thai Nguyen", "Northern Midlands");
        PROVINCE_REGION.put("Phu Tho", "Northern Midlands");
        PROVINCE_REGION.put("Lang Son", "Northern Midlands");
        PROVINCE_REGION.put("Cao Bang", "Northern Midlands");
        PROVINCE_REGION.put("Dien Bien", "Northern Midlands");
        PROVINCE_REGION.put("Lai Chau", "Northern Midlands");
        PROVINCE_REGION.put("Son La", "Northern Midlands");
    }

    // province → urban_tier lookup (derived from geo_risk.csv urban_tier_geo column).
    private static final Map<String, String> PROVINCE_URBAN_TIER = new HashMap<>();
    static {
        PROVINCE_URBAN_TIER.put("Ha Noi", "tier1");
        PROVINCE_URBAN_TIER.put("Hai Phong", "tier1");
        PROVINCE_URBAN_TIER.put("Hue", "tier1");
        PROVINCE_URBAN_TIER.put("Da Nang", "tier1");
        PROVINCE_URBAN_TIER.put("TP Ho Chi Minh", "tier1");
        PROVINCE_URBAN_TIER.put("Can Tho", "tier1");
        PROVINCE_URBAN_TIER.put("Bac Ninh", "urban");
        PROVINCE_URBAN_TIER.put("Hung Yen", "urban");
        PROVINCE_URBAN_TIER.put("Khanh Hoa", "urban");
        PROVINCE_URBAN_TIER.put("Dong Nai", "urban");
        PROVINCE_URBAN_TIER.put("Tay Ninh", "urban");
        PROVINCE_URBAN_TIER.put("Thanh Hoa", "urban");
        PROVINCE_URBAN_TIER.put("Quang Ninh", "urban");
        PROVINCE_URBAN_TIER.put("Tuyen Quang", "rural");
        PROVINCE_URBAN_TIER.put("Lao Cai", "rural");
        PROVINCE_URBAN_TIER.put("Thai Nguyen", "rural");
        PROVINCE_URBAN_TIER.put("Phu Tho", "rural");
        PROVINCE_URBAN_TIER.put("Ninh Binh", "rural");
        PROVINCE_URBAN_TIER.put("Quang Tri", "rural");
        PROVINCE_URBAN_TIER.put("Nghe An", "rural");
        PROVINCE_URBAN_TIER.put("Ha Tinh", "rural");
        PROVINCE_URBAN_TIER.put("Quang Ngai", "rural");
        PROVINCE_URBAN_TIER.put("Gia Lai", "rural");
        PROVINCE_URBAN_TIER.put("Lam Dong", "rural");
        PROVINCE_URBAN_TIER.put("Dak Lak", "rural");
        PROVINCE_URBAN_TIER.put("Vinh Long", "rural");
        PROVINCE_URBAN_TIER.put("Dong Thap", "rural");
        PROVINCE_URBAN_TIER.put("An Giang", "rural");
        PROVINCE_URBAN_TIER.put("Ca Mau", "rural");
        PROVINCE_URBAN_TIER.put("Lang Son", "rural");
        PROVINCE_URBAN_TIER.put("Cao Bang", "rural");
        PROVINCE_URBAN_TIER.put("Dien Bien", "rural");
        PROVINCE_URBAN_TIER.put("Lai Chau", "rural");
        PROVINCE_URBAN_TIER.put("Son La", "rural");
    }

    public void validateBase(BaseProfileRequest request) {
        if (request.getAge() == null) {
            throw new ServiceException(ErrorCode.MISSING_REQUIRED_FIELDS, "Missing required field", Map.of("field", "age"));
        }
        if (request.getMonthlyIncomeVnd() == null) {
            throw new ServiceException(ErrorCode.MISSING_REQUIRED_FIELDS, "Missing required field", Map.of("field", "monthly_income_vnd"));
        }
        checkRange("age", request.getAge(), 18, 100);
        checkRange("monthly_income_vnd", request.getMonthlyIncomeVnd(), 1, 999_999_999_999L);
        checkCategorical("gender", request.getGender(), GENDERS);
        checkCategorical("province", request.getProvince(), PROVINCES);
        checkCategorical("occupation", request.getOccupation(), OCCUPATIONS);
        checkCategorical("marital_status", request.getMaritalStatus(), MARITAL);
    }

    public String deriveRegion(String province) {
        return PROVINCE_REGION.get(province);
    }

    public String deriveUrbanTier(String province) {
        return PROVINCE_URBAN_TIER.get(province);
    }

    public String deriveIncomeLevel(Long monthlyIncomeVnd) {
        if (monthlyIncomeVnd == null) return null;
        long income = monthlyIncomeVnd;
        if (income < 7_000_000) return "low";
        if (income < 12_000_000) return "lower_middle";
        if (income < 21_000_000) return "middle";
        if (income < 39_000_000) return "upper_middle";
        return "high";
    }

    public void validateLine(String line, Map<String, Object> attrs) {
        if (!VALID_LINES.contains(line)) {
            throw categorical("line", line);
        }
        if (attrs == null) {
            throw new ServiceException(ErrorCode.MISSING_REQUIRED_FIELDS, "lineAttributes missing", null);
        }

        switch (line) {
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
        checkRequired(attrs, "vehicle_plate");
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
        checkRequired(attrs, "property_address");
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
        checkRequired(attrs, "trip_start_date");
        checkRequired(attrs, "trip_end_date");
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

    // --- helpers ---

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
