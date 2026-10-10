/** Fehler der API; message ist bereits deutsch und für Anwender gedacht. */
export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function csrfToken(): string | undefined {
  const entry = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='));
  return entry ? decodeURIComponent(entry.substring('XSRF-TOKEN='.length)) : undefined;
}

async function ensureCsrf(): Promise<void> {
  if (!csrfToken()) {
    await fetch('/api/me', { credentials: 'same-origin' }).catch(() => undefined);
  }
}

/** Spring erwartet das Teil "data" als JSON mit eigenem Content-Type; orval sendet es als einfachen Text. */
function jsonPartsToBlobs(source: FormData): FormData {
  const out = new FormData();
  source.forEach((value, key) => {
    if (key === 'data' && typeof value === 'string') {
      out.append(key, new Blob([value], { type: 'application/json' }));
    } else {
      out.append(key, value);
    }
  });
  return out;
}

export const customFetch = async <T>(url: string, options: RequestInit = {}): Promise<T> => {
  const method = (options.method ?? 'GET').toUpperCase();
  const headers = new Headers(options.headers);
  headers.set('Accept', 'application/json');
  let body = options.body;
  if (typeof FormData !== 'undefined' && body instanceof FormData) {
    body = jsonPartsToBlobs(body);
  }
  if (method !== 'GET' && method !== 'HEAD') {
    await ensureCsrf();
    const token = csrfToken();
    if (token) headers.set('X-XSRF-TOKEN', token);
  }
  const res = await fetch(url, { ...options, method, headers, body, credentials: 'same-origin' });
  const type = res.headers.get('content-type') ?? '';
  let data: unknown;
  if (res.status !== 204) {
    data = type.includes('json') ? await res.json().catch(() => undefined) : await res.text();
  }
  if (!res.ok) {
    const problem = (typeof data === 'object' && data !== null ? data : {}) as { detail?: string; message?: string };
    throw new ApiError(res.status, problem.detail ?? problem.message ?? fallbackMessage(res.status));
  }
  return { data, status: res.status, headers: res.headers } as T;
};

function fallbackMessage(status: number): string {
  switch (status) {
    case 401:
      return 'Bitte melde dich an.';
    case 403:
      return 'Dafür fehlt dir die Berechtigung.';
    case 404:
      return 'Nicht gefunden.';
    case 429:
      return 'Zu viele Versuche. Bitte warte einen Moment.';
    default:
      return status >= 500 ? 'Der Server hat ein Problem. Bitte versuche es später erneut.' : 'Die Anfrage war nicht erfolgreich.';
  }
}
