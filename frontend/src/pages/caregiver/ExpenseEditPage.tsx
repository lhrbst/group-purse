import { useState } from 'react';
import { Alert, Button, Stack, Title } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { useExpenseGet, useExpenseReplacePhotos } from '../../api/generated/expense-controller/expense-controller';
import { LoadError, Loading } from '../../components/Loading';
import { PhotoPicker } from '../../components/PhotoPicker';
import { notifyError, notifySuccess } from '../../lib/errors';

/** Nach einer Ablehnung (Foto unbrauchbar): neue Fotos aufnehmen, der Beleg wird erneut gelesen. */
export function ExpenseEditPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const q = useExpenseGet(id);
  const replace = useExpenseReplacePhotos();
  const [files, setFiles] = useState<File[]>([]);

  if (q.isLoading) return <Loading />;
  if (q.isError || q.data?.status !== 200) return <LoadError error={q.error} />;
  const b = q.data.data;

  if (b.status !== 'ABGELEHNT') {
    return <Alert color="blue">Dieser Beleg kann nicht mehr neu fotografiert werden (Status: {b.status}).</Alert>;
  }

  return (
    <Stack maw={560}>
      <Title order={2}>Beleg neu fotografieren</Title>
      <Alert color="red" title="Der Kassenverwalter hat den Beleg abgelehnt">
        {b.ablehnungsKommentar}
      </Alert>
      <PhotoPicker files={files} onChange={setFiles} disabled={replace.isPending} />
      <Button
        size="xl"
        h={64}
        disabled={files.length === 0}
        loading={replace.isPending}
        onClick={async () => {
          try {
            await replace.mutateAsync({ id, data: { photos: files } });
            await qc.invalidateQueries();
            notifySuccess('Neue Fotos gesendet. Der Beleg wird erneut verarbeitet.');
            navigate('/');
          } catch (e) {
            notifyError(e);
          }
        }}
      >
        Neue Fotos absenden
      </Button>
    </Stack>
  );
}
