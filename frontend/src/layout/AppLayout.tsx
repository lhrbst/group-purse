import { ActionIcon, AppShell, Burger, Group, Menu, NavLink, Text, useMantineColorScheme, Badge } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { NavLink as RouterLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { useAuthLogout } from '../api/generated/auth-controller/auth-controller';
import { useReportManager } from '../api/generated/report-controller/report-controller';
import { GroupSwitcher } from './GroupSwitcher';

interface NavItem {
  to: string;
  label: string;
}

const CAREGIVER_NAV: NavItem[] = [
  { to: '/', label: 'Start' },
  { to: '/beleg', label: 'Beleg erfassen' },
  { to: '/meine-ausgaben', label: 'Meine Ausgaben' },
];

const MANAGER_NAV: NavItem[] = [
  { to: '/', label: 'Übersicht' },
  { to: '/pruefung', label: 'Prüfung' },
  { to: '/beleg', label: 'Beleg erfassen' },
  { to: '/kasse', label: 'Kasse' },
  { to: '/beitraege', label: 'Elternbeiträge' },
  { to: '/auswertungen', label: 'Auswertungen' },
  { to: '/mitglieder', label: 'Mitglieder' },
  { to: '/einstellungen', label: 'Einstellungen' },
];

export function AppLayout() {
  const [opened, { toggle, close }] = useDisclosure();
  const { me, isAdmin, isManager, isCaregiver } = useAuth();
  const { colorScheme, toggleColorScheme } = useMantineColorScheme();
  const logout = useAuthLogout();
  const dashboard = useReportManager({ query: { enabled: isManager } });
  const pending = dashboard.data?.status === 200 ? (dashboard.data.data.eingereichtAnzahl ?? 0) : 0;

  const items = [...(isManager ? MANAGER_NAV : isCaregiver ? CAREGIVER_NAV : [{ to: '/', label: 'Start' }])];
  if (isAdmin) items.push({ to: '/admin', label: 'Wohngruppen (Admin)' });

  async function signOut() {
    try {
      await logout.mutateAsync();
    } finally {
      // komplett neu laden: verwirft alle Daten und zeigt keine Fehler alter Abfragen
      window.location.assign('/login');
    }
  }

  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 240, breakpoint: 'sm', collapsed: { mobile: !opened } }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="Menü" />
            <Text fw={700}>Gruppenkasse</Text>
          </Group>
          <Group wrap="nowrap" gap="xs">
            <GroupSwitcher />
            <ActionIcon variant="default" size="lg" aria-label="Farbschema wechseln" onClick={() => toggleColorScheme()}>
              {colorScheme === 'dark' ? '☀' : '☾'}
            </ActionIcon>
            <Menu position="bottom-end">
              <Menu.Target>
                <ActionIcon variant="default" size="lg" aria-label="Benutzermenü">
                  {me?.name?.charAt(0).toUpperCase() ?? '?'}
                </ActionIcon>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Label>{me?.name}</Menu.Label>
                <Menu.Item onClick={signOut}>Abmelden</Menu.Item>
              </Menu.Dropdown>
            </Menu>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs">
        {items.map((item) => (
          <NavLink
            key={item.to}
            component={RouterLink}
            to={item.to}
            end={item.to === '/'}
            label={item.label}
            onClick={close}
            rightSection={item.to === '/pruefung' && pending > 0 ? <Badge size="sm" circle>{pending}</Badge> : undefined}
          />
        ))}
      </AppShell.Navbar>
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}
