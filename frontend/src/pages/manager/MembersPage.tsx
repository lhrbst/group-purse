import { useState } from 'react';
import { Badge, Button, Card, CopyButton, Group, Stack, Table, Text, Title } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useMemberActivate, useMemberBlock, useMemberGroup, useMemberList, useMemberNewCode, useMemberPasswordLink } from '../../api/generated/member-controller/member-controller';
import { useAuth } from '../../auth/AuthProvider';
import { ActionModal } from '../../components/ActionModal';
import { LoadError, Loading } from '../../components/Loading';
import { notifyError, notifySuccess } from '../../lib/errors';
import { MEMBER_STATUS_LABEL, ROLLE_LABEL } from '../../lib/labels';

const STATUS_COLOR: Record<string, string> = { WARTEND: 'yellow', AKTIV: 'green', GESPERRT: 'gray' };

export function MembersPage() {
  const qc = useQueryClient();
  const { me, isAdmin } = useAuth();
  const members = useMemberList();
  const group = useMemberGroup();
  const activate = useMemberActivate();
  const block = useMemberBlock();
  const newCode = useMemberNewCode();
  const [confirmCode, setConfirmCode] = useState(false);
  const passwordLink = useMemberPasswordLink();
  const [link, setLink] = useState<{ name: string; url: string } | null>(null);

  async function run(fn: () => Promise<unknown>, message: string) {
    try {
      await fn();
      await qc.invalidateQueries();
      notifySuccess(message);
    } catch (e) {
      notifyError(e);
    }
  }

  if (members.isLoading) return <Loading />;
  if (members.isError || members.data?.status !== 200) return <LoadError error={members.error} />;
  const code = group.data?.status === 200 ? group.data.data.beitrittscode : undefined;

  return (
    <Stack>
      <Title order={2}>Mitglieder</Title>

      <Card withBorder>
        <Group justify="space-between">
          <div>
            <Text c="dimmed" size="sm">Beitrittscode für Pflegekräfte</Text>
            <Title order={3} ff="monospace">{code ?? '–'}</Title>
            <Text size="xs" c="dimmed">Pflegekräfte geben ihn bei der Registrierung ein und warten dann auf deine Freischaltung.</Text>
          </div>
          <Group>
            {code && (
              <CopyButton value={code}>
                {({ copied, copy }) => (
                  <Button variant="light" onClick={copy}>{copied ? 'Kopiert' : 'Kopieren'}</Button>
                )}
              </CopyButton>
            )}
            <Button variant="subtle" color="red" onClick={() => setConfirmCode(true)}>Neu erzeugen</Button>
          </Group>
        </Group>
      </Card>

      <Table.ScrollContainer minWidth={600}>
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Name</Table.Th>
              <Table.Th>E-Mail</Table.Th>
              <Table.Th>Rolle</Table.Th>
              <Table.Th>Status</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {members.data.data.map((m) => {
              const locked = m.rolle === 'KASSENVERWALTER' && !isAdmin;
              const self = m.personId === me?.personId;
              return (
                <Table.Tr key={m.mitgliedschaftId}>
                  <Table.Td>{m.name}</Table.Td>
                  <Table.Td>{m.email}</Table.Td>
                  <Table.Td>{ROLLE_LABEL[m.rolle ?? '']}</Table.Td>
                  <Table.Td><Badge variant="light" color={STATUS_COLOR[m.status ?? '']}>{MEMBER_STATUS_LABEL[m.status ?? '']}</Badge></Table.Td>
                  <Table.Td>
                    <Group justify="flex-end" gap="xs">
                      {!locked && !self && (
                        <Button
                          size="compact-sm"
                          variant="subtle"
                          onClick={async () => {
                            try {
                              const res = await passwordLink.mutateAsync({ id: m.mitgliedschaftId! });
                              if (res.status === 200) setLink({ name: m.name ?? '', url: res.data.link ?? '' });
                            } catch (e) {
                              notifyError(e);
                            }
                          }}
                        >
                          Passwort-Link
                        </Button>
                      )}
                      {m.status !== 'AKTIV' && !locked && (
                        <Button size="compact-sm" onClick={() => run(() => activate.mutateAsync({ id: m.mitgliedschaftId! }), `${m.name} ist freigeschaltet.`)}>
                          {m.status === 'GESPERRT' ? 'Wieder freischalten' : 'Freischalten'}
                        </Button>
                      )}
                      {m.status === 'AKTIV' && !locked && !self && (
                        <Button size="compact-sm" color="red" variant="light" onClick={() => run(() => block.mutateAsync({ id: m.mitgliedschaftId! }), `${m.name} ist gesperrt.`)}>
                          Sperren
                        </Button>
                      )}
                    </Group>
                  </Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
      <Text size="xs" c="dimmed">Ausgeschiedene Personen werden gesperrt, nicht gelöscht. Ihre Belege und Buchungen bleiben erhalten.</Text>

      <ActionModal opened={link !== null} onClose={() => setLink(null)} title={`Passwort zurücksetzen: ${link?.name ?? ''}`}>
        <Stack>
          <Text size="sm">Gib diesen Link persönlich an {link?.name} weiter (24 Stunden gültig, nur einmal verwendbar). Damit kann ein neues Passwort gesetzt werden.</Text>
          <Text size="xs" ff="monospace" style={{ wordBreak: 'break-all' }}>{link?.url}</Text>
          <Group justify="flex-end">
            <CopyButton value={link?.url ?? ''}>
              {({ copied, copy }) => <Button variant="light" onClick={copy}>{copied ? 'Kopiert' : 'Link kopieren'}</Button>}
            </CopyButton>
            <Button onClick={() => setLink(null)}>Fertig</Button>
          </Group>
        </Stack>
      </ActionModal>

      <ActionModal opened={confirmCode} onClose={() => setConfirmCode(false)} title="Neuen Beitrittscode erzeugen">
        <Stack>
          <Text size="sm">Der alte Code funktioniert danach nicht mehr. Bereits registrierte Personen sind nicht betroffen.</Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirmCode(false)}>Abbrechen</Button>
            <Button
              color="red"
              loading={newCode.isPending}
              onClick={async () => {
                await run(() => newCode.mutateAsync(), 'Neuer Beitrittscode erzeugt.');
                setConfirmCode(false);
              }}
            >
              Neu erzeugen
            </Button>
          </Group>
        </Stack>
      </ActionModal>
    </Stack>
  );
}
