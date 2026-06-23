import { Routes, Route, Link } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import Login from './pages/Login';
import Quote from './pages/Quote';
import Products from './pages/Products';
import Policies from './pages/Policies';
import AdminConsole from './pages/admin/AdminConsole';

export default function App() {
  const { isLoggedIn } = useAuth();
  return (
    <div>
      <nav><Link to='/'>Home</Link> | <Link to='/products'>Products</Link> | <Link to='/quote'>Quote</Link> | <Link to='/policies'>Policies</Link> | <Link to='/admin'>Admin</Link></nav>
      <Routes>
        <Route path='/login' element={<Login />} />
        <Route path='/products' element={<Products />} />
        <Route path='/quote' element={isLoggedIn ? <Quote /> : <Login />} />
        <Route path='/policies' element={isLoggedIn ? <Policies /> : <Login />} />
        <Route path='/admin' element={isLoggedIn ? <AdminConsole /> : <Login />} />
      </Routes>
    </div>
  );
}
