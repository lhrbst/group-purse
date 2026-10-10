import { Button, Card, Group, Loader, Stack, Text } from '@mantine/core';
import { Link } from 'react-router-dom';
import { useCategoryList } from '../api/generated/category-controller/category-controller';
import type { BuchungDto } from '../api/model';
import { categoryLabel, describePositions } from '../lib/categories';
import { formatDate } from '../lib/format';
import { Amount } from './Amount';
import { ReceiptThumbs } from './ReceiptThumbs';
import { StatusBadge } from './StatusBadge';

interface Props {
  items: BuchungDto[];
  empty: string;
  /** Link zum neuen Fotografieren bei abgelehnten Belegen der eigenen Person */
  showCorrect?: boolean;
  showThumbs?: boolean;
}

const AMOUNT_OPEN = ['VERARBEITUNG', 'MANUELL'];

export function ExpenseList({ items, empty, showCorrect = false, showThumbs = false }: Props) {
  const categories = useCategoryList();
  const all = categories.data?.status === 200 ? categories.data.data : [];
  if (items.length === 0) {
    return <Text c="dimmed">{empty}</Text>;
  }
  return (
    <Stack gap="xs">
      {items.map((b) => {
        const open = AMOUNT_OPEN.includes(b.status ?? '');
        return (
          <Card key={b.id} withBorder padding="sm">
            <Group justify="space-between" wrap="nowrap" align="flex-start">
              <div>
                <Text fw={600}>{b.haendler || b.zweck || `Beleg Nr. ${b.nummer}`}</Text>
                <Text size="sm" c="dimmed">
                  {formatDate(open ? b.erfasstAm : b.datum)} · Nr. {b.nummer}
                  {b.zweck && b.haendler ? ` · ${b.zweck}` : ''}
                </Text>
                {!open && <Text size="sm">{describePositions(b.positionen, (id) => categoryLabel(all, id))}</Text>}
              </div>
              <Stack gap={4} align="flex-end">
                {open ? <Text size="sm" c="dimmed">Betrag offen</Text> : <Amount value={b.betrag} fw={600} />}
                <StatusBadge status={b.status} />
              </Stack>
            </Group>
            {b.status === 'VERARBEITUNG' && (
              <Group gap="xs" mt={6}>
                <Loader size="xs" />
                <Text size="sm" c="dimmed">Der Beleg wird gelesen. Du musst nichts weiter tun.</Text>
              </Group>
            )}
            {b.status === 'MANUELL' && (
              <Text size="sm" c="dimmed" mt={6}>
                Der Kassenverwalter trägt die Daten dieses Belegs ein.
              </Text>
            )}
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
                Beleg neu fotografieren
              </Button>
            )}
          </Card>
        );
      })}
    </Stack>
  );
}
