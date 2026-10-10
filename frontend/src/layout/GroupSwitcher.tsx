import { Select } from '@mantine/core';
import { useAuth } from '../auth/AuthProvider';
import { useAuthSetActiveGroup } from '../api/generated/auth-controller/auth-controller';
import { useAdminList } from '../api/generated/admin-controller/admin-controller';
import { notifyError } from '../lib/errors';

/** Auswahl der aktiven Wohngruppe; Admins sehen alle Gruppen, alle anderen ihre aktiven Mitgliedschaften. */
export function GroupSwitcher() {
  const { me, isAdmin, reset } = useAuth();
  const setActive = useAuthSetActiveGroup();
  const all = useAdminList({ query: { enabled: isAdmin } });

  const options = new Map<string, string>();
  me?.mitgliedschaften?.filter((m) => m.status === 'AKTIV').forEach((m) => options.set(m.wohngruppeId!, m.wohngruppeName ?? ''));
  if (isAdmin && all.data?.status === 200) {
    all.data.data.forEach((g) => options.set(g.id!, g.name ?? ''));
  }
  if (options.size === 0) return null;
  if (options.size === 1 && me?.activeGroupId) {
    return <span style={{ fontSize: 14 }}>{[...options.values()][0]}</span>;
  }

  return (
    <Select
      aria-label="Wohngruppe"
      size="xs"
      w={170}
      placeholder="Wohngruppe wählen"
      allowDeselect={false}
      data={[...options.entries()].map(([value, label]) => ({ value, label }))}
      value={me?.activeGroupId ?? null}
      onChange={async (value) => {
        if (!value) return;
        try {
          await setActive.mutateAsync({ data: { wohngruppeId: value } });
          await reset();
        } catch (e) {
          notifyError(e);
        }
      }}
    />
  );
}
