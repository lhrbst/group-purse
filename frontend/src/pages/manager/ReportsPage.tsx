import { useState } from 'react';
import { BarChart, LineChart } from '@mantine/charts';
import { Card, Group, Select, SegmentedControl, SimpleGrid, Stack, Text, TextInput, Title } from '@mantine/core';
import { DateInput } from '@mantine/dates';
import { useCategoryList } from '../../api/generated/category-controller/category-controller';
import { useMemberList } from '../../api/generated/member-controller/member-controller';
import { useReportBalanceHistory, useReportByCategory, useReportIncomeVsExpense } from '../../api/generated/report-controller/report-controller';
import { LoadError, Loading } from '../../components/Loading';
import { categoryLabel } from '../../lib/categories';
import { incomeExpenseData, pivotByMonth } from '../../lib/chartData';
import { currentMonth, formatDate, formatEuro, shiftMonth, todayIso } from '../../lib/format';

export function ReportsPage() {
  const [von, setVon] = useState<string | null>(`${shiftMonth(currentMonth(), -11)}-01`);
  const [bis, setBis] = useState<string | null>(todayIso());
  const [person, setPerson] = useState<string | null>(null);
  const [kategorie, setKategorie] = useState<string | null>(null);
  const [bewohner, setBewohner] = useState('');
  const [konto, setKonto] = useState('BARKASSE');
  const [ebene, setEbene] = useState('UNTER');

  const range = { ...(von ? { von } : {}), ...(bis ? { bis } : {}) };
  const byCategory = useReportByCategory({
    ...range,
    ...(person ? { person } : {}),
    ...(kategorie ? { kategorie } : {}),
    ...(bewohner.trim() ? { bewohner: bewohner.trim() } : {}),
    ebene: ebene as 'UNTER',
  });
  const income = useReportIncomeVsExpense(range);
  const history = useReportBalanceHistory({ ...range, konto: konto as 'BARKASSE' });
  const members = useMemberList();
  const categories = useCategoryList({ alle: true });
  const allCategories = categories.data?.status === 200 ? categories.data.data : [];

  const pivot = byCategory.data?.status === 200 ? pivotByMonth(byCategory.data.data) : null;

  return (
    <Stack>
      <Title order={2}>Auswertungen</Title>
      <SimpleGrid cols={{ base: 1, sm: 2, lg: 5 }}>
        <DateInput label="Von" valueFormat="DD.MM.YYYY" value={von} onChange={setVon} />
        <DateInput label="Bis" valueFormat="DD.MM.YYYY" value={bis} onChange={setBis} />
        <Select
          label="Pflegekraft"
          clearable
          placeholder="Alle"
          data={members.data?.status === 200 ? members.data.data.map((m) => ({ value: m.personId!, label: m.name ?? '' })) : []}
          value={person}
          onChange={setPerson}
        />
        <Select
          label="Kategorie"
          clearable
          placeholder="Alle"
          data={allCategories.map((c) => ({ value: c.id!, label: categoryLabel(allCategories, c.id) }))}
          value={kategorie}
          onChange={setKategorie}
        />
        <TextInput label="Bewohner (Kürzel)" value={bewohner} onChange={(e) => setBewohner(e.currentTarget.value)} />
      </SimpleGrid>

      <Card withBorder>
        <Group justify="space-between" mb="sm">
          <Title order={4}>Ausgaben nach Kategorie und Monat</Title>
          <SegmentedControl
            value={ebene}
            onChange={setEbene}
            data={[{ value: 'UNTER', label: 'Unterkategorien' }, { value: 'HAUPT', label: 'Hauptkategorien' }]}
          />
        </Group>
        {byCategory.isLoading ? (
          <Loading />
        ) : byCategory.isError ? (
          <LoadError error={byCategory.error} />
        ) : pivot && pivot.data.length > 0 ? (
          <BarChart h={320} data={pivot.data} dataKey="monat" type="stacked" series={pivot.series} valueFormatter={formatEuro} withLegend legendProps={{ verticalAlign: 'bottom' }} />
        ) : (
          <Text c="dimmed">Keine Ausgaben im gewählten Zeitraum.</Text>
        )}
      </Card>

      <Card withBorder>
        <Title order={4} mb="sm">Einnahmen gegen Ausgaben</Title>
        {income.isLoading ? (
          <Loading />
        ) : income.isError ? (
          <LoadError error={income.error} />
        ) : income.data?.status === 200 && income.data.data.length > 0 ? (
          <BarChart
            h={280}
            data={incomeExpenseData(income.data.data)}
            dataKey="monat"
            series={[{ name: 'Einnahmen', color: 'teal.6' }, { name: 'Ausgaben', color: 'red.6' }]}
            valueFormatter={formatEuro}
            withLegend
            legendProps={{ verticalAlign: 'bottom' }}
          />
        ) : (
          <Text c="dimmed">Keine Daten.</Text>
        )}
      </Card>

      <Card withBorder>
        <Group justify="space-between" mb="sm">
          <Title order={4}>Verlauf des Kassenstands</Title>
          <SegmentedControl
            value={konto}
            onChange={setKonto}
            data={[{ value: 'BARKASSE', label: 'Barkasse' }, { value: 'VEREINSKONTO', label: 'Vereinskonto' }]}
          />
        </Group>
        {history.isLoading ? (
          <Loading />
        ) : history.isError ? (
          <LoadError error={history.error} />
        ) : history.data?.status === 200 && history.data.data.length > 0 ? (
          <LineChart
            h={280}
            data={history.data.data.map((p) => ({ datum: formatDate(p.datum), Stand: p.stand ?? 0 }))}
            dataKey="datum"
            series={[{ name: 'Stand', color: 'blue.6' }]}
            curveType="stepAfter"
            valueFormatter={formatEuro}
          />
        ) : (
          <Text c="dimmed">Keine Daten.</Text>
        )}
      </Card>
    </Stack>
  );
}
