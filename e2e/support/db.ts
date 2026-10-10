import { execFileSync } from 'node:child_process';

const CONTAINER = 'gk-e2e-db';

function count(sql: string): number {
  const out = execFileSync('docker', ['exec', CONTAINER, 'psql', '-U', 'grouppurse', '-d', 'grouppurse', '-tAc', sql], { encoding: 'utf8' });
  return Number(out.trim());
}

/**
 * Der Hintergrundjob des Backends läuft über Tests hinweg weiter. Damit Belege eines früheren Tests nicht in die KI-Zähler des nächsten
 * hineinlaufen, wartet jeder Test, bis keine Belege mehr in Verarbeitung sind.
 */
export async function waitForBackendIdle(timeoutMs = 90_000) {
  const start = Date.now();
  while (count("select count(*) from buchung where status = 'VERARBEITUNG'") > 0) {
    if (Date.now() - start > timeoutMs) throw new Error('Das Backend verarbeitet noch Belege früherer Tests.');
    await new Promise((r) => setTimeout(r, 300));
  }
}
