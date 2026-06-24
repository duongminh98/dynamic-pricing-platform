import { Routes, Route, Link, Navigate } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import Login from './pages/Login';
import Register from './pages/Register';
import Quote from './pages/Quote';
import Products from './pages/Products';
import Policies from './pages/Policies';
import Notifications from './pages/Notifications';
import Checkout from './pages/Checkout';
import PaymentResult from './pages/PaymentResult';
import AdminConsole from './pages/admin/AdminConsole';

export default function App() {
  const { isLoggedIn, isAdmin, logout } = useAuth();
  return (
    <div>
      <nav>
        <Link to='/'>Home</Link> | <Link to='/products'>Products</Link>
        {isLoggedIn && <> | <Link to='/quote'>Quote</Link> | <Link to='/policies'>Policies</Link> | <Link to='/checkout'>Checkout</Link> | <Link to='/notifications'>Notifications</Link></>}
        {isAdmin && <> | <Link to='/admin'>Admin</Link></>}
        {!isLoggedIn && <> | <Link to='/login'>Login</Link> | <Link to='/register'>Register</Link></>}
        {isLoggedIn && <> | <button onClick={logout}>Logout</button></>}
      </nav>
      <Routes>
        <Route path='/' element={<Products />} />
        <Route path='/login' element={<Login />} />
        <Route path='/register' element={<Register />} />
        <Route path='/products' element={<Products />} />
        <Route path='/quote' element={isLoggedIn ? <Quote /> : <Navigate to='/login' replace />} />
        <Route path='/policies' element={isLoggedIn ? <Policies /> : <Navigate to='/login' replace />} />
        <Route path='/checkout' element={isLoggedIn ? <Checkout /> : <Navigate to='/login' replace />} />
        <Route path='/payment-result' element={<PaymentResult />} />
        <Route path='/notifications' element={isLoggedIn ? <Notifications /> : <Navigate to='/login' replace />} />
        <Route path='/admin' element={isAdmin ? <AdminConsole /> : <Navigate to='/' replace />} />
      </Routes>
    </div>
  );
}
