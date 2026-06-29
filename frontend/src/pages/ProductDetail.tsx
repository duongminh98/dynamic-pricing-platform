import { useParams, useNavigate, Link } from 'react-router-dom';
import { useApi, vndLabel } from '../lib/format';
import { LINE_LABEL, LINE_ICON, Line } from '../lib/domain';
import { viProduct } from '../lib/labels';
import { Loading, ErrorBanner } from '../lib/ui';

interface ProductDetail {
  product_id: string;
  category: string;
  product_name: string;
  coverage_amount_vnd: number;
  deductible_vnd: number;
  base_premium_vnd: number;
  admin_fee_vnd: number;
}

export default function ProductDetail() {
  const { id } = useParams();
  const nav = useNavigate();
  const { data, error, loading } = useApi<ProductDetail>(`/products/${id}`, [id]);

  if (loading) return <Loading />;
  if (error) return <ErrorBanner error={error} />;
  if (!data) return null;

  const line = data.category as Line;

  return (
    <div className="stack" style={{ maxWidth: 720 }}>
      <Link to="/products" className="btn-link">← Tất cả sản phẩm</Link>

      <div className="card card-pad-lg stack">
        <div className="row-between">
          <span className="prod-line">{LINE_ICON[line]} {LINE_LABEL[line] || data.category}</span>
          <span className="tag mono">{data.product_id}</span>
        </div>
        <h2>{viProduct(data.product_id, data.product_name)}</h2>

        <div className="panel">
          <div className="kv" style={{ borderTop: 'none' }}>
            <span className="kv-k">Số tiền bảo hiểm</span>
            <span className="kv-v">{vndLabel(data.coverage_amount_vnd)}</span>
          </div>
          <div className="kv">
            <span className="kv-k">Mức miễn thường</span>
            <span className="kv-v">{vndLabel(data.deductible_vnd)}</span>
          </div>
          <div className="kv">
            <span className="kv-k">Phí tham chiếu</span>
            <span className="kv-v">{vndLabel(data.base_premium_vnd)}</span>
          </div>
          <div className="kv">
            <span className="kv-k">Phí quản lý</span>
            <span className="kv-v">{vndLabel(data.admin_fee_vnd)}</span>
          </div>
        </div>

        <div className="alert alert-info">
          Đây là phí tham chiếu. Phí thực tế = <span className="mono">phí thuần × hệ số tải + phí quản lý</span>, tính riêng cho hồ sơ của bạn.
        </div>

        <button className="btn btn-primary" onClick={() => nav(`/quote?product=${data.product_id}`)}>
          Lấy báo giá cho tôi →
        </button>
      </div>
    </div>
  );
}
