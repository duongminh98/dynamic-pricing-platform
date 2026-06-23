package dpp.customer.validator;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.customer.dto.ProfileRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class ProfileValidator {

    private static final Set<String> VALID_LINES = Set.of("health", "motorbike", "car", "home", "accident", "travel");

    public void validate(ProfileRequest request) {
        if (!VALID_LINES.contains(request.getLine())) {
            throw new ServiceException(ErrorCode.INVALID_CATEGORICAL_VALUE, "Invalid line", Map.of("line", request.getLine()));
        }
        
        Map<String, Object> attrs = request.getLineAttributes();
        if (attrs == null) {
            throw new ServiceException(ErrorCode.MISSING_REQUIRED_FIELDS, "lineAttributes missing", null);
        }

        switch (request.getLine()) {
            case "health":
                validateHealth(attrs);
                break;
            case "motorbike":
                validateMotorbike(attrs);
                break;
            case "car":
                validateCar(attrs);
                break;
            case "home":
                validateHome(attrs);
                break;
            case "accident":
                validateAccident(attrs);
                break;
            case "travel":
                validateTravel(attrs);
                break;
        }
    }

    private void validateHealth(Map<String, Object> attrs) {
        checkNumberRange(attrs, "height_cm", 50, 250);
        checkNumberRange(attrs, "weight_kg", 2, 500);
        checkRequired(attrs, "bmi");
        checkRequired(attrs, "smoker");
        checkRequired(attrs, "chronic_disease");
        checkRequired(attrs, "diabetes");
        checkRequired(attrs, "blood_pressure_problem");
        checkRequired(attrs, "major_surgeries_count");
        checkRequired(attrs, "hospitalized_last_12m");
        checkRequired(attrs, "medical_visit_count_12m");
    }

    private void validateMotorbike(Map<String, Object> attrs) {
        checkRequired(attrs, "vehicle_brand");
        checkRequired(attrs, "vehicle_model");
        checkRequired(attrs, "vehicle_segment");
        checkRequired(attrs, "vehicle_age");
        checkRequired(attrs, "vehicle_value_vnd");
        checkRequired(attrs, "engine_capacity_cc");
        checkRequired(attrs, "driving_experience_years");
        checkRequired(attrs, "annual_mileage_km");
        checkRequired(attrs, "traffic_violation_count_12m");
        checkRequired(attrs, "parking_location");
        checkRequired(attrs, "anti_theft_device");
        checkRequired(attrs, "primary_use");
    }

    private void validateCar(Map<String, Object> attrs) {
        validateMotorbike(attrs);
        checkRequired(attrs, "driver_count");
        checkRequired(attrs, "garage_repair_option");
        checkRequired(attrs, "loan_or_leasing_flag");
    }

    private void validateHome(Map<String, Object> attrs) {
        checkRequired(attrs, "home_type");
    }

    private void validateAccident(Map<String, Object> attrs) {
        checkRequired(attrs, "accident_history");
    }

    private void validateTravel(Map<String, Object> attrs) {
        checkRequired(attrs, "destination_country");
    }

    private void checkRequired(Map<String, Object> attrs, String field) {
        if (!attrs.containsKey(field) || attrs.get(field) == null) {
            throw new ServiceException(ErrorCode.MISSING_REQUIRED_FIELDS, "Missing required field", Map.of("field", field));
        }
    }

    private void checkNumberRange(Map<String, Object> attrs, String field, double min, double max) {
        checkRequired(attrs, field);
        Object val = attrs.get(field);
        double num = 0;
        if (val instanceof Number) {
            num = ((Number) val).doubleValue();
        } else {
            try {
                num = Double.parseDouble(val.toString());
            } catch (NumberFormatException e) {
                throw new ServiceException(ErrorCode.INVALID_CATEGORICAL_VALUE, "Invalid number format", Map.of("field", field));
            }
        }
        if (num < min || num > max) {
            throw new ServiceException(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE, "Field out of range", Map.of("field", field, "min", min, "max", max));
        }
    }
}
