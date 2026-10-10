import { Alert, Button, Card, Group, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { Link } from 'react-router-dom';
import { useReportManager } from '../../api/generated/report-controller/report-controller';
import { Amount } from '../../components/Amount';
import { LoadError, Loading } from '../../components/Loading';
import { formatDateTime } from '../../lib/format';

function Tile({ title, children, to, action }: { title: string; children: React.ReactNode; to?: string; action?: string }) {
  return (
    <Card withBorder>
      <Text c="dimmed" size="sm">
        {title}
      </Text>
      <Title order={2} my={4}>
        {children}
      </Title>
      {to && (
        <Button component={Link} to={to} variant="subtle" size="compact-sm" px={0}>
          {action}
        </Button>
      )}
    </Card>
  );
}

export function ManagerDashboardPage() {
  const q = useReportManager();
  if (q.isLoading) return <Loading />;
  if (q.isError || q.data?.status !== 200) return <LoadError error={q.error} />;
  const d = q.data.data;
  const open = d.offeneBeitraege;

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Übersicht</Title>
        <Button component={Link} to="/beleg" variant="light">
          Beleg erfassen
        </Button>
      </Group>

      {d.negativ && <Alert color="red" title="Die Barkasse ist im Minus">Der Kassenstand liegt unter 0 €. Bitte auffüllen oder den Bestand prüfen.</Alert>}
      {d.warnung && !d.negativ && <Alert color="orange" title="Wenig Geld in der Barkasse">Der Kassenstand liegt unter dem eingestellten Mindestbetrag.</Alert>}

      {(d.ohneBetrag ?? 0) > 0 && (
        <Alert color="blue" title="Belege ohne Betrag">
          {d.ohneBetrag === 1 ? 'Ein Beleg wird' : `${d.ohneBetrag} Belege werden`} gerade gelesen oder warten auf deine Eingabe. Der Kassenstand enthält
          {d.ohneBetrag === 1 ? ' diese Ausgabe' : ' diese Ausgaben'} noch nicht. <Button component={Link} to="/pruefung" variant="subtle" size="compact-sm">Zur Prüfung</Button>
        </Alert>
      )}

      <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
        <Tile title="Barkasse" to="/kasse" action="Zur Kasse">
          <Amount value={d.barkasse} />
        </Tile>
        <Tile title="Vereinskonto (gebuchter Stand)" to="/kasse" action="Kassenbuch">
          <Amount value={d.vereinskonto} />
        </Tile>
        <Tile title="Zu prüfen" to="/pruefung" action="Zur Prüfliste">
          {d.eingereichtAnzahl}
        </Tile>
        <Tile title="Elternbeiträge" to="/beitraege" action="Zum Raster">
          <Text component="span" inherit c={open?.ueberfaellig ? 'red' : undefined}>
            {open?.ueberfaellig ?? 0} überfällig
          </Text>
          <Text size="sm" c="dimmed" fw={400}>
            {open?.offen ?? 0} offen · {open?.familien ?? 0} Familien
          </Text>
        </Tile>
      </SimpleGrid>

      <Text size="sm" c="dimmed">
        Letzte Bestandserfassung: {d.letzteBestandserfassung ? formatDateTime(d.letzteBestandserfassung) : 'noch nie'}
      </Text>
    </Stack>
  );
}
