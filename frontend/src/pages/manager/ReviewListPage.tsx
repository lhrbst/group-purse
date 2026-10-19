import { useState } from 'react';
import { Badge, Button, Card, Group, Loader, Select, Stack, Tabs, Text, Title } from '@mantine/core';
import { DateField } from '../../components/DateField';
import { useQueryClient } from '@tanstack/react-query';
import {
  useExpenseApprove,
  useExpensePending,
  useExpenseReject,
  useExpenseSearch,
  useExpenseUpdateDetails,
} from '../../api/generated/expense-controller/expense-controller';
import { useLedgerStorno } from '../../api/generated/ledger-controller/ledger-controller';
import type { BuchungDto } from '../../api/model';
import { ActionModal } from '../../components/ActionModal';
import { Amount } from '../../components/Amount';
import { ExpenseForm, newPosition } from '../../components/ExpenseForm';
import { LoadError, Loading } from '../../components/Loading';
import { ReasonModal } from '../../components/ReasonModal';
import { ReceiptThumbs } from '../../components/ReceiptThumbs';
import { StatusBadge } from '../../components/StatusBadge';
import { useCategoryNames } from '../../components/useCategoryNames';
import { describePositions } from '../../lib/categories';
import { notifyError, notifySuccess } from '../../lib/errors';
import { formatDate } from '../../lib/format';
import { ART_LABEL, STATUS_LABEL } from '../../lib/labels';

type Dialog = { kind: 'reject' | 'storno'; item: BuchungDto } | null;

interface CardProps {
  b: BuchungDto;
  categories: Map<string, string>;
  onApprove?: (b: BuchungDto) => void;
  onEdit?: (b: BuchungDto) => void;
  onDialog: (kind: 'reject' | 'storno', b: BuchungDto) => void;
}

function ExpenseCard({ b, categories, onApprove, onEdit, onDialog }: CardProps) {
  const open = b.status === 'VERARBEITUNG' || b.status === 'MANUELL';
  const editable = open || b.status === 'EINGEREICHT';
  const canStorno = b.status !== 'STORNIERT' && b.art !== 'STORNO';
  return (
    <Card withBorder padding="sm">
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <div>
          <Text fw={600}>{b.haendler || b.zweck || ART_LABEL[b.art ?? ''] || 'Ausgabe'}</Text>
          <Text size="sm" c="dimmed">
            {formatDate(open ? b.erfasstAm : b.datum)} · Nr. {b.nummer} · {b.erfasstVonName}
          </Text>
          {b.zweck && b.haendler && <Text size="sm">{b.zweck}</Text>}
          {!open && <Text size="sm">{describePositions(b.positionen, (id) => categories.get(id ?? '') ?? 'Kategorie')}</Text>}
        </div>
        <Stack gap={4} align="flex-end">
          {open ? <Text size="sm" c="dimmed">Betrag offen</Text> : <Amount value={b.betrag} fw={700} />}
          <StatusBadge status={b.status} />
        </Stack>
      </Group>
      {b.status === 'VERARBEITUNG' && (
        <Group gap="xs" mt={6}>
          <Loader size="xs" />
          <Text size="sm" c="dimmed">Die KI liest den Beleg. Das Ergebnis erscheint hier automatisch.</Text>
        </Group>
      )}
      {b.status === 'MANUELL' && (
        <Text size="sm" c="orange" mt={6}>
          Bitte die Daten selbst eintragen.{b.verarbeitungFehler ? ` (${b.verarbeitungFehler})` : ''}
        </Text>
      )}
      {b.ohneBeleg && (
        <Badge color="orange" variant="light" mt="xs">
          Ohne Beleg: {b.begruendungOhneBeleg}
        </Badge>
      )}
      {b.status === 'ABGELEHNT' && b.ablehnungsKommentar && (
        <Text size="sm" c="red" mt={4}>
          Abgelehnt: {b.ablehnungsKommentar}
        </Text>
      )}
      {b.id && <ReceiptThumbs buchungId={b.id} count={b.belegAnzahl ?? 0} />}
      <Group mt="xs" gap="xs">
        {onEdit && editable && (
          <Button size="compact-md" variant={b.status === 'EINGEREICHT' ? 'light' : 'filled'} onClick={() => onEdit(b)}>
            {open ? 'Daten erfassen' : 'Bearbeiten'}
          </Button>
        )}
        {onApprove && b.status === 'EINGEREICHT' && (
          <Button size="compact-md" color="green" onClick={() => onApprove(b)}>
            Prüfen und bestätigen
          </Button>
        )}
        {onEdit && editable && (
          <Button size="compact-md" color="red" variant="light" onClick={() => onDialog('reject', b)}>
            Foto unbrauchbar
          </Button>
        )}
        {canStorno && (
          <Button size="compact-md" variant="subtle" color="gray" onClick={() => onDialog('storno', b)}>
            Stornieren
          </Button>
        )}
      </Group>
    </Card>
  );
}

