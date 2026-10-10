import { useState } from 'react';
import { Button, Card, Group, Select, Stack, Text, Title } from '@mantine/core';

export function ExportPage() {
  const thisYear = new Date().getFullYear();
  const [year, setYear] = useState(String(thisYear));
  const years = Array.from({ length: 8 }, (_, i) => String(thisYear - i));
  const link = (file: string) => `/api/export/${file}?jahr=${year}`;

  return (
    <Stack maw={640}>
      <Title order={2}>Export für die Kassenprüfung</Title>
      <Select label="Jahr" data={years} value={year} onChange={(v) => v && setYear(v)} allowDeselect={false} w={160} />
      <Card withBorder>
        <Text fw={600}>Jahresabschluss (PDF)</Text>
        <Text size="sm" c="dimmed" mb="xs">Kontostände, Einnahmen, Ausgaben nach Kategorie und das vollständige Kassenbuch.</Text>
        <Button component="a" href={link('annual-report.pdf')} download>PDF herunterladen</Button>
      </Card>
      <Card withBorder>
        <Text fw={600}>Alle Buchungen (CSV)</Text>
        <Text size="sm" c="dimmed" mb="xs">Für Excel: Semikolon-getrennt, deutsche Zahlen.</Text>
        <Button component="a" href={link('bookings.csv')} download variant="light">CSV herunterladen</Button>
      </Card>
      <Card withBorder>
        <Text fw={600}>Belegfotos (ZIP)</Text>
        <Text size="sm" c="dimmed" mb="xs">Alle Belege des Jahres, benannt nach Datum und Buchungsnummer. Die Datei kann groß sein.</Text>
        <Button component="a" href={link('receipts.zip')} download variant="light">ZIP herunterladen</Button>
      </Card>
      <Group>
        <Text size="xs" c="dimmed">Belege und Buchungen sollten in der Regel 10 Jahre aufbewahrt werden.</Text>
      </Group>
    </Stack>
  );
}
