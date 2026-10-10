import { Stack, Text, Title } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useExpenseSubmit } from '../../api/generated/expense-controller/expense-controller';
import { ExpenseForm } from '../../components/ExpenseForm';
import { notifySuccess } from '../../lib/errors';

/** Ausnahmefall: Beleg ohne Foto (z. B. verloren). Hier gibt man die Daten selbst ein und begründet, warum es kein Foto gibt. */
export function ExpenseManualPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const submit = useExpenseSubmit();

  return (
    <Stack maw={560}>
      <Title order={2}>Beleg ohne Foto erfassen</Title>
      <Text c="dimmed" size="sm">
        Nur für den Ausnahmefall, dass es kein Foto gibt. Der Betrag wird sofort von der Barkasse abgezogen und der Beleg in der Prüfliste markiert.
      </Text>
      <ExpenseForm
        submitLabel="Absenden"
        submitting={submit.isPending}
        onSubmit={async (input, photos) => {
          await submit.mutateAsync({ data: { data: input, photos } });
          await qc.invalidateQueries();
          notifySuccess('Ausgabe eingereicht.');
          navigate('/');
        }}
      />
    </Stack>
  );
}