export function ReviewListPage() {
  const qc = useQueryClient();
  const categories = useCategoryNames();
  const pending = useExpensePending({
    query: { refetchInterval: (q) => (q.state.data?.status === 200 && q.state.data.data.some((b) => b.status === 'VERARBEITUNG') ? 8000 : false) },
  });
  const approve = useExpenseApprove();
  const reject = useExpenseReject();
  const storno = useLedgerStorno();
  const update = useExpenseUpdateDetails();
  const [dialog, setDialog] = useState<Dialog>(null);
  const [editing, setEditing] = useState<BuchungDto | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [von, setVon] = useState<string | null>(null);
  const [bis, setBis] = useState<string | null>(null);
  const all = useExpenseSearch({
    ...(status ? { status: status as never } : {}),
    ...(von ? { von } : {}),
    ...(bis ? { bis } : {}),
  });

  async function onApprove(b: BuchungDto) {
    try {
      await approve.mutateAsync({ id: b.id! });
      await qc.invalidateQueries();
      notifySuccess('Ausgabe bestätigt.');
    } catch (e) {
      notifyError(e);
    }
  }

  async function confirm(reason: string) {
    if (!dialog) return;
    try {
      if (dialog.kind === 'reject') {
        await reject.mutateAsync({ id: dialog.item.id!, data: { kommentar: reason } });
        notifySuccess('Beleg abgelehnt. Das Teammitglied kann neue Fotos hochladen.');
      } else {
        await storno.mutateAsync({ id: dialog.item.id!, data: { begruendung: reason } });
        notifySuccess('Ausgabe storniert.');
      }
      await qc.invalidateQueries();
    } catch (e) {
      notifyError(e);
      throw e;
    }
  }

  const open = (pending.data?.status === 200 ? pending.data.data : []).filter((b) => b.status !== 'EINGEREICHT');
  const toReview = (pending.data?.status === 200 ? pending.data.data : []).filter((b) => b.status === 'EINGEREICHT');
  const dialogProps = { categories, onDialog: (kind: 'reject' | 'storno', item: BuchungDto) => setDialog({ kind, item }) };

  return (
    <Stack maw={820}>
      <Title order={2}>Prüfung</Title>
      <Tabs defaultValue="pending" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="pending">Offen{pending.data?.status === 200 ? ` (${pending.data.data.length})` : ''}</Tabs.Tab>
          <Tabs.Tab value="all">Alle Ausgaben</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="pending" pt="md">
          {pending.isLoading ? (
            <Loading />
          ) : pending.isError || pending.data?.status !== 200 ? (
            <LoadError error={pending.error} />
          ) : pending.data.data.length === 0 ? (
            <Text c="dimmed">Alles erledigt. Es gibt keine offenen Belege.</Text>
          ) : (
            <Stack gap="md">
              {open.length > 0 && (
                <Stack gap="xs">
                  <Title order={5}>Wird gelesen oder braucht deine Eingabe ({open.length})</Title>
                  {open.map((b) => (
                    <ExpenseCard key={b.id} b={b} onEdit={setEditing} {...dialogProps} />
                  ))}
                </Stack>
              )}
              {toReview.length > 0 && (
                <Stack gap="xs">
                  <Title order={5}>Zu prüfen ({toReview.length})</Title>
                  {toReview.map((b) => (
                    <ExpenseCard key={b.id} b={b} onApprove={onApprove} onEdit={setEditing} {...dialogProps} />
                  ))}
                </Stack>
              )}
            </Stack>
          )}
        </Tabs.Panel>

        <Tabs.Panel value="all" pt="md">
          <Group mb="sm" align="flex-end">
            <Select
              label="Status"
              clearable
              placeholder="Alle"
              data={Object.entries(STATUS_LABEL).map(([value, label]) => ({ value, label }))}
              value={status}
              onChange={setStatus}
              w={180}
            />
            <DateField label="Von" compact clearable value={von} onChange={setVon} w={150} />
            <DateField label="Bis" compact clearable value={bis} onChange={setBis} w={150} />
          </Group>
          {all.isLoading ? (
            <Loading />
          ) : all.isError || all.data?.status !== 200 ? (
            <LoadError error={all.error} />
          ) : all.data.data.length === 0 ? (
            <Text c="dimmed">Keine Ausgaben gefunden.</Text>
          ) : (
            <Stack gap="xs">
              {all.data.data.map((b) => (
                <ExpenseCard key={b.id} b={b} {...dialogProps} />
              ))}
            </Stack>
          )}
        </Tabs.Panel>
      </Tabs>

      <ReasonModal
        opened={dialog !== null}
        onClose={() => setDialog(null)}
        title={dialog?.kind === 'reject' ? 'Foto unbrauchbar' : 'Ausgabe stornieren'}
        label={dialog?.kind === 'reject' ? 'Hinweis an das Teammitglied (z. B. „Bitte neu fotografieren, das Foto ist unscharf“)' : 'Begründung (wird im Kassenbuch festgehalten)'}
        confirmLabel={dialog?.kind === 'reject' ? 'Zurückgeben' : 'Stornieren'}
        color="red"
        onConfirm={confirm}
      />

      <ActionModal opened={editing !== null} onClose={() => setEditing(null)} title={editing?.status === 'EINGEREICHT' ? 'Beleg bearbeiten' : 'Beleg erfassen'}>
        {editing && (
          <ExpenseForm
            allowPhotos={false}
            existingId={editing.id}
            existingPhotos={editing.belegAnzahl ?? 0}
            initial={{
              datum: editing.datum,
              positionen: (editing.positionen ?? []).map((p) => newPosition(p.kategorieId ?? '', (p.betrag ?? 0).toFixed(2).replace('.', ','))),
              haendler: editing.haendler ?? '',
              zweck: editing.zweck ?? '',
              begruendungOhneBeleg: editing.begruendungOhneBeleg ?? '',
            }}
            submitLabel="Speichern"
            submitting={update.isPending}
            hint={editing.status === 'EINGEREICHT' ? 'Die Aufteilung stammt von der KI. Bitte prüfen und bei Bedarf ändern.' : 'Bitte Betrag, Datum und Kategorien vom Beleg abtippen.'}
            onSubmit={async (input) => {
              await update.mutateAsync({ id: editing.id!, data: input });
              await qc.invalidateQueries();
              notifySuccess('Beleg gespeichert. Er kann jetzt geprüft werden.');
              setEditing(null);
            }}
          />
        )}
      </ActionModal>
    </Stack>
  );
}
