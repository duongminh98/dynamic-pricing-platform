/* Vietnamese display labels. The KEYS are the English wire values the API
   uses; the VALUES are what the user sees. Wire values are never translated —
   only display is. Unknown keys fall back to a humanized version of the key. */

import { humanize } from './format';
import { LINE_FIELDS } from './domain';

/* ---------- lifecycle statuses (orders, invoices, policies, claims, refunds, credits, endorsements) ---------- */
const STATUS: Record<string, string> = {
  // orders
  PENDING_REVIEW: 'Chờ duyệt',
  PENDING_PAYMENT: 'Chờ thanh toán',
  COMPLETED: 'Hoàn tất',
  REJECTED: 'Bị từ chối',
  CANCELLED: 'Đã hủy',
  // invoices
  unpaid: 'Chưa thanh toán',
  paid: 'Đã thanh toán',
  voided: 'Đã hủy',
  // policies
  active: 'Đang hiệu lực',
  cancelled: 'Đã hủy',
  expired: 'Hết hạn',
  pending_payment: 'Chờ thanh toán',
  // claims
  pending: 'Chờ xử lý',
  approved: 'Đã duyệt',
  rejected: 'Bị từ chối',
  // refunds
  completed: 'Hoàn tất',
  // credits
  open: 'Còn hiệu lực',
  partially_applied: 'Áp dụng một phần',
  exhausted: 'Đã dùng hết',
  refunded: 'Đã hoàn',
  // endorsements
  APPROVED: 'Đã duyệt',
  APPLIED: 'Đã áp dụng',
  // review decisions
  APPROVE: 'Duyệt',
  REJECT: 'Từ chối',
  // payment poll
  success: 'Thành công',
  failed: 'Thất bại',
  // generic delivery
  sent: 'Đã gửi',
};

export function viStatus(s: string | null | undefined): string {
  if (!s) return '—';
  return STATUS[s] || STATUS[s.toLowerCase()] || humanize(s);
}

/* ---------- categorical enums (profile + line attributes + claims) ---------- */
const ENUM: Record<string, string> = {
  // gender
  male: 'Nam', female: 'Nữ', other: 'Khác',
  // marital_status
  single: 'Độc thân', married: 'Đã kết hôn', divorced_widowed: 'Ly hôn / Góa',
  // occupation
  office_worker: 'Nhân viên văn phòng', teacher: 'Giáo viên', engineer: 'Kỹ sư',
  driver: 'Tài xế', factory_worker: 'Công nhân nhà máy', business_owner: 'Chủ doanh nghiệp',
  farmer: 'Nông dân', student: 'Học sinh / Sinh viên', freelancer: 'Lao động tự do',
  retired: 'Nghỉ hưu', healthcare_worker: 'Nhân viên y tế', construction_worker: 'Công nhân xây dựng',
  // income_level + urban_tier (derived, shown read-only)
  low: 'Thấp', lower_middle: 'Trung bình thấp', middle: 'Trung bình',
  upper_middle: 'Trung bình cao', high: 'Cao',
  tier1: 'Đô thị loại 1', urban: 'Thành thị', rural: 'Nông thôn',
  // vehicle_segment
  standard: 'Tiêu chuẩn', mid: 'Trung cấp', premium: 'Cao cấp', economy: 'Phổ thông', luxury: 'Hạng sang',
  // parking_location
  yard: 'Sân nhà', indoor: 'Trong nhà', street: 'Ngoài đường', garage: 'Nhà để xe',
  // primary_use
  personal: 'Cá nhân', commute: 'Đi làm', delivery: 'Giao hàng', business: 'Kinh doanh', ride_hailing: 'Xe công nghệ',
  // garage_repair_option
  authorized: 'Chính hãng',
  // property_type
  rural_house: 'Nhà nông thôn', detached_house: 'Nhà riêng', townhouse: 'Nhà phố', apartment: 'Chung cư',
  // construction_type
  brick: 'Gạch', reinforced_concrete: 'Bê tông cốt thép', mixed: 'Hỗn hợp', wood: 'Gỗ',
  // roof_type
  tile: 'Ngói', concrete: 'Bê tông', metal: 'Kim loại',
  // flood_risk_zone / risk levels
  medium: 'Trung bình', medium_high: 'Trung bình cao',
  // commute_mode
  motorbike: 'Xe máy', public_transport: 'Phương tiện công cộng', car: 'Ô tô', walk_bicycle: 'Đi bộ / Xe đạp',
  // sport_risk_level
  none: 'Không',
  // travel
  domestic: 'Trong nước', international: 'Quốc tế',
  leisure: 'Du lịch', study: 'Học tập', family: 'Gia đình',
  // claim loss types
  collision: 'Va chạm', theft: 'Trộm cắp', fire: 'Cháy nổ', flood: 'Ngập lụt',
  medical: 'Y tế', accident: 'Tai nạn', liability: 'Trách nhiệm',
  // claim sanctions
  proportional: 'Theo tỷ lệ', cancel: 'Hủy bảo hiểm', reject: 'Từ chối',
};

