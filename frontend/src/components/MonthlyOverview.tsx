import { useMemo, useState } from 'react';
import { Badge, Button, Card, Group, Select, Stack, Table, Text, Title } from '@mantine/core';
import { useReportMonthlyReport } from '../api/generated/report-controller/report-controller';
import { LoadError, Loading } from './Loading';
import { currentMonth, formatDate, formatEuro, formatMonthLong, recentMonths, shiftMonth } from '../lib/format';

const MONTH_COUNT = 60;

const formatPercent = (value: number | undefined) => `${(value ?? 0).toLocaleString('de-DE', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} %`;
const kontoLabel = (konto: string | undefined) => (konto === 'VEREINSKONTO' ? 'Vereinskonto' : 'Barkasse');

export function MonthlyOverview() {
  const [monat, setMonat] = useState(currentMonth());
  const options = useMemo(() => recentMonths(MONTH_COUNT).map((m) => ({ value: m, label: formatMonthLong(m) })), []);
  const oldest = options[options.length - 1].value;
  const report = useReportMonthlyReport({ monat });
  const data = report.data?.status === 200 ? report.data.data : null;

  return (
    <Card withBorder>
      <Group justify="space-between" align="flex-end" mb="sm" wrap="wrap">
        <Title order={4}>Monatsübersicht</Title>
        <Group align="flex-end" gap="xs">
          <Button variant="default" aria-label="Vormonat" disabled={monat <= oldest} onClick={() => setMonat(shiftMonth(monat, -1))}>‹</Button>
          <Select aria-label="Monat" data={options} value={monat} onChange={(v) => v && setMonat(v)} allowDeselect={false} w={190} />
          <Button variant="default" aria-label="Nächster Monat" disabled={monat >= currentMonth()} onClick={() => setMonat(shiftMonth(monat, 1))}>›</Button>
          <Button component="a" href={`/api/reports/monthly.pdf?monat=${monat}`} download>PDF herunterladen</Button>
        </Group>
      </Group>

      {report.isLoading ? (
        <Loading />
      ) : report.isError ? (
        <LoadError error={report.error} />
      ) : data ? (
        <Stack gap="md">
          <Text>{data.zusammenfassung}</Text>
          {data.ungeprueftAnzahl ? (
            <Text size="sm" c="dimmed">
              Noch nicht geprüft: {data.ungeprueftAnzahl === 1 ? '1 Beleg' : `${data.ungeprueftAnzahl} Belege`} ({formatEuro(data.ungeprueftSumme)})
            </Text>
          ) : null}
          {(data.belegAnzahl ?? 0) > 0 && (
            <>
              <Title order={5}>Ausgaben nach Kategorie</Title>
              <Table.ScrollContainer minWidth={420}>
                <Table>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Hauptkategorie</Table.Th>
                      <Table.Th>Unterkategorie</Table.Th>
                      <Table.Th ta="right">Betrag</Table.Th>
                      <Table.Th ta="right">Anteil</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {data.kategorien?.map((k) => (
                      <Table.Tr key={`${k.hauptkategorie}/${k.unterkategorie ?? ''}`}>
                        <Table.Td>{k.hauptkategorie}</Table.Td>
                        <Table.Td>{k.unterkategorie ?? ''}</Table.Td>
                        <Table.Td ta="right">{formatEuro(k.betrag)}</Table.Td>
                        <Table.Td ta="right">{formatPercent(k.anteilProzent)}</Table.Td>
                      </Table.Tr>
                    ))}
                    <Table.Tr fw={600}>
                      <Table.Td>Summe</Table.Td>
                      <Table.Td />
                      <Table.Td ta="right">{formatEuro(data.summe)}</Table.Td>
                      <Table.Td ta="right">{formatPercent(100)}</Table.Td>
                    </Table.Tr>
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>

              <Title order={5}>Alle Ausgaben</Title>
              <Table.ScrollContainer minWidth={640}>
                <Table>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Datum</Table.Th>
                      <Table.Th>Nr.</Table.Th>
                      <Table.Th>Konto</Table.Th>
                      <Table.Th>Händler</Table.Th>
                      <Table.Th>Kategorie</Table.Th>
                      <Table.Th>Erfasst von</Table.Th>
                      <Table.Th ta="right">Betrag</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {data.belege?.map((b) => {
                      const several = (b.positionen?.length ?? 0) > 1;
                      return [
                        <Table.Tr key={b.nummer}>
                          <Table.Td>{formatDate(b.datum)}</Table.Td>
                          <Table.Td>{b.nummer}</Table.Td>
                          <Table.Td>{kontoLabel(b.konto)}</Table.Td>
                          <Table.Td>
                            {b.haendler ?? '–'} {b.zuPruefen && <Badge size="xs" variant="light" color="yellow">zu prüfen</Badge>}
                          </Table.Td>
                          <Table.Td>{several ? 'mehrere' : b.positionen?.[0]?.kategorie ?? '–'}</Table.Td>
                          <Table.Td>{b.erfasstVon}</Table.Td>
                          <Table.Td ta="right">{formatEuro(b.betrag)}</Table.Td>
                        </Table.Tr>,
                        ...(several
                          ? (b.positionen ?? []).map((p, i) => (
                              <Table.Tr key={`${b.nummer}-${i}`} c="dimmed" fz="xs">
                                <Table.Td colSpan={4} />
                                <Table.Td colSpan={2}>· {p.kategorie}</Table.Td>
                                <Table.Td ta="right">{formatEuro(p.betrag)}</Table.Td>
                              </Table.Tr>
                            ))
                          : []),
                      ];
                    })}
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>
            </>
          )}
        </Stack>
      ) : null}
    </Card>
  );
}
