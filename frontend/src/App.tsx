import type { ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { Center, Loader } from '@mantine/core';
import { useAuth } from './auth/AuthProvider';
import { RequireRole } from './auth/RequireRole';
import { AppLayout } from './layout/AppLayout';
import { LoginPage } from './pages/auth/LoginPage';
import { RegisterPage } from './pages/auth/RegisterPage';
import { ResetPasswordPage } from './pages/auth/PasswordPages';
import { InvitationPage } from './pages/auth/InvitationPage';
import { HomePage } from './pages/HomePage';
import { ExpenseCapturePage } from './pages/caregiver/ExpenseCapturePage';
import { MyExpensesPage } from './pages/caregiver/MyExpensesPage';
import { ExpenseEditPage } from './pages/caregiver/ExpenseEditPage';
import { ExpenseManualPage } from './pages/caregiver/ExpenseManualPage';
import { ReviewListPage } from './pages/manager/ReviewListPage';
import { CashPage } from './pages/manager/CashPage';
import { ContributionsPage } from './pages/manager/ContributionsPage';
import { ReportsPage } from './pages/manager/ReportsPage';
import { MembersPage } from './pages/manager/MembersPage';
import { SettingsPage } from './pages/manager/SettingsPage';
import { AdminGroupsPage } from './pages/admin/AdminGroupsPage';

function RequireLogin({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth();
  const location = useLocation();
  if (loading) {
    return (
      <Center h="100vh">
        <Loader />
      </Center>
    );
  }
  if (!me) {
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  }
  return <>{children}</>;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/registrieren" element={<RegisterPage />} />
      <Route path="/passwort-neu/:token" element={<ResetPasswordPage />} />
      <Route path="/einladung/:token" element={<InvitationPage />} />
      <Route
        element={
          <RequireLogin>
            <AppLayout />
          </RequireLogin>
        }
      >
        <Route index element={<HomePage />} />
        <Route path="beleg" element={<RequireRole roles={['manager', 'caregiver']}><ExpenseCapturePage /></RequireRole>} />
        <Route path="beleg/ohne-foto" element={<RequireRole roles={['manager', 'caregiver']}><ExpenseManualPage /></RequireRole>} />
        <Route path="meine-ausgaben" element={<RequireRole roles={['manager', 'caregiver']}><MyExpensesPage /></RequireRole>} />
        <Route path="ausgaben/:id/korrigieren" element={<RequireRole roles={['manager', 'caregiver']}><ExpenseEditPage /></RequireRole>} />
        <Route path="pruefung" element={<RequireRole roles={['manager']}><ReviewListPage /></RequireRole>} />
        <Route path="kasse" element={<RequireRole roles={['manager']}><CashPage /></RequireRole>} />
        <Route path="beitraege" element={<RequireRole roles={['manager']}><ContributionsPage /></RequireRole>} />
        <Route path="auswertungen" element={<RequireRole roles={['manager']}><ReportsPage /></RequireRole>} />
        <Route path="mitglieder" element={<RequireRole roles={['manager']}><MembersPage /></RequireRole>} />
        <Route path="einstellungen" element={<RequireRole roles={['manager']}><SettingsPage /></RequireRole>} />
        <Route path="admin" element={<RequireRole roles={['admin']}><AdminGroupsPage /></RequireRole>} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
