import { Badge } from '@mantine/core';
import { STATUS_COLOR, STATUS_LABEL } from '../lib/labels';

export function StatusBadge({ status }: { status?: string }) {
  if (!status) return null;
  return (
    <Badge color={STATUS_COLOR[status] ?? 'gray'} variant="light">
      {STATUS_LABEL[status] ?? status}
    </Badge>
  );
}
