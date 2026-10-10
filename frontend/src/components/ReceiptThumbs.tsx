import { Group, Image, Modal, Text } from '@mantine/core';
import { useState } from 'react';

/** Zeigt die Belegfotos einer Buchung; Auslieferung nur über die API mit Rechteprüfung. */
export function ReceiptThumbs({ buchungId, count }: { buchungId: string; count: number }) {
  const [open, setOpen] = useState<number | null>(null);
  if (count === 0) return null;
  return (
    <>
      <Group gap="xs">
        {Array.from({ length: count }, (_, i) => (
          <Image
            key={i}
            src={`/api/receipts/${buchungId}/${i}`}
            alt={`Beleg ${i + 1}`}
            w={72}
            h={72}
            fit="cover"
            radius="sm"
            style={{ cursor: 'pointer' }}
            onClick={() => setOpen(i)}
          />
        ))}
      </Group>
      <Modal opened={open !== null} onClose={() => setOpen(null)} size="xl" title={open !== null ? `Beleg ${open + 1} von ${count}` : ''}>
        {open !== null && <Image src={`/api/receipts/${buchungId}/${open}`} alt={`Beleg ${open + 1}`} fit="contain" mah="80vh" />}
        {open !== null && count > 1 && (
          <Group justify="center" mt="sm">
            <Text size="sm" c="dimmed">
              {Array.from({ length: count }, (_, i) => (
                <span key={i} style={{ margin: '0 6px', cursor: 'pointer', fontWeight: i === open ? 700 : 400 }} onClick={() => setOpen(i)}>
                  {i + 1}
                </span>
              ))}
            </Text>
          </Group>
        )}
      </Modal>
    </>
  );
}
