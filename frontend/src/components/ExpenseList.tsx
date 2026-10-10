import { Button, Card, Group, Stack, Text } from '@mantine/core';
import { Link } from 'react-router-dom';
import type { BuchungDto } from '../api/model';
import { formatDate } from '../lib/format';
import { Amount } from './Amount';
import { ReceiptThumbs } from './ReceiptThumbs';
import { StatusBadge } from './StatusBadge';

interface Props {
  items: BuchungDto[];
  empty: string;
  /** Link zum Korrigieren bei abgelehnten Ausgaben der eigenen Person */
  showCorrect?: boolean;
  showThumbs?: boolean;
}

export function ExpenseList({ items, empty, showCorrect = false, showThumbs = false }: Props) {
  if (items.length === 0) {
    return <Text c="dimmed">{empty}</Text>;
  }
  return (
    <Stack gap="xs">
      {items.map((b) => (
        <Card key={b.id} withBorder padding="sm">
          <Group justify="space-between" wrap="nowrap" align="flex-start">
            <div>
              <Text fw={600}>{b.haendler || b.zweck || 'Ausgabe'}</Text>
              <Text size="sm" c="dimmed">
                {formatDate(b.datum)} · Nr. {b.nummer}
                {b.zweck && b.haendler ? ` · ${b.zweck}` : ''}
              </Text>
            </div>
            <Stack gap={4} align="flex-end">
              <Amount value={b.betrag} fw={600} />
              <StatusBadge status={b.status} />
            </Stack>
          </Group>
          {b.ohneBeleg && (
            <Text size="xs" c="orange" mt={4}>
              Ohne Beleg: {b.begruendungOhneBeleg}
            </Text>
          )}
          {b.status === 'ABGELEHNT' && b.ablehnungsKommentar && (
            <Text size="sm" c="red" mt={4}>
              Abgelehnt: {b.ablehnungsKommentar}
            </Text>
          )}
          {showThumbs && b.id && <ReceiptThumbs buchungId={b.id} count={b.belegAnzahl ?? 0} />}
          {showCorrect && b.status === 'ABGELEHNT' && (
            <Button component={Link} to={`/ausgaben/${b.id}/korrigieren`} size="compact-sm" mt="xs" variant="light" color="red">
              Korrigieren und neu einreichen
            </Button>
          )}
        </Card>
      ))}
    </Stack>
  );
}
