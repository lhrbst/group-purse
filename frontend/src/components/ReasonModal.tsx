import { useState } from 'react';
import { Button, Group, Stack, Textarea } from '@mantine/core';
import { ActionModal } from './ActionModal';

/** Kleiner Dialog, der eine Pflichtbegründung abfragt (Ablehnen, Stornieren, ...). */
export function ReasonModal({
  opened,
  onClose,
  title,
  label,
  confirmLabel,
  color,
  onConfirm,
}: {
  opened: boolean;
  onClose: () => void;
  title: string;
  label: string;
  confirmLabel: string;
  color?: string;
  onConfirm: (reason: string) => Promise<void>;
}) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function confirm() {
    if (reason.trim() === '') {
      setError('Bitte eine Begründung eingeben');
      return;
    }
    setBusy(true);
    try {
      await onConfirm(reason.trim());
      setReason('');
      setError(null);
      onClose();
    } finally {
      setBusy(false);
    }
  }

  return (
    <ActionModal opened={opened} onClose={onClose} title={title}>
      <Stack>
        <Textarea label={label} minRows={3} autosize value={reason} error={error} onChange={(e) => setReason(e.currentTarget.value)} data-autofocus />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Abbrechen
          </Button>
          <Button color={color} loading={busy} onClick={confirm}>
            {confirmLabel}
          </Button>
        </Group>
      </Stack>
    </ActionModal>
  );
}
