import { useEffect } from 'react';
import { Alert, Button, Group, Stack, Text, Title } from '@mantine/core';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { useAuthSetActiveGroup } from '../api/generated/auth-controller/auth-controller';
import { MEMBER_STATUS_LABEL, ROLLE_LABEL } from '../lib/labels';
import { Loading } from '../components/Loading';
import { CaregiverHomePage } from './caregiver/CaregiverHomePage';
import { ManagerDashboardPage } from './manager/ManagerDashboardPage';

export function HomePage() {
  const { me, hasActiveGroup, isManager, isAdmin, reset } = useAuth();
  const setActive = useAuthSetActiveGroup();
  const active = me?.mitgliedschaften?.filter((m) => m.status === 'AKTIV') ?? [];
  const autoSelect = !hasActiveGroup && !isAdmin && active.length === 1 ? active[0].wohngruppeId : undefined;

  useEffect(() => {
    if (autoSelect) {
      setActive.mutateAsync({ data: { wohngruppeId: autoSelect } }).then(() => reset());
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoSelect]);

  if (hasActiveGroup) return isManager ? <ManagerDashboardPage /> : <CaregiverHomePage />;
  if (isAdmin) return <Navigate to="/admin" replace />;
  if (autoSelect) return <Loading />;

  if (active.length > 1) {
    return (
      <Stack>
        <Title order={2}>Wohngruppe wählen</Title>
        <Text c="dimmed">Du gehörst mehreren Wohngruppen an. Oben rechts kannst du jederzeit wechseln.</Text>
        <Group>
          {active.map((m) => (
            <Button
              key={m.mitgliedschaftId}
              variant="light"
              onClick={async () => {
                await setActive.mutateAsync({ data: { wohngruppeId: m.wohngruppeId! } });
                await reset();
              }}
            >
              {m.wohngruppeName} ({ROLLE_LABEL[m.rolle ?? ''] ?? m.rolle})
            </Button>
          ))}
        </Group>
      </Stack>
    );
  }

  return (
    <Stack>
      <Title order={2}>Hallo {me?.name}</Title>
      <Alert color="yellow" title="Noch kein Zugriff">
        Du hast noch keinen aktiven Zugang zu einer Wohngruppe. Ein Kassenverwalter muss dich zuerst freischalten.
        {(me?.mitgliedschaften?.length ?? 0) > 0 && (
          <ul>
            {me?.mitgliedschaften?.map((m) => (
              <li key={m.mitgliedschaftId}>
                {m.wohngruppeName}: {MEMBER_STATUS_LABEL[m.status ?? ''] ?? m.status}
              </li>
            ))}
          </ul>
        )}
      </Alert>
      <Group>
        <Button variant="default" onClick={() => reset()}>
          Status neu prüfen
        </Button>
      </Group>
    </Stack>
  );
}
