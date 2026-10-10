import { expenseCapture } from '../api/generated/expense-controller/expense-controller';
import { flush, type FlushResult } from './uploadQueue';

/** Sendet alle lokal wartenden Belegfotos an den Server. */
export function uploadQueuedReceipts(): Promise<FlushResult> {
  return flush(async (photos) => {
    await expenseCapture({ photos });
  });
}
