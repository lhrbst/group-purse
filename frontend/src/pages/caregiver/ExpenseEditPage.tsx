import { Alert, Stack, Title } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { useExpenseGet, useExpenseResubmit } from '../../api/generated/expense-controller/expense-controller';
import { ExpenseForm } from '../../components/ExpenseForm';
import { LoadError, Loading } from '../../components/Loading';
import { notifySuccess } from '../../lib/errors';

export function ExpenseEditPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const q = useExpenseGet(id);
  const resubmit = useExpenseResubmit();

  if (q.isLoading) return <Loading />;
  if (q.isError || q.data?.status !== 200) return <LoadError error={q.error} />;
  const b = q.data.data;

  if (b.status !== 'ABGELEHNT') {
    return <Alert color="blue">Diese Ausgabe kann nicht mehr geändert werden (Status: {b.status}).</Alert>;
  }

  return (
    <Stack maw={560}>
      <Title order={2}>Ausgabe korrigieren</Title>
      {b.ablehnungsKommentar && (
        <Alert color="red" title="Abgelehnt">
          {b.ablehnungsKommentar}
        </Alert>
      )}
      <ExpenseForm
        mode="edit"
        existingId={b.id}
        existingPhotos={b.belegAnzahl ?? 0}
        initial={{
          betrag: String(Math.abs(b.betrag ?? 0)).replace('.', ','),
          datum: b.datum,
          kategorieId: b.kategorieId ?? '',
          haendler: b.haendler ?? '',
          zweck: b.zweck ?? '',
          bewohnerKuerzel: b.bewohnerKuerzel ?? '',
          begruendungOhneBeleg: b.begruendungOhneBeleg ?? '',
        }}
        submitLabel="Neu einreichen"
        submitting={resubmit.isPending}
        onSubmit={async (input, photos) => {
          await resubmit.mutateAsync({ id, data: { data: input, photos } });
          await qc.invalidateQueries();
          notifySuccess('Ausgabe erneut eingereicht.');
          navigate('/');
        }}
      />
    </Stack>
  );
}
