/* Domain constants mirrored from the API contract (section 2.5 / 2.6 / 5).
   These are the source of truth the backend validates against. */

export const LINES = ['health', 'motorbike', 'car', 'home', 'accident', 'travel'] as const;
export type Line = (typeof LINES)[number];

export const LINE_LABEL: Record<Line, string> = {
  health: 'Sức khỏe',
  motorbike: 'Xe máy',
  car: 'Ô tô',
  home: 'Nhà ở',
  accident: 'Tai nạn',
  travel: 'Du lịch',
};

export const LINE_ICON: Record<Line, string> = {
  health: '♥',
  motorbike: '⛟',
  car: '◈',
  home: '⌂',
  accident: '✦',
  travel: '✈',
};

export const GENDERS = ['male', 'female', 'other'] as const;
export const MARITAL = ['single', 'married', 'divorced_widowed'] as const;

export const OCCUPATIONS = [
  'office_worker', 'teacher', 'engineer', 'driver', 'factory_worker',
  'business_owner', 'farmer', 'student', 'freelancer', 'retired',
  'healthcare_worker', 'construction_worker',
] as const;

export const PROVINCES = [
  'Ha Noi', 'Hai Phong', 'Bac Ninh', 'Hung Yen', 'Ninh Binh', 'Quang Ninh',
  'Hue', 'Quang Tri', 'Nghe An', 'Ha Tinh', 'Thanh Hoa', 'Da Nang',
  'Quang Ngai', 'Khanh Hoa', 'Gia Lai', 'Lam Dong', 'Dak Lak', 'TP Ho Chi Minh',
  'Dong Nai', 'Tay Ninh', 'Can Tho', 'Vinh Long', 'Dong Thap', 'An Giang',
  'Ca Mau', 'Tuyen Quang', 'Lao Cai', 'Thai Nguyen', 'Phu Tho', 'Lang Son',
  'Cao Bang', 'Dien Bien', 'Lai Chau', 'Son La',
] as const;

/* ---------- per-line attribute field descriptors (section 2.6) ---------- */

export type FieldKind = 'number' | 'bool' | 'text' | 'enum' | 'date';

export interface AttrField {
  key: string;
  label: string;
  kind: FieldKind;
  min?: number;
  max?: number;
  options?: string[];
  optional?: boolean;
}

