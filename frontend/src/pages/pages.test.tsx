import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { SessionProvider } from '../session/SessionContext';
import { mockFetch } from '../test/fetchMock';
import { LoginPage } from './LoginPage';
import { PinPage } from './PinPage';
import { RegisterPage } from './RegisterPage';

function renderPage(page: React.ReactNode) {
  return render(
    <MemoryRouter>
      <SessionProvider>{page}</SessionProvider>
    </MemoryRouter>,
  );
}

const unauthenticated = () => ({ status: 401, body: { code: 'unauthenticated' } });

describe('LoginPage', () => {
  it('shows the Italian message for wrong credentials', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    mockFetch({
      'GET /api/v1/auth/me': unauthenticated,
      'POST /api/v1/auth/login': () => ({ status: 401, body: { code: 'auth.invalid_credentials' } }),
    });
    renderPage(<LoginPage />);

    await userEvent.type(screen.getByLabelText('Email'), 'owner@standpos.test');
    await userEvent.type(screen.getByLabelText('Password'), 'sbagliata');
    await userEvent.click(screen.getByRole('button', { name: 'Accedi' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Email o password errate.');
  });
});

describe('PinPage', () => {
  it('lets a cashier pick their name and type the PIN on the keypad', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    let sentPin: unknown;
    mockFetch({
      'GET /api/v1/auth/me': unauthenticated,
      'GET /api/v1/devices/current': () => ({
        body: { enrolled: true, id: 'd1', venueId: 'v1', kind: 'REGISTER', name: 'Cassa 1' },
      }),
      'GET /api/v1/register/cashiers': () => ({ body: [{ userId: 'u1', displayName: 'Anna' }] }),
      'POST /api/v1/auth/pin': (init) => {
        sentPin = JSON.parse(String(init?.body));
        return { status: 423, body: { code: 'auth.pin_locked' } };
      },
    });
    renderPage(<PinPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Anna' }));
    for (const digit of ['1', '3', '5', '7']) {
      await userEvent.click(screen.getByRole('button', { name: digit }));
    }
    await userEvent.click(screen.getByRole('button', { name: 'Entra' }));

    expect(sentPin).toEqual({ userId: 'u1', pin: '1357' });
    expect(await screen.findByRole('alert')).toHaveTextContent('PIN bloccato');
  });

  it('asks to enrol when the browser is not a register', async () => {
    mockFetch({
      'GET /api/v1/auth/me': unauthenticated,
      'GET /api/v1/devices/current': () => ({
        body: { enrolled: false, id: null, venueId: null, kind: null, name: null },
      }),
    });
    renderPage(<PinPage />);

    expect(await screen.findByText('Questo dispositivo non è registrato come cassa.')).toBeInTheDocument();
  });
});

describe('RegisterPage', () => {
  it('shows the product grid by category with prices in euro', async () => {
    mockFetch({
      'GET /api/v1/auth/me': () => ({
        body: { kind: 'CASHIER', user: { id: 'u1', displayName: 'Anna', email: null }, memberships: [] },
      }),
      'GET /api/v1/register/catalogue': () => ({
        body: {
          venueId: 'v1',
          venueName: 'Bar oratorio',
          categories: [
            {
              id: 'c1',
              name: 'Bibite',
              colour: '#1E88E5',
              products: [
                { id: 'p1', name: 'Acqua', priceCents: 100, soldOut: false },
                { id: 'p2', name: 'Chinotto', priceCents: 250, soldOut: true },
              ],
            },
            { id: 'c2', name: 'Cucina', colour: '#E53935', products: [] },
          ],
        },
      }),
    });
    renderPage(<RegisterPage />);

    expect(await screen.findByRole('button', { name: /Acqua/ })).toHaveTextContent(/1,00\s€/);
    expect(screen.getByRole('button', { name: /Chinotto/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Bibite' })).toHaveAttribute('aria-pressed', 'true');
    expect(await screen.findByText('Cassiere: Anna')).toBeInTheDocument();
  });
});
