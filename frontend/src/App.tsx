import { Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import Shell from './components/Shell';

import Login from './pages/Login';
import AuthCallback from './pages/AuthCallback';
import AuthRedirect from './pages/AuthRedirect';
import Register from './pages/Register';
import Products from './pages/Products';
import ProductDetail from './pages/ProductDetail';
import Quote from './pages/Quote';
import Orders from './pages/Orders';
import OrderDetail from './pages/OrderDetail';
import PaymentResult from './pages/PaymentResult';
import Policies from './pages/Policies';
import PolicyDetail from './pages/PolicyDetail';
import Claims from './pages/Claims';
import Profile from './pages/Profile';
import Notifications from './pages/Notifications';

import AdminOverview from './pages/admin/AdminOverview';
import AdminOrders from './pages/admin/AdminOrders';
import AdminEndorsements from './pages/admin/AdminEndorsements';
import AdminClaims from './pages/admin/AdminClaims';
import AdminPolicies from './pages/admin/AdminPolicies';
import AdminBilling from './pages/admin/AdminBilling';
import AdminRefunds from './pages/admin/AdminRefunds';
import AdminCustomers from './pages/admin/AdminCustomers';
import AdminCatalog from './pages/admin/AdminCatalog';
import AdminModels from './pages/admin/AdminModels';

function CustomerRoute({ title, children }: { title: string; children: JSX.Element }) {
  const { isLoggedIn, isAdmin } = useAuth();
  const loc = useLocation();
  const returnTo = loc.pathname + loc.search;
  if (!isLoggedIn) return <AuthRedirect returnTo={returnTo} />;
  if (isAdmin) return <Navigate to="/admin" replace />;
  return <Shell title={title}>{children}</Shell>;
}

function AdminRoute({ title, children }: { title: string; children: JSX.Element }) {
  const { isLoggedIn, isAdmin } = useAuth();
  const loc = useLocation();
  const returnTo = loc.pathname + loc.search;
  if (!isLoggedIn) return <AuthRedirect returnTo={returnTo} />;
  if (!isAdmin) return <Navigate to="/products" replace />;
  return <Shell title={title}>{children}</Shell>;
}

export default function App() {
  const { isLoggedIn, isAdmin } = useAuth();
  const homeRedirect = isLoggedIn ? (isAdmin ? '/admin' : '/products') : '/login';

  return (
    <Routes>
      <Route path="/" element={<Navigate to={homeRedirect} replace />} />

      {/* public auth */}
      <Route path="/login" element={<Login />} />
      <Route path="/auth/callback" element={<AuthCallback />} />
      <Route path="/register" element={isLoggedIn ? <Navigate to={homeRedirect} replace /> : <Register />} />
      {/* VNPAY redirects here in the browser — public, display only */}
      <Route path="/payment-result" element={<PaymentResult />} />

      {/* customer */}
      <Route path="/products" element={<CustomerRoute title="Sản phẩm"><Products /></CustomerRoute>} />
      <Route path="/products/:id" element={<CustomerRoute title="Chi tiết sản phẩm"><ProductDetail /></CustomerRoute>} />
      <Route path="/quote" element={<CustomerRoute title="Báo giá"><Quote /></CustomerRoute>} />
      <Route path="/orders" element={<CustomerRoute title="Đơn hàng của tôi"><Orders /></CustomerRoute>} />
      <Route path="/orders/:id" element={<CustomerRoute title="Chi tiết đơn hàng"><OrderDetail /></CustomerRoute>} />
      <Route path="/policies" element={<CustomerRoute title="Hợp đồng của tôi"><Policies /></CustomerRoute>} />
      <Route path="/policies/:id" element={<CustomerRoute title="Chi tiết hợp đồng"><PolicyDetail /></CustomerRoute>} />
      <Route path="/claims" element={<CustomerRoute title="Yêu cầu bồi thường"><Claims /></CustomerRoute>} />
      <Route path="/profile" element={<CustomerRoute title="Hồ sơ rủi ro"><Profile /></CustomerRoute>} />
      <Route path="/notifications" element={<CustomerRoute title="Thông báo"><Notifications /></CustomerRoute>} />

      {/* admin */}
      <Route path="/admin" element={<AdminRoute title="Tổng quan"><AdminOverview /></AdminRoute>} />
      <Route path="/admin/orders" element={<AdminRoute title="Duyệt đơn hàng"><AdminOrders /></AdminRoute>} />
      <Route path="/admin/endorsements" element={<AdminRoute title="Yêu cầu sửa đổi"><AdminEndorsements /></AdminRoute>} />
      <Route path="/admin/claims" element={<AdminRoute title="Bồi thường"><AdminClaims /></AdminRoute>} />
      <Route path="/admin/policies" element={<AdminRoute title="Hợp đồng"><AdminPolicies /></AdminRoute>} />
      <Route path="/admin/billing" element={<AdminRoute title="Hóa đơn"><AdminBilling /></AdminRoute>} />
      <Route path="/admin/refunds" element={<AdminRoute title="Hoàn tiền"><AdminRefunds /></AdminRoute>} />
      <Route path="/admin/customers" element={<AdminRoute title="Khách hàng"><AdminCustomers /></AdminRoute>} />
      <Route path="/admin/catalog" element={<AdminRoute title="Danh mục & hệ số giá"><AdminCatalog /></AdminRoute>} />
      <Route path="/admin/models" element={<AdminRoute title="Quản trị mô hình"><AdminModels /></AdminRoute>} />

      <Route path="*" element={<Navigate to={homeRedirect} replace />} />
    </Routes>
  );
}





