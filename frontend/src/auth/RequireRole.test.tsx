import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { RequireRole } from './RequireRole';
import { renderWithProviders } from '../test/utils';
import * as authModule from './AuthProvider';

function mockAuth(partial: Partial<authModule.AuthState>) {
  const state: authModule.AuthState = {
    me: undefined,
    loading: false,
    isAdmin: false,
    isManager: false,
    isCaregiver: false,
    hasActiveGroup: true,
    hasRole: (roles) =>
      (roles.includes('admin') && !!partial.isAdmin) ||
      (roles.includes('manager') && !!partial.isManager) ||
      (roles.includes('caregiver') && !!partial.isCaregiver),
    reset: async () => undefined,
    ...partial,
  };
  vi.spyOn(authModule, 'useAuth').mockReturnValue(state);
}

describe('RequireRole', () => {
  it('renders children for a matching role', () => {
    mockAuth({ isManager: true });
    renderWithProviders(<RequireRole roles={['manager']}>Geheim</RequireRole>);
    expect(screen.getByText('Geheim')).toBeInTheDocument();
  });

  it('hides children for another role when silent', () => {
    mockAuth({ isCaregiver: true });
    renderWithProviders(<RequireRole roles={['manager']} silent>Geheim</RequireRole>);
    expect(screen.queryByText('Geheim')).not.toBeInTheDocument();
  });

  it('lets an admin through where admin is allowed', () => {
    mockAuth({ isAdmin: true });
    renderWithProviders(<RequireRole roles={['admin', 'manager']}>Admin-Bereich</RequireRole>);
    expect(screen.getByText('Admin-Bereich')).toBeInTheDocument();
  });
});
