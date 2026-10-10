const env = process.env;

export const PORTS = {
  db: Number(env.E2E_DB_PORT ?? 55432),
  backend: Number(env.E2E_BACKEND_PORT ?? 18080),
  llm: Number(env.E2E_LLM_PORT ?? 19090),
  web: Number(env.E2E_WEB_PORT ?? 14173),
};

export const URLS = {
  web: `http://localhost:${PORTS.web}`,
  backend: `http://localhost:${PORTS.backend}`,
  llm: `http://localhost:${PORTS.llm}`,
};

/** Admin, den der Teststack direkt in der Datenbank anlegt (wie es das Betriebshandbuch für den ersten Admin beschreibt). */
export const ADMIN = { name: 'E2E Admin', email: 'e2e-admin@example.org', password: 'e2e-admin-passwort-1' };
