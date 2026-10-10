import { Text, type TextProps } from '@mantine/core';
import { formatEuro } from '../lib/format';

/** Betrag, negative Werte rot. */
export function Amount({ value, signed = false, ...rest }: { value: number | undefined | null; signed?: boolean } & TextProps) {
  const v = value ?? 0;
  return (
    <Text component="span" c={v < 0 ? 'red' : undefined} style={{ whiteSpace: 'nowrap' }} {...rest}>
      {signed && v > 0 ? '+' : ''}
      {formatEuro(v)}
    </Text>
  );
}
