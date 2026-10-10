import { useState } from 'react';
import { Badge, Button, Card, Group, Select, Stack, Tabs, Text, Title } from '@mantine/core';
import { DateInput } from '@mantine/dates';
import { useQueryClient } from '@tanstack/react-query';
import { useExpenseApprove, useExpensePending, useExpenseReject, useExpenseSearch } from '../../api/generated/expense-controller/expense-controller';
import { useLedgerStorno } from '../../api/generated/ledger-controller/ledger-controller';
import type { BuchungDto } from '../../api/model';
import { Amount } from '../../components/Amount';
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

function ExpenseCard({ b, categories, onApprove, onDialog }: {
  b: BuchungDto;
  categories: Map<string, string>;
  onApprove?: (b: BuchungDto) => void;
  onDialog: (kind: 'reject' | 'storno', b: BuchungDto) => void;
}) {
  const canStorno = b.status !== 'STORNIERT' && b.art !== 'STORNO';
  return (
    <Card withBorder padding="sm">
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <div>
          <Text fw={600}>{b.haendler || b.zweck || ART_LABEL[b.art ?? ''] || 'Ausgabe'}</Text>
          <Text size="sm" c="dimmed">
            {formatDate(b.datum)} · Nr. {b.nummer} · {b.erfasstVonName}
            {b.bewohnerKuerzel ? ` · Bewohner ${b.bewohnerKuerzel}` : ''}
          </Text>
          {b.zweck && b.haendler && <Text size="sm">{b.zweck}</Text>}
          <Text size="sm">{describePositions(b.positionen, (id) => categories.get(id ?? '') ?? 'Kategorie')}</Text>
        </div>
        <Stack gap={4} align="flex-end">
          <Amount value={b.betrag} fw={700} />
          <StatusBadge status={b.status} />
        </Stack>
      </Group>
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
        {onApprove && b.status === 'EINGEREICHT' && (
          <>
            <Button size="compact-md" color="green" onClick={() => onApprove(b)}>
              Prüfen und bestätigen
            </Button>
            <Button size="compact-md" color="red" variant="light" onClick={() => onDialog('reject', b)}>
              Ablehnen
            </Button>
          </>
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
  const pending = useExpensePending();
  const approve = useExpenseApprove();
  const reject = useExpenseReject();
  const storno = useLedgerStorno();
  const [dialog, setDialog] = useState<Dialog>(null);
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
        notifySuccess('Ausgabe abgelehnt und zur Korrektur zurückgegeben.');
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

  return (
    <Stack maw={820}>
      <Title order={2}>Prüfung</Title>
      <Tabs defaultValue="pending" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="pending">Zu prüfen{pending.data?.status === 200 ? ` (${pending.data.data.length})` : ''}</Tabs.Tab>
          <Tabs.Tab value="all">Alle Ausgaben</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="pending" pt="md">
          {pending.isLoading ? (
            <Loading />
          ) : pending.isError || pending.data?.status !== 200 ? (
            <LoadError error={pending.error} />
          ) : pending.data.data.length === 0 ? (
            <Text c="dimmed">Alles geprüft. Es gibt keine eingereichten Ausgaben.</Text>
          ) : (
            <Stack gap="xs">
              {pending.data.data.map((b) => (
                <ExpenseCard key={b.id} b={b} categories={categories} onApprove={onApprove} onDialog={(kind, item) => setDialog({ kind, item })} />
              ))}
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
              w={160}
            />
            <DateInput label="Von" valueFormat="DD.MM.YYYY" clearable value={von} onChange={setVon} w={150} />
            <DateInput label="Bis" valueFormat="DD.MM.YYYY" clearable value={bis} onChange={setBis} w={150} />
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
                <ExpenseCard key={b.id} b={b} categories={categories} onDialog={(kind, item) => setDialog({ kind, item })} />
              ))}
            </Stack>
          )}
        </Tabs.Panel>
      </Tabs>

      <ReasonModal
        opened={dialog !== null}
        onClose={() => setDialog(null)}
        title={dialog?.kind === 'reject' ? 'Ausgabe ablehnen' : 'Ausgabe stornieren'}
        label={dialog?.kind === 'reject' ? 'Kommentar an die Pflegekraft' : 'Begründung (wird im Kassenbuch festgehalten)'}
        confirmLabel={dialog?.kind === 'reject' ? 'Ablehnen' : 'Stornieren'}
        color="red"
        onConfirm={confirm}
      />
    </Stack>
  );
}
