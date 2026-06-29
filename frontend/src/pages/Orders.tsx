import { useNavigate } from 'react-router-dom';
import { useApi, vndLabel, dateTime } from '../lib/format';
import { viProduct } from '../lib/labels';
import { Loading, ErrorBanner, EmptyState, StatusPill } from '../lib/ui';

interface OrderResponse {
  order_id: string; product_id: string; final_premium_vnd: number;
  status: string; created_at: string; invoice_id: string | null;
}

export default function Orders() {
  const nav = useNavigate();
  const { data, error, loading } = useApi<OrderResponse[]>('/orders');

  return (
    <div className="stack">
      <div>
        <p className="eyebrow">Đơn hàng</p>
        <h2>Đơn hàng của tôi</h2>
      </div>
      {loading && <Loading />}
      <ErrorBanner error={error} />
      {data && data.length === 0 && <EmptyState title="Bạn chưa có đơn hàng nào" hint="Lấy báo giá và đặt mua để bắt đầu." />}

      {data && data.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr><th>Sản phẩm</th><th>Phí</th><th>Trạng thái</th><th>Ngày tạo</th><th></th></tr>
            </thead>
            <tbody>
              {data.map((o) => (
                <tr key={o.order_id} style={{ cursor: 'pointer' }} onClick={() => nav(`/orders/${o.order_id}`)}>
                  <td>{viProduct(o.product_id)}</td>
                  <td className="num">{vndLabel(o.final_premium_vnd)}</td>
                  <td><StatusPill status={o.status} /></td>
                  <td className="muted">{dateTime(o.created_at)}</td>
                  <td className="num">
                    {o.status === 'PENDING_PAYMENT' && o.invoice_id && <span className="pill pill-wait">cần thanh toán</span>}
                    <span className="btn-link" style={{ marginLeft: 8 }}>Xem →</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
