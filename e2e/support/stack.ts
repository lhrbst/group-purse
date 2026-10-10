import { ChildProcess, execFileSync, spawn } from 'node:child_process';
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import type { Server } from 'node:http';
import path from 'node:path';
import { startFakeLlm } from './fake-llm';
import { ADMIN, PORTS, URLS } from './ports';

const ROOT = path.resolve(__dirname, '..', '..');
export const RUN_DIR = path.join(ROOT, 'e2e', '.run');
const DB_CONTAINER = 'gk-e2e-db';
const isWin = process.platform === 'win32';

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}

function tail(file: string, lines = 40): string {
  try {
    return readFileSync(file, 'utf8').split('\n').slice(-lines).join('\n');
  } catch {
    return '(kein Log)';
  }
}

function docker(args: string[], allowFail = false): string {
  try {
    return execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (e) {
    if (allowFail) return '';
    throw new Error(`docker ${args.join(' ')} fehlgeschlagen. Läuft Docker?\n${(e as Error).message}`);
  }
}

async function waitFor(what: string, check: () => Promise<boolean>, timeoutMs: number, logFile?: string, child?: ChildProcess) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (child && child.exitCode !== null) {
      throw new Error(`${what}: Prozess ist beendet (Code ${child.exitCode}).\n${logFile ? tail(logFile) : ''}`);
    }
    try {
      if (await check()) return;
    } catch {
      // noch nicht bereit
    }
    await sleep(500);
  }
  throw new Error(`${what} wurde nicht rechtzeitig bereit.\n${logFile ? tail(logFile) : ''}`);
}

function kill(child: ChildProcess | undefined) {
  if (!child || child.pid === undefined || child.exitCode !== null) return;
  try {
    if (isWin) {
      execFileSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
    } else {
      process.kill(-child.pid, 'SIGTERM');
    }
  } catch {
    // war schon beendet
  }
}

function spawnLogged(cmd: string, args: string[], opts: { cwd: string; env: NodeJS.ProcessEnv; log: string }): ChildProcess {
  const fd = openSync(opts.log, 'w');
  const child = spawn(cmd, args, { cwd: opts.cwd, env: opts.env, stdio: ['ignore', fd, fd], detached: !isWin, windowsHide: true });
  closeSync(fd);
  return child;
}

function findJar(): string {
  const dir = path.join(ROOT, 'target');
  const jar = existsSync(dir) ? readdirSync(dir).find((f) => /^group-purse-.*\.jar$/.test(f) && !f.endsWith('.original')) : undefined;
  if (!jar) throw new Error('Backend-JAR fehlt. Bitte "npm run build" im Ordner e2e ausführen.');
  return path.join(dir, jar);
}

/** Startet PostgreSQL (Docker), Fake-KI, Backend und die Vorschau des gebauten Frontends. Gibt die Aufräumfunktion zurück. */
export async function startStack(): Promise<() => Promise<void>> {
  rmSync(RUN_DIR, { recursive: true, force: true });
  mkdirSync(path.join(RUN_DIR, 'receipts'), { recursive: true });
  const jar = findJar();
  const dist = path.join(ROOT, 'frontend', 'dist', 'index.html');
  if (!existsSync(dist)) throw new Error('Frontend-Build fehlt (frontend/dist). Bitte "npm run build" im Ordner e2e ausführen.');

  const children: ChildProcess[] = [];
  let llmServer: Server | undefined;
  const stop = async () => {
    children.forEach(kill);
    await new Promise<void>((r) => (llmServer ? llmServer.close(() => r()) : r()));
    docker(['rm', '-f', DB_CONTAINER], true);
  };

  try {
    // 1. Datenbank
    docker(['rm', '-f', DB_CONTAINER], true);
    docker([
      'run', '-d', '--name', DB_CONTAINER, '-p', `${PORTS.db}:5432`,
      '-e', 'POSTGRES_USER=grouppurse', '-e', 'POSTGRES_PASSWORD=e2e', '-e', 'POSTGRES_DB=grouppurse',
      '--tmpfs', '/var/lib/postgresql/data', 'postgres:17-alpine', '-c', 'fsync=off', '-c', 'max_connections=50',
    ]);
    await waitFor('PostgreSQL', async () => {
      docker(['exec', DB_CONTAINER, 'pg_isready', '-U', 'grouppurse', '-d', 'grouppurse']);
      return true;
    }, 60_000);

    // 2. Fake-KI
    llmServer = await startFakeLlm(PORTS.llm);

    // 3. Backend (Arbeitsverzeichnis ohne config/, damit lokale Entwicklungseinstellungen nicht hineinwirken)
    const backendLog = path.join(RUN_DIR, 'backend.log');
    const backend = spawnLogged('java', ['-jar', jar], {
      cwd: RUN_DIR,
      log: backendLog,
      env: {
        ...process.env,
        SERVER_PORT: String(PORTS.backend),
        DB_URL: `jdbc:postgresql://localhost:${PORTS.db}/grouppurse`,
        DB_USER: 'grouppurse',
        DB_PASSWORD: 'e2e',
        COOKIE_SECURE: 'false',
        APP_BASE_URL: URLS.web,
        STORAGE_DIR: path.join(RUN_DIR, 'receipts'),
        SPRING_AI_OPENAI_BASE_URL: URLS.llm,
        OPENAI_API_KEY: 'e2e-key',
        SPRING_AI_RETRY_MAX_ATTEMPTS: '1',
        RECEIPT_AI_ENABLED: 'true',
        RECEIPT_MODEL: 'fake-klein',
        RECEIPT_FALLBACK_MODEL: 'fake-gross',
        RECEIPT_TIMEOUT_SECONDS: '3',
        RECEIPT_RETRY_BACKOFF_SECONDS: '1',
        APP_RECEIPT_POLL_DELAY_MS: '500',
        JAVA_TOOL_OPTIONS: '-Xmx512m',
      },
    });
    children.push(backend);
    await waitFor('Backend', async () => {
      const r = await fetch(`${URLS.backend}/actuator/health`);
      return r.ok && (await r.text()).includes('"UP"');
    }, 150_000, backendLog, backend);

    // 4. Admin direkt in der Datenbank anlegen
    docker([
      'exec', DB_CONTAINER, 'psql', '-U', 'grouppurse', '-d', 'grouppurse', '-v', 'ON_ERROR_STOP=1', '-c',
      `create extension if not exists pgcrypto;
       insert into person (id, name, email, passwort_hash, ist_admin, datenschutz_zugestimmt_am)
       values (gen_random_uuid(), '${ADMIN.name}', '${ADMIN.email}', crypt('${ADMIN.password}', gen_salt('bf', 10)), true, now());`,
    ]);

    // 5. Frontend: Vorschau des Produktions-Builds (mit Service Worker), /api geht an das Backend
    const webLog = path.join(RUN_DIR, 'web.log');
    const vite = path.join(ROOT, 'frontend', 'node_modules', 'vite', 'bin', 'vite.js');
    const web = spawnLogged(process.execPath, [vite, 'preview', '--port', String(PORTS.web), '--strictPort', '--host', 'localhost'], {
      cwd: path.join(ROOT, 'frontend'),
      log: webLog,
      env: { ...process.env, VITE_BACKEND_URL: URLS.backend },
    });
    children.push(web);
    await waitFor('Frontend', async () => (await fetch(URLS.web)).ok, 30_000, webLog, web);
  } catch (e) {
    await stop();
    throw e;
  }
  return stop;
}