export const LINE_FIELDS: Record<Line, AttrField[]> = {
  health: [
    { key: 'height_cm', label: 'Chiều cao (cm)', kind: 'number', min: 50, max: 250 },
    { key: 'weight_kg', label: 'Cân nặng (kg)', kind: 'number', min: 2, max: 500 },
    { key: 'bmi', label: 'BMI', kind: 'number', min: 5, max: 100 },
    { key: 'smoker', label: 'Hút thuốc', kind: 'bool' },
    { key: 'chronic_disease', label: 'Bệnh mãn tính', kind: 'bool' },
    { key: 'diabetes', label: 'Tiểu đường', kind: 'bool' },
    { key: 'blood_pressure_problem', label: 'Vấn đề huyết áp', kind: 'bool' },
    { key: 'major_surgeries_count', label: 'Số ca phẫu thuật lớn', kind: 'number', min: 0, max: 50 },
    { key: 'hospitalized_last_12m', label: 'Nhập viện 12 tháng qua', kind: 'bool' },
    { key: 'medical_visit_count_12m', label: 'Số lần khám 12 tháng', kind: 'number', min: 0, max: 365 },
  ],
  motorbike: [
    { key: 'vehicle_plate', label: 'Biển số', kind: 'text' },
    { key: 'vehicle_brand', label: 'Hãng xe', kind: 'text' },
    { key: 'vehicle_model', label: 'Dòng xe', kind: 'text' },
    { key: 'vehicle_segment', label: 'Phân khúc', kind: 'enum', options: ['standard', 'mid', 'premium', 'economy', 'luxury'] },
    { key: 'vehicle_age', label: 'Tuổi xe (năm)', kind: 'number', min: 0, max: 50 },
    { key: 'vehicle_value_vnd', label: 'Giá trị xe (₫)', kind: 'number', min: 0, max: 100000000000 },
    { key: 'engine_capacity_cc', label: 'Dung tích (cc)', kind: 'number', min: 0, max: 10000 },
    { key: 'driving_experience_years', label: 'Số năm kinh nghiệm', kind: 'number', min: 0, max: 90 },
    { key: 'annual_mileage_km', label: 'Quãng đường/năm (km)', kind: 'number', min: 0, max: 500000 },
    { key: 'traffic_violation_count_12m', label: 'Vi phạm 12 tháng', kind: 'number', min: 0, max: 1000 },
    { key: 'parking_location', label: 'Nơi đỗ xe', kind: 'enum', options: ['yard', 'indoor', 'street', 'garage'] },
    { key: 'anti_theft_device', label: 'Thiết bị chống trộm', kind: 'bool' },
    { key: 'primary_use', label: 'Mục đích dùng', kind: 'enum', options: ['personal', 'commute', 'delivery', 'business', 'ride_hailing'] },
  ],
  car: [
    { key: 'vehicle_plate', label: 'Biển số', kind: 'text' },
    { key: 'vehicle_brand', label: 'Hãng xe', kind: 'text' },
    { key: 'vehicle_model', label: 'Dòng xe', kind: 'text' },
    { key: 'vehicle_segment', label: 'Phân khúc', kind: 'enum', options: ['standard', 'mid', 'premium', 'economy', 'luxury'] },
    { key: 'vehicle_age', label: 'Tuổi xe (năm)', kind: 'number', min: 0, max: 50 },
    { key: 'vehicle_value_vnd', label: 'Giá trị xe (₫)', kind: 'number', min: 0, max: 100000000000 },
    { key: 'engine_capacity_cc', label: 'Dung tích (cc)', kind: 'number', min: 0, max: 10000 },
    { key: 'driving_experience_years', label: 'Số năm kinh nghiệm', kind: 'number', min: 0, max: 90 },
    { key: 'annual_mileage_km', label: 'Quãng đường/năm (km)', kind: 'number', min: 0, max: 500000 },
    { key: 'traffic_violation_count_12m', label: 'Vi phạm 12 tháng', kind: 'number', min: 0, max: 1000 },
    { key: 'parking_location', label: 'Nơi đỗ xe', kind: 'enum', options: ['yard', 'indoor', 'street', 'garage'] },
    { key: 'anti_theft_device', label: 'Thiết bị chống trộm', kind: 'bool' },
    { key: 'primary_use', label: 'Mục đích dùng', kind: 'enum', options: ['personal', 'commute', 'delivery', 'business', 'ride_hailing'] },
    { key: 'driver_count', label: 'Số người lái', kind: 'number', min: 1, max: 20 },
    { key: 'garage_repair_option', label: 'Lựa chọn sửa chữa', kind: 'enum', options: ['authorized', 'standard'] },
    { key: 'loan_or_leasing_flag', label: 'Xe trả góp / thuê', kind: 'bool' },
  ],
  home: [
    { key: 'property_address', label: 'Địa chỉ', kind: 'text' },
    { key: 'property_type', label: 'Loại nhà', kind: 'enum', options: ['rural_house', 'detached_house', 'townhouse', 'apartment'] },
    { key: 'floor_area_m2', label: 'Diện tích sàn (m²)', kind: 'number', min: 1, max: 100000 },
    { key: 'number_of_floors', label: 'Số tầng', kind: 'number', min: 1, max: 200 },
    { key: 'building_age', label: 'Tuổi công trình', kind: 'number', min: 0, max: 300 },
    { key: 'construction_type', label: 'Kết cấu', kind: 'enum', options: ['brick', 'reinforced_concrete', 'mixed', 'wood'] },
    { key: 'roof_type', label: 'Loại mái', kind: 'enum', options: ['tile', 'concrete', 'metal', 'mixed'] },
    { key: 'flood_risk_zone', label: 'Vùng ngập lụt', kind: 'enum', options: ['low', 'medium', 'high'] },
    { key: 'fire_protection', label: 'Phòng cháy chữa cháy', kind: 'bool' },
    { key: 'has_fire_alarm', label: 'Có báo cháy', kind: 'bool' },
    { key: 'has_sprinkler', label: 'Có sprinkler', kind: 'bool' },
    { key: 'security_system', label: 'Hệ thống an ninh', kind: 'bool' },
    { key: 'declared_property_value_vnd', label: 'Giá trị khai báo (₫)', kind: 'number', min: 0, max: 1000000000000 },
  ],
  accident: [
    { key: 'occupation_class', label: 'Nhóm nghề', kind: 'enum', options: ['low', 'medium', 'medium_high', 'high'] },
    { key: 'workplace_risk_level', label: 'Rủi ro nơi làm', kind: 'enum', options: ['low', 'medium', 'medium_high', 'high'] },
    { key: 'commute_mode', label: 'Phương tiện đi lại', kind: 'enum', options: ['motorbike', 'public_transport', 'car', 'walk_bicycle'] },
    { key: 'commute_distance_km', label: 'Quãng đường đi lại (km)', kind: 'number', min: 0, max: 1000 },
    { key: 'sport_activity_flag', label: 'Có chơi thể thao', kind: 'bool' },
    { key: 'sport_risk_level', label: 'Mức rủi ro thể thao', kind: 'enum', options: ['none', 'low', 'medium', 'high'] },
    { key: 'hazardous_activity_exclusion_flag', label: 'Loại trừ HĐ nguy hiểm', kind: 'bool' },
  ],
  travel: [
    { key: 'trip_start_date', label: 'Ngày bắt đầu', kind: 'date' },
    { key: 'trip_end_date', label: 'Ngày kết thúc', kind: 'date' },
    { key: 'domestic_or_international', label: 'Phạm vi', kind: 'enum', options: ['domestic', 'international'] },
    { key: 'destination_region', label: 'Khu vực đến', kind: 'text' },
    { key: 'destination_country', label: 'Quốc gia đến', kind: 'text' },
    { key: 'trip_duration_days', label: 'Số ngày', kind: 'number', min: 1, max: 3650 },
    { key: 'traveler_count', label: 'Số người', kind: 'number', min: 1, max: 1000 },
    { key: 'trip_cost_vnd', label: 'Chi phí chuyến đi (₫)', kind: 'number', min: 0, max: 100000000000 },
    { key: 'travel_purpose', label: 'Mục đích', kind: 'enum', options: ['leisure', 'study', 'business', 'family'] },
    { key: 'has_baggage_cover', label: 'Bảo hiểm hành lý', kind: 'bool' },
    { key: 'has_trip_cancellation_cover', label: 'Bảo hiểm hủy chuyến', kind: 'bool' },
  ],
};
