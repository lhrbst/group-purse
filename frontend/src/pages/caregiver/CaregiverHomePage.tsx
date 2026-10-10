import { Alert, Button, Card, Group, Stack, Text, Title } from '@mantine/core';
import { Link } from 'react-router-dom';
import { useReportCaregiver } from '../../api/generated/report-controller/report-controller';
import { Amount } from '../../components/Amount';
import { ExpenseList } from '../../components/ExpenseList';
import { LoadError, Loading } from '../../components/Loading';

export function CaregiverHomePage() {
  const q = useReportCaregiver();
  if (q.isLoading) return <Loading />;
  if (q.isError || q.data?.status !== 200) return <LoadError error={q.error} />;
  const d = q.data.data;

  return (
    <Stack maw={640}>
      <Button component={Link} to="/beleg" size="xl" h={80} fz="xl">
        Beleg erfassen
      </Button>

      <Card withBorder>
        <Group justify="space-between">
          <Text c="dimmed">Aktueller Kassenstand</Text>
          <Title order={2}>
            <Amount value={d.barkasse} />
          </Title>
        </Group>
      </Card>

      {d.negativ && (
        <Alert color="red" title="Die Barkasse ist im Minus">
          Bitte keine weiteren größeren Einkäufe ohne Absprache mit dem Kassenverwalter.
        </Alert>
      )}
      {d.warnung && !d.negativ && (
        <Alert color="orange" title="Wenig Geld in der Barkasse">
          Bitte größere Einkäufe vorher mit dem Kassenverwalter absprechen.
        </Alert>
      )}

      <Group justify="space-between" mt="sm">
        <Title order={4}>Meine Ausgaben der letzten 30 Tage</Title>
        <Button component={Link} to="/meine-ausgaben" variant="subtle" size="compact-sm">
          Alle ansehen
        </Button>
      </Group>
      <ExpenseList items={d.eigeneAusgaben ?? []} showCorrect empty="Du hast in den letzten 30 Tagen noch nichts erfasst." />
    </Stack>
  );
}
