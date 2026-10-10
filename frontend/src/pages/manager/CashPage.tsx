import { useState } from 'react';
import { Alert, Button, Card, Group, SegmentedControl, Select, SimpleGrid, Stack, Table, Text, TextInput, Textarea, Title } from '@mantine/core';
import { DateInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import {
  useLedgerAnfangsbestand,
  useLedgerAuffuellung,
  useLedgerBalance,
  useLedgerBestand,
  useLedgerKorrektur,
  useLedgerList,
  useLedgerRueckgabe,
  useLedgerStorno,
} from '../../api/generated/ledger-controller/ledger-controller';
import { useExpenseKontoausgabe } from '../../api/generated/expense-controller/expense-controller';
import type { BuchungDto } from '../../api/model';
import { ActionModal } from '../../components/ActionModal';
import { Amount } from '../../components/Amount';
import { ExpenseForm } from '../../components/ExpenseForm';
import { LoadError, Loading } from '../../components/Loading';
import { ReasonModal } from '../../components/ReasonModal';
import { StatusBadge } from '../../components/StatusBadge';
import { useCategoryNames } from '../../components/useCategoryNames';
import { describePositions } from '../../lib/categories';
import { notifyError, notifySuccess } from '../../lib/errors';
import { formatDate, formatEuro, parseAmount, shiftMonth, todayIso, currentMonth } from '../../lib/format';
import { ART_LABEL, KONTO_LABEL } from '../../lib/labels';
import { zodValidate } from '../../lib/zodForm';

type ModalKind = 'bestand' | 'auffuellung' | 'rueckgabe' | 'korrektur' | 'kontoausgabe' | 'anfangsbestand' | null;

const amount = z.string().refine((v) => parseAmount(v) > 0, 'Bitte einen Betrag größer 0 eingeben').refine(
  (v) => Math.round(parseAmount(v) * 100) / 100 === parseAmount(v),
  'Höchstens zwei Nachkommastellen',
);

function useDone() {
  const qc = useQueryClient();
  return async (message: string) => {
    await qc.invalidateQueries();
    notifySuccess(message);
  };
}

function KontoSwitch({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <SegmentedControl
      fullWidth
      value={value}
      onChange={onChange}
      data={[
        { value: 'BARKASSE', label: 'Barkasse' },
        { value: 'VEREINSKONTO', label: 'Vereinskonto' },
      ]}
    />
  );
}

function BestandForm({ soll, onClose }: { soll: { BARKASSE: number; VEREINSKONTO: number }; onClose: () => void }) {
  const bestand = useLedgerBestand();
  const done = useDone();
  const [konto, setKonto] = useState('BARKASSE');
  const form = useForm({
    initialValues: { ist: '', begruendung: '' },
    validate: zodValidate(z.object({ ist: z.string().refine((v) => parseAmount(v) >= 0, 'Bitte den gezählten Betrag eingeben'), begruendung: z.string() })),
  });
  const current = soll[konto as 'BARKASSE' | 'VEREINSKONTO'];
  const ist = parseAmount(form.values.ist);
  const diff = Number.isFinite(ist) ? Math.round((ist - current) * 100) / 100 : null;

  return (
    <form
      onSubmit={form.onSubmit(async (v) => {
        if (diff !== null && diff !== 0 && v.begruendung.trim() === '' && current !== 0) {
          form.setFieldError('begruendung', 'Bei einer Differenz ist eine Begründung Pflicht');
          return;
        }
        try {
          const res = await bestand.mutateAsync({ data: { konto: konto as 'BARKASSE', istBestand: ist, begruendung: v.begruendung.trim() || undefined } });
          const r = res.status === 200 ? res.data : undefined;
          await done(r && r.differenz ? `Bestand erfasst. Differenz von ${formatEuro(r.differenz)} als Korrektur gebucht.` : 'Bestand erfasst. Keine Differenz.');
          onClose();
        } catch (e) {
          notifyError(e);
        }
      })}
    >
      <Stack>
        <KontoSwitch value={konto} onChange={setKonto} />
        <Group justify="space-between">
          <Text c="dimmed">Berechneter Stand (Soll)</Text>
          <Amount value={current} fw={600} />
        </Group>
        <TextInput label="Tatsächlicher Stand (Ist)" description={konto === 'BARKASSE' ? 'Bargeld zählen und eintragen' : 'Kontostand laut Bank'} inputMode="decimal" data-autofocus {...form.getInputProps('ist')} />
        {diff !== null && (
          <Alert color={diff === 0 ? 'green' : 'orange'} variant="light">
            Differenz: <b>{formatEuro(diff)}</b>
            {diff === 0 ? ' – alles stimmt.' : current === 0 ? ' – wird als Anfangsbestand gebucht.' : ' – wird als Korrektur gebucht.'}
          </Alert>
        )}
        {diff !== null && diff !== 0 && current !== 0 && <Textarea label="Begründung" minRows={2} {...form.getInputProps('begruendung')} />}
        <Button type="submit" loading={bestand.isPending}>
          Bestand erfassen
        </Button>
      </Stack>
    </form>
  );
}

function AuffuellungForm({ onClose }: { onClose: () => void }) {
  const m = useLedgerAuffuellung();
  const done = useDone();
  const form = useForm({
    initialValues: { betrag: '', datum: todayIso(), an: '' },
    validate: zodValidate(z.object({ betrag: amount, datum: z.string().min(1, 'Bitte Datum wählen'), an: z.string().trim().min(1, 'Bitte angeben, an wen übergeben wurde') })),
  });
  return (
    <form
      onSubmit={form.onSubmit(async (v) => {
        try {
          await m.mutateAsync({ data: { betrag: parseAmount(v.betrag), datum: v.datum, uebergebenAn: v.an.trim() } });
          await done('Barkasse aufgefüllt.');
          onClose();
        } catch (e) {
          notifyError(e);
        }
      })}
    >
      <Stack>
        <Text size="sm" c="dimmed">Bucht den Betrag vom Vereinskonto ab und in die Barkasse hinein.</Text>
        <TextInput label="Betrag (€)" inputMode="decimal" data-autofocus {...form.getInputProps('betrag')} />
        <DateInput label="Datum" valueFormat="DD.MM.YYYY" maxDate={todayIso()} {...form.getInputProps('datum')} />
        <TextInput label="Übergeben an" description="Teammitglied oder „Kasse direkt“" {...form.getInputProps('an')} />
        <Button type="submit" loading={m.isPending}>Auffüllung buchen</Button>
      </Stack>
    </form>
  );
}

function RueckgabeForm({ onClose }: { onClose: () => void }) {
  const m = useLedgerRueckgabe();
  const done = useDone();
  const form = useForm({
    initialValues: { betrag: '', datum: todayIso(), grund: '' },
    validate: zodValidate(z.object({ betrag: amount, datum: z.string().min(1, 'Bitte Datum wählen'), grund: z.string().trim().min(1, 'Bitte einen Grund angeben') })),
  });
  return (
    <form
      onSubmit={form.onSubmit(async (v) => {
        try {
          await m.mutateAsync({ data: { betrag: parseAmount(v.betrag), datum: v.datum, grund: v.grund.trim() } });
          await done('Rückgabe ans Konto gebucht.');
          onClose();
        } catch (e) {
          notifyError(e);
        }
      })}
    >
      <Stack>
        <Text size="sm" c="dimmed">Bargeld aus der Barkasse wird wieder auf das Vereinskonto eingezahlt.</Text>
        <TextInput label="Betrag (€)" inputMode="decimal" data-autofocus {...form.getInputProps('betrag')} />
        <DateInput label="Datum" valueFormat="DD.MM.YYYY" maxDate={todayIso()} {...form.getInputProps('datum')} />
        <TextInput label="Grund" {...form.getInputProps('grund')} />
        <Button type="submit" loading={m.isPending}>Rückgabe buchen</Button>
      </Stack>
    </form>
  );
}

function KorrekturForm({ onClose }: { onClose: () => void }) {
  const m = useLedgerKorrektur();
  const done = useDone();
  const [konto, setKonto] = useState('BARKASSE');
  const form = useForm({
    initialValues: { betrag: '', datum: todayIso(), begruendung: '' },
    validate: zodValidate(z.object({
      betrag: z.string().refine((v) => Number.isFinite(parseAmount(v)) && parseAmount(v) !== 0, 'Bitte einen Betrag ungleich 0 eingeben (Minus für Abzug)'),
      datum: z.string().min(1, 'Bitte Datum wählen'),
      begruendung: z.string().trim().min(1, 'Bitte eine Begründung angeben'),
    })),
  });
  return (
    <form
      onSubmit={form.onSubmit(async (v) => {
        try {
          await m.mutateAsync({ data: { konto: konto as 'BARKASSE', betrag: parseAmount(v.betrag), datum: v.datum, begruendung: v.begruendung.trim() } });
          await done('Korrektur gebucht.');
          onClose();
        } catch (e) {
          notifyError(e);
        }
      })}
    >
      <Stack>
        <KontoSwitch value={konto} onChange={setKonto} />
        <TextInput label="Betrag (€)" description="Plus erhöht den Stand, Minus verringert ihn" inputMode="decimal" data-autofocus {...form.getInputProps('betrag')} />
        <DateInput label="Datum" valueFormat="DD.MM.YYYY" maxDate={todayIso()} {...form.getInputProps('datum')} />
        <Textarea label="Begründung" minRows={2} {...form.getInputProps('begruendung')} />
        <Button type="submit" loading={m.isPending}>Korrektur buchen</Button>
      </Stack>
    </form>
  );
}

function AnfangsbestandForm({ onClose }: { onClose: () => void }) {
  const m = useLedgerAnfangsbestand();
  const done = useDone();
  const [konto, setKonto] = useState('BARKASSE');
  const form = useForm({
    initialValues: { betrag: '', datum: todayIso() },
    validate: zodValidate(z.object({ betrag: z.string().refine((v) => parseAmount(v) >= 0, 'Bitte einen Betrag eingeben'), datum: z.string().min(1, 'Bitte Datum wählen') })),
  });
  return (
    <form
      onSubmit={form.onSubmit(async (v) => {
        try {
          await m.mutateAsync({ data: { konto: konto as 'BARKASSE', betrag: parseAmount(v.betrag), datum: v.datum } });
          await done('Anfangsbestand gebucht.');
          onClose();
        } catch (e) {
          notifyError(e);
        }
      })}
    >
      <Stack>
        <Text size="sm" c="dimmed">Der Anfangsbestand wird einmal je Konto beim Start der App gebucht.</Text>
        <KontoSwitch value={konto} onChange={setKonto} />
        <TextInput label="Betrag (€)" inputMode="decimal" data-autofocus {...form.getInputProps('betrag')} />
        <DateInput label="Stichtag" valueFormat="DD.MM.YYYY" maxDate={todayIso()} {...form.getInputProps('datum')} />
        <Button type="submit" loading={m.isPending}>Anfangsbestand buchen</Button>
      </Stack>
    </form>
  );
}

function KontoausgabeForm({ onClose }: { onClose: () => void }) {
  const m = useExpenseKontoausgabe();
  const done = useDone();
  return (
    <ExpenseForm
      submitLabel="Kontoausgabe buchen"
      submitting={m.isPending}
      hint="Ausgabe, die direkt vom Vereinskonto bezahlt wurde (z. B. größere Anschaffung oder Dauerauftrag)."
      onSubmit={async (input, photos) => {
        await m.mutateAsync({ data: { data: input, photos } });
        await done('Kontoausgabe gebucht.');
        onClose();
      }}
    />
  );
}

const TITLES: Record<Exclude<ModalKind, null>, string> = {
  bestand: 'Bestand erfassen',
  auffuellung: 'Barkasse auffüllen',
  rueckgabe: 'Rückgabe ans Konto',
  korrektur: 'Korrektur buchen',
  kontoausgabe: 'Kontoausgabe erfassen',
  anfangsbestand: 'Anfangsbestand',
};

export function CashPage() {
  const balance = useLedgerBalance();
  const categories = useCategoryNames();
  const [modal, setModal] = useState<ModalKind>(null);
  const [konto, setKonto] = useState<string | null>(null);
  const [von, setVon] = useState<string | null>(`${shiftMonth(currentMonth(), -2)}-01`);
  const [bis, setBis] = useState<string | null>(null);
  const [stornoItem, setStornoItem] = useState<BuchungDto | null>(null);
  const storno = useLedgerStorno();
  const done = useDone();
  const list = useLedgerList({ ...(von ? { von } : {}), ...(bis ? { bis } : {}), ...(konto ? { konto: konto as 'BARKASSE' } : {}) });

  if (balance.isLoading) return <Loading />;
  if (balance.isError || balance.data?.status !== 200) return <LoadError error={balance.error} />;
  const b = balance.data.data;
  const close = () => setModal(null);

  return (
    <Stack>
      <Title order={2}>Kasse</Title>
      <SimpleGrid cols={{ base: 1, sm: 2 }}>
        <Card withBorder>
          <Text c="dimmed" size="sm">Barkasse</Text>
          <Title order={2}><Amount value={b.barkasse} /></Title>
          {b.negativ && <Text size="sm" c="red">Im Minus</Text>}
          {b.warnung && !b.negativ && <Text size="sm" c="orange">Unter dem Mindestbetrag von {formatEuro(b.mindestbetrag)}</Text>}
        </Card>
        <Card withBorder>
          <Text c="dimmed" size="sm">Vereinskonto (gebuchter Stand)</Text>
          <Title order={2}><Amount value={b.vereinskonto} /></Title>
        </Card>
      </SimpleGrid>

      <Group>
        <Button onClick={() => setModal('bestand')}>Bestand erfassen</Button>
        <Button variant="light" onClick={() => setModal('auffuellung')}>Barkasse auffüllen</Button>
        <Button variant="light" onClick={() => setModal('rueckgabe')}>Rückgabe ans Konto</Button>
        <Button variant="light" onClick={() => setModal('kontoausgabe')}>Kontoausgabe</Button>
        <Button variant="subtle" onClick={() => setModal('korrektur')}>Korrektur</Button>
        <Button variant="subtle" onClick={() => setModal('anfangsbestand')}>Anfangsbestand</Button>
      </Group>

      <Title order={3} mt="md">Kassenbuch</Title>
      <Group align="flex-end">
        <Select label="Konto" clearable placeholder="Beide" data={[{ value: 'BARKASSE', label: 'Barkasse' }, { value: 'VEREINSKONTO', label: 'Vereinskonto' }]} value={konto} onChange={setKonto} w={160} />
        <DateInput label="Von" valueFormat="DD.MM.YYYY" clearable value={von} onChange={setVon} w={150} />
        <DateInput label="Bis" valueFormat="DD.MM.YYYY" clearable value={bis} onChange={setBis} w={150} />
      </Group>
      {list.isLoading ? (
        <Loading />
      ) : list.isError || list.data?.status !== 200 ? (
        <LoadError error={list.error} />
      ) : (
        <Table.ScrollContainer minWidth={760}>
          <Table striped highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Nr.</Table.Th>
                <Table.Th>Datum</Table.Th>
                <Table.Th>Konto</Table.Th>
                <Table.Th>Art</Table.Th>
                <Table.Th ta="right">Betrag</Table.Th>
                <Table.Th>Beschreibung</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {list.data.data.map((r) => (
                <Table.Tr key={r.id} c={r.status === 'STORNIERT' ? 'dimmed' : undefined}>
                  <Table.Td>{r.nummer}</Table.Td>
                  <Table.Td>{formatDate(r.datum)}</Table.Td>
                  <Table.Td>{KONTO_LABEL[r.konto ?? '']}</Table.Td>
                  <Table.Td>{ART_LABEL[r.art ?? ''] ?? r.art}</Table.Td>
                  <Table.Td ta="right"><Amount value={r.betrag} /></Table.Td>
                  <Table.Td>
                    {[describePositions(r.positionen, (id) => categories.get(id ?? '') ?? 'Kategorie') || null, r.haendler, r.zweck].filter(Boolean).join(' · ')}
                  </Table.Td>
                  <Table.Td><StatusBadge status={r.status} /></Table.Td>
                  <Table.Td>
                    {r.status !== 'STORNIERT' && r.art !== 'STORNO' && (
                      <Button size="compact-xs" variant="subtle" color="gray" onClick={() => setStornoItem(r)}>
                        Stornieren
                      </Button>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
              {list.data.data.length === 0 && (
                <Table.Tr>
                  <Table.Td colSpan={8}><Text c="dimmed" ta="center">Keine Buchungen im gewählten Zeitraum.</Text></Table.Td>
                </Table.Tr>
              )}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}

      <ActionModal opened={modal !== null} onClose={close} title={modal ? TITLES[modal] : ''}>
        {modal === 'bestand' && <BestandForm soll={{ BARKASSE: b.barkasse ?? 0, VEREINSKONTO: b.vereinskonto ?? 0 }} onClose={close} />}
        {modal === 'auffuellung' && <AuffuellungForm onClose={close} />}
        {modal === 'rueckgabe' && <RueckgabeForm onClose={close} />}
        {modal === 'korrektur' && <KorrekturForm onClose={close} />}
        {modal === 'anfangsbestand' && <AnfangsbestandForm onClose={close} />}
        {modal === 'kontoausgabe' && <KontoausgabeForm onClose={close} />}
      </ActionModal>

      <ReasonModal
        opened={stornoItem !== null}
        onClose={() => setStornoItem(null)}
        title={`Buchung Nr. ${stornoItem?.nummer} stornieren`}
        label="Begründung (wird im Kassenbuch festgehalten)"
        confirmLabel="Stornieren"
        color="red"
        onConfirm={async (reason) => {
          try {
            await storno.mutateAsync({ id: stornoItem!.id!, data: { begruendung: reason } });
            await done('Buchung storniert. Zusammengehörige Gegenbuchungen wurden mit storniert.');
          } catch (e) {
            notifyError(e);
            throw e;
          }
        }}
      />
    </Stack>
  );
}
