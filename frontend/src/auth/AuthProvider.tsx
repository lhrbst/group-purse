import { createContext, useCallback, useContext, useMemo, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useAuthMe, getAuthMeQueryKey } from '../api/generated/auth-controller/auth-controller';
import type { MeDto } from '../api/model';

export type AppRole = 'admin' | 'manager' | 'caregiver';

export interface AuthState {
  me: MeDto | undefined;
  loading: boolean;
  isAdmin: boolean;
  /** Kassenverwalter in der aktiven Gruppe (Admins zählen dazu) */
  isManager: boolean;
  isCaregiver: boolean;
  hasActiveGroup: boolean;
  hasRole: (roles: AppRole[]) => boolean;
  /** Nach Anmeldung/Gruppenwechsel: alle Daten verwerfen und neu laden */
  reset: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const query = useAuthMe({ query: { retry: false, staleTime: 30_000 } });
  const me = query.data?.status === 200 ? (query.data.data as MeDto) : undefined;

  const reset = useCallback(async () => {
    qc.removeQueries({ predicate: (q) => q.queryKey[0] !== getAuthMeQueryKey()[0] });
    await qc.invalidateQueries({ queryKey: getAuthMeQueryKey() });
  }, [qc]);

  const value = useMemo<AuthState>(() => {
    const isAdmin = me?.istAdmin ?? false;
    const isManager = me?.activeRole === 'KASSENVERWALTER';
    const isCaregiver = me?.activeRole === 'PFLEGEKRAFT';
    return {
      me,
      loading: query.isLoading,
      isAdmin,
      isManager,
      isCaregiver,
      hasActiveGroup: !!me?.activeGroupId && !!me.activeRole,
      hasRole: (roles) =>
        (roles.includes('admin') && isAdmin) || (roles.includes('manager') && isManager) || (roles.includes('caregiver') && isCaregiver),
      reset,
    };
  }, [me, query.isLoading, reset]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth braucht einen AuthProvider');
  return ctx;
}
