import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, Route, Routes } from 'react-router';
import type { PrincipalKind } from './api/types';
import { CategoriesPage } from './manager/CategoriesPage';
import { DevicesPage } from './manager/DevicesPage';
import { ManagerLayout } from './manager/ManagerLayout';
import { ProductsPage } from './manager/ProductsPage';
import { RoutesPage } from './manager/RoutesPage';
import { UsersPage } from './manager/UsersPage';
import { VenuesPage } from './manager/VenuesPage';
import { EnrolPage } from './pages/EnrolPage';
import { LoginPage } from './pages/LoginPage';
import { PinPage } from './pages/PinPage';
import { RegisterPage } from './pages/RegisterPage';
import { useSession } from './session/SessionContext';

function Loading() {
  const { t } = useTranslation();
  return <p className="centered">{t('app.loading')}</p>;
}

/** Sends each caller to its home: managers to the manager area, cashiers to the register, devices to the PIN page. */
function Home() {
  const { me, loading } = useSession();
  if (loading) return <Loading />;
  if (me?.kind === 'USER') return <Navigate to="/manager" replace />;
  if (me?.kind === 'CASHIER') return <Navigate to="/register" replace />;
  if (me?.kind === 'DEVICE') return <Navigate to="/pin" replace />;
  return <Navigate to="/login" replace />;
}

function RequireKind({
  kind,
  redirect,
  children,
}: {
  kind: PrincipalKind;
  redirect: string;
  children: ReactNode;
}) {
  const { me, loading } = useSession();
  if (loading) return <Loading />;
  return me?.kind === kind ? children : <Navigate to={redirect} replace />;
}

export function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/enrol" element={<EnrolPage />} />
      <Route path="/pin" element={<PinPage />} />
      <Route
        path="/register"
        element={
          <RequireKind kind="CASHIER" redirect="/pin">
            <RegisterPage />
          </RequireKind>
        }
      />
      <Route
        path="/manager"
        element={
          <RequireKind kind="USER" redirect="/login">
            <ManagerLayout />
          </RequireKind>
        }
      >
        <Route index element={<Navigate to="products" replace />} />
        <Route path="venues" element={<VenuesPage />} />
        <Route path="users" element={<UsersPage />} />
        <Route path="devices" element={<DevicesPage />} />
        <Route path="routes" element={<RoutesPage />} />
        <Route path="categories" element={<CategoriesPage />} />
        <Route path="products" element={<ProductsPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
