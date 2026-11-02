import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import { SessionProvider } from '../session/SessionContext';
import { mockFetch } from '../test/fetchMock';
import { LoginPage } from './LoginPage';
import { SignupPage } from './SignupPage';

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
          <Route path="/manager/setup" element={<p>Wizard aperto</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  );
}

const unauthenticated = () => ({ status: 401, body: { code: 'unauthenticated' } });
const enabled = () => ({ body: { enabled: true, declarationsVersion: '2026-11-02' } });
const owner = {
  kind: 'USER',
  tenant: { id: 't1', name: 'Oratorio Nuovo' },
  user: { id: 'o1', displayName: 'Anna Rossi', email: 'anna@example.org' },
  memberships: [{ id: 'm1', venueId: null, role: 'OWNER' }],
  device: null,
  platformAdmin: false,
};

async function fillForm(password = 'una-password-lunga', confirm = password) {
  await userEvent.type(
    await screen.findByLabelText("Nome dell'associazione, dell'oratorio o del comitato"),
    'Oratorio Nuovo',
  );
  await userEvent.type(screen.getByLabelText('Il tuo nome e cognome'), 'Anna Rossi');
  await userEvent.type(screen.getByLabelText('La tua email (per accedere)'), 'anna@example.org');
  await userEvent.type(screen.getByLabelText('Password (almeno 10 caratteri)'), password);
  await userEvent.type(screen.getByLabelText('Ripeti la password'), confirm);
  await userEvent.click(screen.getByRole('checkbox', { name: /ente senza scopo di lucro/ }));
  await userEvent.click(screen.getByRole('checkbox', { name: /scontrini di cortesia, non fiscali/ }));
}

describe('sign-up', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
  });

  it('is offered on the login page when the server allows it', async () => {
    mockFetch({ 'GET /api/v1/auth/me': unauthenticated, 'GET /api/v1/auth/signup': enabled });
    renderAt('/login');

    expect(await screen.findByRole('link', { name: 'Crea la tua associazione' })).toHaveAttribute(
      'href',
      '/signup',
    );
  });

  it('is hidden and unavailable when the server has it off', async () => {
    mockFetch({
      'GET /api/v1/auth/me': unauthenticated,
      'GET /api/v1/auth/signup': () => ({ status: 404, body: { code: 'not_found' } }),
    });
    renderAt('/signup');

    expect(await screen.findByText(/La registrazione non è attiva su questo server/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: "Crea l'associazione" })).not.toBeInTheDocument();
  });

  it('sends the declarations and opens the setup wizard for the new owner', async () => {
    let sent: unknown;
    let loggedIn = false;
    mockFetch({
      'GET /api/v1/auth/me': () => (loggedIn ? { body: owner } : unauthenticated()),
      'GET /api/v1/auth/signup': enabled,
      'POST /api/v1/auth/signup': (init) => {
        sent = JSON.parse(String(init?.body));
        loggedIn = true;
        return { status: 202, body: { status: 'accepted' } };
      },
    });
    renderAt('/signup');

    await fillForm();
    await userEvent.click(screen.getByRole('button', { name: "Crea l'associazione" }));

    expect(await screen.findByText('Wizard aperto')).toBeInTheDocument();
    expect(sent).toEqual({
      organisationName: 'Oratorio Nuovo',
      ownerName: 'Anna Rossi',
      email: 'anna@example.org',
      password: 'una-password-lunga',
      nonProfit: true,
      nonFiscalAcknowledged: true,
    });
  });

  it('answers generically when no session was opened', async () => {
    mockFetch({
      'GET /api/v1/auth/me': unauthenticated,
      'GET /api/v1/auth/signup': enabled,
      'POST /api/v1/auth/signup': () => ({ status: 202, body: { status: 'accepted' } }),
    });
    renderAt('/signup');

    await fillForm();
    await userEvent.click(screen.getByRole('button', { name: "Crea l'associazione" }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Se questa email ha già un account StandPOS');
  });

  it('checks that the two passwords match before sending', async () => {
    const fetchMock = mockFetch({
      'GET /api/v1/auth/me': unauthenticated,
      'GET /api/v1/auth/signup': enabled,
    });
    renderAt('/signup');

    await fillForm('una-password-lunga', 'unaltra-password');
    await userEvent.click(screen.getByRole('button', { name: "Crea l'associazione" }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Le due password non coincidono.');
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
  });

  it('shows the rate limit in Italian', async () => {
    mockFetch({
      'GET /api/v1/auth/me': unauthenticated,
      'GET /api/v1/auth/signup': enabled,
      'POST /api/v1/auth/signup': () => ({ status: 429, body: { code: 'signup.rate_limited' } }),
    });
    renderAt('/signup');

    await fillForm();
    await userEvent.click(screen.getByRole('button', { name: "Crea l'associazione" }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Troppe registrazioni da questa rete');
  });
});
