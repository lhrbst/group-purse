import { useState } from 'react';
import { Alert, Anchor, Button, Stack, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { Link, useNavigate } from 'react-router-dom';
import { PhotoPicker } from '../../components/PhotoPicker';
import { RECEIPT_QUEUED_EVENT } from '../../components/PendingUploads';
import { notifyError } from '../../lib/errors';
import { uploadQueuedReceipts } from '../../lib/receiptUpload';
import { enqueue } from '../../lib/uploadQueue';
import { expenseCapture } from '../../api/generated/expense-controller/expense-controller';

export function ExpenseCapturePage() {
  const navigate = useNavigate();
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);

  async function send() {
    setBusy(true);
    try {
      // Erst lokal sichern, dann senden: So geht das Foto auch bei Abbruch oder fehlendem Netz nicht verloren.
      let queued = true;
      try {
        await enqueue(files);
      } catch {
        queued = false;
      }
      if (queued) {
        const result = await uploadQueuedReceipts();
        window.dispatchEvent(new Event(RECEIPT_QUEUED_EVENT));
        if (result.rejected.length > 0) {
          notifyError(new Error(result.rejected[0].message), 'Beleg abgelehnt');
          return;
        }
        notifications.show({
          color: result.remaining === 0 ? 'green' : 'orange',
          message: result.remaining === 0 ? 'Beleg gesendet. Er wird jetzt verarbeitet.' : 'Kein Netz: Der Beleg ist auf dem Gerät gespeichert und wird automatisch gesendet.',
        });
      } else {
        // Browser ohne lokalen Speicher (z. B. privates Fenster): direkt senden
        await expenseCapture({ photos: files });
        notifications.show({ color: 'green', message: 'Beleg gesendet. Er wird jetzt verarbeitet.' });
      }
      navigate('/');
    } catch (e) {
      notifyError(e, 'Senden fehlgeschlagen');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Stack maw={560}>
      <Title order={2}>Beleg erfassen</Title>
      <Alert color="blue" variant="light">
        Fotografiere den Kassenzettel und sende ihn ab. Mehr musst du nicht tun: Der Beleg wird im Hintergrund gelesen und der Kassenverwalter prüft ihn.
        Bei einem langen Bon gerne mehrere Fotos.
      </Alert>
      <PhotoPicker files={files} onChange={setFiles} disabled={busy} />
      <Button size="xl" h={64} disabled={files.length === 0} loading={busy} onClick={send}>
        Beleg absenden
      </Button>
      <Text size="sm" c="dimmed">
        Beleg verloren?{' '}
        <Anchor component={Link} to="/beleg/ohne-foto" size="sm">
          Ohne Foto erfassen
        </Anchor>
      </Text>
    </Stack>
  );
}
