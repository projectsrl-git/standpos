/** Thin fetch wrapper for the StandPOS REST API: same-origin cookies, CSRF header, RFC 9457 errors. */

export interface FieldError {
  field: string;
  message: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldError[];

  constructor(status: number, code: string, fieldErrors: FieldError[] = []) {
    super(code);
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }
}

const BASE = '/api/v1';
const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`;
  const found = document.cookie.split('; ').find((part) => part.startsWith(prefix));
  return found ? decodeURIComponent(found.slice(prefix.length)) : undefined;
}

/** The SPA sends the raw cookie value back in the header (cookie-to-header pattern). */
async function csrfToken(): Promise<string | undefined> {
  const existing = readCookie(CSRF_COOKIE);
  if (existing) {
    return existing;
  }
  await fetch(`${BASE}/auth/csrf`, { credentials: 'same-origin' });
  return readCookie(CSRF_COOKIE);
}

export async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (method !== 'GET') {
    const token = await csrfToken();
    if (token) {
      headers[CSRF_HEADER] = token;
    }
  }
  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'network');
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const data: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    const problem = (data ?? {}) as { code?: string; errors?: FieldError[] };
    throw new ApiError(response.status, problem.code ?? `http.${response.status}`, problem.errors ?? []);
  }
  return data as T;
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  delete: <T = void>(path: string) => request<T>('DELETE', path),
};
