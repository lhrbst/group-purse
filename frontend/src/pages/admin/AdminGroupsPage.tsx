import { useState } from 'react';
import { Alert, Button, Card, CopyButton, Group, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { useAdminCreate, useAdminInvite, useAdminList } from '../../api/generated/admin-controller/admin-controller';
import { useAuthSetActiveGroup } from '../../api/generated/auth-controller/auth-controller';
import type { InviteResponse, WohngruppeDto } from '../../api/model';
import { useAuth } from '../../auth/AuthProvider';
import { ActionModal } from '../../components/ActionModal';
import { LoadError, Loading } from '../../components/Loading';
import { notifyError, notifySuccess } from '../../lib/errors';
import { formatEuro } from '../../lib/format';
import { zodValidate } from '../../lib/zodForm';

function InviteDialog({ group, onClose }: { group: WohngruppeDto; onClose: () => void }) {
  const invite = useAdminInvite();
  const [result, setResult] = useState<InviteResponse | null>(null);
  const form = useForm({
    initialValues: { email: '' },
    validate: zodValidate(z.object({ email: z.string().trim().min(1, 'Bitte E-Mail eingeben').email('Keine gültige E-Mail-Adresse') })),
  });
  if (result) {
    return (
      <Stack>
        <Alert color="green">Einladung erzeugt. Gib den Link der Person weiter, z. B. per Messenger.</Alert>
        <Text size="sm">Einladungslink (7 Tage gültig):</Text>
        <Text size="xs" ff="monospace" style={{ wordBreak: 'break-all' }}>{result.link}</Text>
        <Group justify="flex-end">
          <CopyButton value={result.link ?? ''}>
            {({ copied, copy }) => <Button variant="light" onClick={copy}>{copied ? 'Kopiert' : 'Link kopieren'}</Button>}
          </CopyButton>
          <Button onClick={onClose}>Fertig</Button>
        </Group>
      </Stack>
    );
  }
  return (
    <form
      onSubmit={form.onSubmit(async (v) => {
        try {
          const res = await invite.mutateAsync({ id: group.id!, data: { email: v.email.trim() } });
          if (res.status === 200) setResult(res.data);
        } catch (e) {
          notifyError(e);
        }
      })}
    >
      <Stack>
        <Text size="sm" c="dimmed">Die Person wird Kassenverwalter von „{group.name}“. Hat sie schon ein Konto, wird die Gruppe dort ergänzt.</Text>
        <TextInput label="E-Mail" type="email" data-autofocus {...form.getInputProps('email')} />
        <Button type="submit" loading={invite.isPending}>Einladung senden</Button>
      </Stack>
    </form>
  );
}

export function AdminGroupsPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { reset } = useAuth();
  const list = useAdminList();
  const create = useAdminCreate();
  const setActive = useAuthSetActiveGroup();
  const [inviteFor, setInviteFor] = useState<WohngruppeDto | null>(null);
  const form = useForm({ initialValues: { name: '' }, validate: zodValidate(z.object({ name: z.string().trim().min(1, 'Bitte einen Namen eingeben') })) });

  if (list.isLoading) return <Loading />;
  if (list.isError || list.data?.status !== 200) return <LoadError error={list.error} />;

  return (
    <Stack>
      <Title order={2}>Wohngruppen</Title>
      <Card withBorder maw={560}>
        <form
          onSubmit={form.onSubmit(async (v) => {
            try {
              await create.mutateAsync({ data: { name: v.name.trim() } });
              await qc.invalidateQueries();
              form.reset();
              notifySuccess('Wohngruppe angelegt.');
            } catch (e) {
              notifyError(e);
            }
          })}
        >
          <Group align="flex-end">
            <TextInput label="Neue Wohngruppe" placeholder="z. B. WG Sonnenhof" style={{ flex: 1 }} {...form.getInputProps('name')} />
            <Button type="submit" loading={create.isPending}>Anlegen</Button>
          </Group>
        </form>
      </Card>

      <Table.ScrollContainer minWidth={600}>
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Name</Table.Th>
              <Table.Th>Beitrittscode</Table.Th>
              <Table.Th>Mindestbetrag</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {list.data.data.map((g) => (
              <Table.Tr key={g.id}>
                <Table.Td>{g.name}</Table.Td>
                <Table.Td ff="monospace">{g.beitrittscode}</Table.Td>
                <Table.Td>{formatEuro(g.mindestbetragKasse)}</Table.Td>
                <Table.Td>
                  <Group gap="xs" justify="flex-end" wrap="nowrap">
                    <Button size="compact-sm" variant="light" onClick={() => setInviteFor(g)}>Kassenverwalter einladen</Button>
                    <Button
                      size="compact-sm"
                      variant="subtle"
                      onClick={async () => {
                        await setActive.mutateAsync({ data: { wohngruppeId: g.id! } });
                        await reset();
                        navigate('/');
                      }}
                    >
                      Öffnen
                    </Button>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
            {list.data.data.length === 0 && (
              <Table.Tr><Table.Td colSpan={4}><Text c="dimmed" ta="center">Noch keine Wohngruppe angelegt.</Text></Table.Td></Table.Tr>
            )}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>

      <ActionModal opened={inviteFor !== null} onClose={() => setInviteFor(null)} title="Kassenverwalter einladen">
        {inviteFor && <InviteDialog group={inviteFor} onClose={() => setInviteFor(null)} />}
      </ActionModal>
    </Stack>
  );
}
