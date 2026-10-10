import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth, type AppRole } from './AuthProvider';

/** Zeigt die Kinder nur bei passender Rolle; sonst Umleitung auf die Startseite (oder nichts, wenn silent). */
export function RequireRole({ roles, children, silent = false }: { roles: AppRole[]; children: ReactNode; silent?: boolean }) {
  const { hasRole, loading } = useAuth();
  if (loading) return null;
  if (hasRole(roles)) return <>{children}</>;
  return silent ? null : <Navigate to="/" replace />;
}
