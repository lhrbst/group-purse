import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';
import { uploadQueuedReceipts } from '../lib/receiptUpload';
import { pendingCount } from '../lib/uploadQueue';

export const RECEIPT_QUEUED_EVENT = 'receipt-queued';

/** Sendet lokal wartende Belege nach (beim Start, bei Netz-Rückkehr, regelmäßig) und zeigt an, wenn welche warten. */
export function PendingUploads() {
  const qc = useQueryClient();
  const [waiting, setWaiting] = useState(0);
  const mounted = useRef(true);

  const sync = useCallback(async () => {
    try {
      const result = await uploadQueuedReceipts();
      if (!mounted.current) return;
      setWaiting(result.remaining);
      if (result.sent > 0) {
        await qc.invalidateQueries();
        notifications.show({ color: 'green', message: result.sent === 1 ? 'Beleg gesendet. Er wird jetzt verarbeitet.' : `${result.sent} Belege gesendet. Sie werden jetzt verarbeitet.` });
      }
      result.rejected.forEach((r) => notifications.show({ color: 'red', title: 'Beleg abgelehnt', message: r.message }));
    } catch {
      // IndexedDB nicht verfügbar o. Ä.: dann gibt es auch nichts Wartendes
      pendingCount().then((n) => mounted.current && setWaiting(n)).catch(() => undefined);
    }
  }, [qc]);

  useEffect(() => {
    mounted.current = true;
    sync();
    const timer = window.setInterval(sync, 20_000);
    window.addEventListener('online', sync);
    window.addEventListener(RECEIPT_QUEUED_EVENT, sync);
    return () => {
      mounted.current = false;
      window.clearInterval(timer);
      window.removeEventListener('online', sync);
      window.removeEventListener(RECEIPT_QUEUED_EVENT, sync);
    };
  }, [sync]);

  if (waiting === 0) return null;
  return (
    <Alert color="orange" mb="md" title="Belege warten auf den Versand">
      {waiting === 1 ? 'Ein Beleg ist auf diesem Gerät gespeichert' : `${waiting} Belege sind auf diesem Gerät gespeichert`} und werden automatisch gesendet,
      sobald eine Verbindung besteht. Bitte die App nicht deinstallieren.
    </Alert>
  );
}
