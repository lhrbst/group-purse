import { URLS } from './ports';
import type { LlmCall, Mode } from './fake-llm';

export type { LlmCall, Mode };

async function control(body: unknown) {
  const res = await fetch(`${URLS.llm}/__control`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  if (!res.ok) throw new Error(`Fake-KI antwortet mit ${res.status}`);
}

/** Steuerung der Fake-KI aus den Tests heraus. */
export const llm = {
  reset: () => control({ reset: true }),
  mode: (mode: Mode, slowMs?: number) => control({ mode, ...(slowMs ? { slowMs } : {}) }),
  receipt: (receipt: unknown) => control({ receipt }),
  async calls(): Promise<LlmCall[]> {
    const res = await fetch(`${URLS.llm}/__calls`);
    return (await res.json()) as LlmCall[];
  },
};
