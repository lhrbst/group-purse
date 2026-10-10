import { Stack, Title } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useExpenseSubmit } from '../../api/generated/expense-controller/expense-controller';
import { ExpenseForm } from '../../components/ExpenseForm';
import { notifySuccess } from '../../lib/errors';

export function ExpenseCapturePage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const submit = useExpenseSubmit();

  return (
    <Stack maw={560}>
      <Title order={2}>Beleg erfassen</Title>
      <ExpenseForm
        mode="create"
        submitLabel="Absenden"
        submitting={submit.isPending}
        hint="Foto machen, Vorschlag prüfen, absenden. Der Betrag wird sofort vom Kassenstand abgezogen."
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
