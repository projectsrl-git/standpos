import { vi } from 'vitest';

type Handler = (init: RequestInit | undefined) => { status?: number; body?: unknown };

/** Stubs fetch with handlers keyed by "METHOD /api/v1/path"; unknown calls fail the test with a 500. */
export function mockFetch(handlers: Record<string, Handler>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    const key = `${init?.method ?? 'GET'} ${url}`;
    const handler = handlers[key];
    if (!handler) {
      return new Response(JSON.stringify({ code: 'test.unmocked', detail: key }), { status: 500 });
    }
    const { status = 200, body } = handler(init);
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}
