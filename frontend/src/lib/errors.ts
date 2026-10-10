import { notifications } from '@mantine/notifications';
import { ApiError } from '../api/mutator';

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return 'Etwas ist schiefgelaufen.';
}

export function notifyError(e: unknown, title = 'Fehler') {
  notifications.show({ color: 'red', title, message: errorMessage(e) });
}

export function notifySuccess(message: string) {
  notifications.show({ color: 'green', message });
}
