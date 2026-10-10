import { Stack, Title } from '@mantine/core';
import { useExpenseMine } from '../../api/generated/expense-controller/expense-controller';
import { ExpenseList } from '../../components/ExpenseList';
import { LoadError, Loading } from '../../components/Loading';

export function MyExpensesPage() {
  const q = useExpenseMine(
    { days: 365 },
    { query: { refetchInterval: (query) => (query.state.data?.status === 200 && query.state.data.data.some((b) => b.status === 'VERARBEITUNG') ? 8000 : false) } },
  );
  if (q.isLoading) return <Loading />;
  if (q.isError || q.data?.status !== 200) return <LoadError error={q.error} />;
  return (
    <Stack maw={640}>
      <Title order={2}>Meine Ausgaben</Title>
      <ExpenseList items={q.data.data} showCorrect showThumbs empty="Du hast noch keine Ausgaben erfasst." />
    </Stack>
  );
}