export function viEnum(v: string | null | undefined): string {
  if (!v) return '—';
  return ENUM[v] || humanize(v);
}

/* ---------- notification types ---------- */
const NOTIF: Record<string, string> = {
  OrderApproved: 'Đơn hàng được duyệt',
  OrderRejected: 'Đơn hàng bị từ chối',
  OrderSubmitted: 'Đã gửi đơn hàng',
  PolicyIssued: 'Hợp đồng đã phát hành',
  PolicyRenewed: 'Hợp đồng đã tái tục',
  PolicyCancelled: 'Hợp đồng đã hủy',
  EndorsementApplied: 'Sửa đổi đã áp dụng',
  EndorsementRejected: 'Sửa đổi bị từ chối',
  EndorsementPendingPayment: 'Sửa đổi chờ thanh toán',
  EndorsementOverdue: 'Sửa đổi quá hạn',
  EndorsementCreditIssued: 'Đã cấp tín dụng phí',
  ClaimStatusChanged: 'Cập nhật bồi thường',
  ClaimSubmitted: 'Đã gửi yêu cầu bồi thường',
  RefundRequested: 'Yêu cầu hoàn tiền',
  RefundCompleted: 'Hoàn tiền hoàn tất',
  RefundRejected: 'Hoàn tiền bị từ chối',
  InvoiceVoided: 'Hóa đơn đã hủy',
};

export function viNotifType(t: string | null | undefined): string {
  if (!t) return '';
  return NOTIF[t] || t.replace(/([A-Z])/g, ' $1').trim();
}

/* ---------- product names (keyed by product_id) ----------
   Keyed by the stable product_id so it works even where only the id is
   available (orders, policies). Falls back to the server's product_name,
   then to the raw id. TPL → TNDS (trách nhiệm dân sự), the standard VN term. */
const PRODUCT: Record<string, string> = {
  HEALTH_BASIC: 'Sức khỏe Cơ bản',
  HEALTH_STANDARD: 'Sức khỏe Tiêu chuẩn',
  HEALTH_PREMIUM: 'Sức khỏe Cao cấp',
  MOTORBIKE_TPL: 'Xe máy TNDS',
  MOTORBIKE_THEFT_FIRE: 'Xe máy Cháy/Trộm',
  MOTORBIKE_COMPREHENSIVE: 'Xe máy Toàn diện',
  CAR_TPL: 'Ô tô TNDS',
  CAR_PHYSICAL_BASIC: 'Ô tô Vật chất Cơ bản',
  CAR_PHYSICAL_PREMIUM: 'Ô tô Vật chất Cao cấp',
  TRAVEL_DOMESTIC: 'Du lịch Trong nước',
  TRAVEL_INTERNATIONAL: 'Du lịch Quốc tế',
  ACCIDENT_BASIC: 'Tai nạn Cơ bản',
  ACCIDENT_STANDARD: 'Tai nạn Tiêu chuẩn',
  ACCIDENT_PREMIUM: 'Tai nạn Cao cấp',
  HOME_FIRE_FLOOD_BASIC: 'Nhà ở Cháy/Ngập Cơ bản',
  HOME_FIRE_FLOOD_PREMIUM: 'Nhà ở Cháy/Ngập Cao cấp',
};

export function viProduct(productId: string | null | undefined, fallback?: string | null): string {
  if (!productId) return fallback || '—';
  return PRODUCT[productId] || fallback || productId;
}


/* ---------- feature / field keys (SHAP explanation + certificate change) ----------
   Built once from the per-line attribute labels, plus base-profile and monetary
   fields the engine/cert use that aren't part of any line schema. */
const FEATURE: Record<string, string> = {
  // base profile
  age: 'Tuổi', gender: 'Giới tính', province: 'Tỉnh / Thành', region: 'Khu vực',
  urban_tier: 'Phân tầng đô thị', occupation: 'Nghề nghiệp', income_level: 'Mức thu nhập',
  monthly_income_vnd: 'Thu nhập / tháng', marital_status: 'Tình trạng hôn nhân',
  // monetary fields surfaced in the certificate `change` block
  coverage_amount_vnd: 'Số tiền bảo hiểm', deductible_vnd: 'Mức miễn thường', premium: 'Phí bảo hiểm',
};
// fold in every per-line attribute label (key -> Vietnamese label)
for (const fields of Object.values(LINE_FIELDS)) {
  for (const f of fields) if (!FEATURE[f.key]) FEATURE[f.key] = f.label;
}

export function viFeature(key: string | null | undefined): string {
  if (!key) return '';
  return FEATURE[key] || humanize(key);
}

