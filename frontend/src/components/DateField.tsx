import { Button, Group, Text } from '@mantine/core';
import { DateInput, type DateInputProps } from '@mantine/dates';
import { formatLongDate, isWithin, minDateIso, parseGermanDate, parseWhileTyping } from '../lib/dateInput';
import { todayIso } from '../lib/format';

export type DateFieldProps = Omit<DateInputProps, 'valueFormat' | 'dateParser' | 'defaultDate'> & {
  /** "Heute" und "Gestern" als Schnellwahl unter dem Feld (für Buchungsdaten). */
  quickPicks?: boolean;
  /** Schmale Variante für Filter: ohne Schnellwahl und ohne Wochentag. */
  compact?: boolean;
};

/**
 * Datumsfeld der App: tippen (11.10.2026, 11.10.26, 11.10., 111026, heute, gestern) oder im Kalender wählen.
 * Der Kalender öffnet im Monat des Werts, sonst im heutigen Monat; frühestens zehn Jahre zurück.
 */
export function DateField({ quickPicks = false, compact = false, minDate, ...props }: DateFieldProps) {
  const min = minDate ?? minDateIso();
  const max = typeof props.maxDate === 'string' ? props.maxDate : undefined;
  const value = typeof props.value === 'string' ? props.value : null;

  /** Kurzformen (11.10.26, 1110, 111026) gelten erst beim Verlassen des Feldes oder mit Enter, nicht beim Tippen. */
  const commit = (text: string) => {
    const parsed = parseGermanDate(text);
    if (parsed && parsed !== value && isWithin(parsed, typeof min === 'string' ? min : undefined, max)) {
      props.onChange?.(parsed);
    }
  };

  const input = (
    <DateInput
      valueFormat="DD.MM.YYYY"
      placeholder="TT.MM.JJJJ"
      autoComplete="off"
      defaultDate={todayIso()}
      {...props}
      minDate={min}
      dateParser={(text) => parseWhileTyping(text)}
      onBlur={(event) => {
        commit(event.currentTarget.value);
        props.onBlur?.(event);
      }}
      onKeyDown={(event) => {
        if (event.key === 'Enter') commit(event.currentTarget.value);
        props.onKeyDown?.(event);
      }}
    />
  );
  if (compact) {
    return input;
  }

  const weekday = !props.error ? formatLongDate(value) : '';
  const picks = quickPicks
    ? [
        { label: 'Heute', iso: parseGermanDate('heute')! },
        { label: 'Gestern', iso: parseGermanDate('gestern')! },
      ]
    : [];

  return (
    <div>
      {input}
      {(picks.length > 0 || weekday) && (
        <Group gap={6} mt={4} wrap="wrap" align="center">
          {picks.map((p) => (
            <Button
              key={p.label}
              size="compact-xs"
              variant={value === p.iso ? 'light' : 'default'}
              disabled={props.disabled || props.readOnly}
              onClick={() => props.onChange?.(p.iso)}
            >
              {p.label}
            </Button>
          ))}
          {weekday && <Text size="xs" c="dimmed">{weekday}</Text>}
        </Group>
      )}
    </div>
  );
}
