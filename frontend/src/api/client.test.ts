import { describe, expect, it } from 'vitest';
import { mockFetch } from '../test/fetchMock';
import { ApiError, api } from './client';

describe('api client', () => {
  it('sends the CSRF cookie value back in the X-XSRF-TOKEN header', async () => {
    document.cookie = 'XSRF-TOKEN=abc123; path=/';
    const fetchMock = mockFetch({ 'POST /api/v1/auth/logout': () => ({ status: 204 }) });

    await api.post('/auth/logout');

    const init = fetchMock.mock.calls[0]?.[1];
    expect((init?.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('abc123');
    expect(init?.credentials).toBe('same-origin');
  });

  it('turns problem details into ApiError with code and field errors', async () => {
    mockFetch({
      'GET /api/v1/venues': () => ({
        status: 400,
        body: { code: 'validation', errors: [{ field: 'name', message: 'must not be blank' }] },
      }),
    });

    const error = await api.get('/venues').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('validation');
    expect((error as ApiError).fieldErrors[0]?.field).toBe('name');
  });
});
