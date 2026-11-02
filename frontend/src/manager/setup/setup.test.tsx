import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Venue } from '../../api/types';
import { SessionProvider } from '../../session/SessionContext';
import { mockFetch } from '../../test/fetchMock';
import { ManagerHome, ManagerLayout } from '../ManagerLayout';
import { SetupPage } from './SetupWizard';

const owner = {
  kind: 'USER',
  tenant: { id: 't1', name: 'Oratorio Nuovo' },
  user: { id: 'o1', displayName: 'Anna Rossi', email: 'anna@example.org' },
  memberships: [{ id: 'm0', venueId: null, role: 'OWNER' }],
  device: null,
  platformAdmin: false,
};

interface Posted {
  url: string;
  body: Record<string, unknown>;
}

/** A tiny in-memory tenant behind the mocked API: what the wizard writes, it reads back. */
function backend() {
  const state = {
    venues: [] as Venue[],
    routes: [] as { id: string; name: string; sort: number }[],
    categories: [] as { id: string; name: string }[],
    products: [] as { id: string; name: string }[],
    users: [
      {
        id: 'o1',
        displayName: 'Anna Rossi',
        email: 'anna@example.org',
        hasPassword: true,
        hasPin: false,
        lockedUntil: null,
        pinLockedUntil: null,
        memberships: [{ id: 'm0', venueId: null, role: 'OWNER' }],
      },
    ] as Record<string, unknown>[],
    posted: [] as Posted[],
  };
  const body = (init: RequestInit | undefined) => JSON.parse(String(init?.body)) as Record<string, unknown>;
  const record = (url: string, init: RequestInit | undefined) => {
    const b = body(init);
    state.posted.push({ url, body: b });
    return b;
  };
  let codes = 0;
  mockFetch({
    'GET /api/v1/auth/me': () => ({ body: owner }),
    'GET /api/v1/usage': () => ({
      body: { sales: 0, cap: 2000, warning: false, capReached: false, blocked: false },
    }),
    'GET /api/v1/venues': () => ({ body: state.venues }),
    'POST /api/v1/venues': (init) => {
      const b = record('/venues', init);
      const venue = {
        ...b,
        id: `v${state.venues.length + 1}`,
        ticketMode: 'RECEIPT_ONLY',
      } as unknown as Venue;
      state.venues.push(venue);
      return { status: 201, body: venue };
    },
    'GET /api/v1/users': () => ({ body: state.users }),
    'POST /api/v1/users': (init) => {
      const b = record('/users', init);
      const user = {
        id: `u${state.users.length}`,
        displayName: b.displayName,
        email: b.email,
        hasPassword: Boolean(b.password),
        hasPin: Boolean(b.pin),
        lockedUntil: null,
        pinLockedUntil: null,
        memberships: [{ id: `m${state.users.length}`, venueId: b.venueId, role: b.role }],
      };
      state.users.push(user);
      return { status: 201, body: user };
    },
    'GET /api/v1/venues/v1/categories': () => ({ body: state.categories }),
    'GET /api/v1/venues/v1/products': () => ({ body: state.products }),
    'GET /api/v1/venues/v1/devices': () => ({ body: [] }),
    'GET /api/v1/venues/v1/routes': () => ({ body: state.routes }),
    'POST /api/v1/venues/v1/routes': (init) => {
      const route = {
        ...record('routes', init),
        id: `r${state.routes.length}`,
      } as (typeof state.routes)[number];
      state.routes.push(route);
      return { status: 201, body: route };
    },
    'POST /api/v1/venues/v1/categories': (init) => {
      const category = { ...record('categories', init), id: `c${state.categories.length}` } as {
        id: string;
        name: string;
      };
      state.categories.push(category);
      return { status: 201, body: category };
    },
    'POST /api/v1/venues/v1/products': (init) => {
      const product = { ...record('products', init), id: `p${state.products.length}` } as {
        id: string;
        name: string;
      };
      state.products.push(product);
      return { status: 201, body: product };
    },
    'POST /api/v1/venues/v1/enrolment-codes': (init) => {
      const b = record('codes', init);
      codes += 1;
      return {
        status: 201,
        body: { code: `CASSA00${codes}`, kind: b.kind, expiresAt: '2026-11-02T10:10:00Z' },
      };
    },
  });
  return state;
}

function renderManager() {
  return render(
    <MemoryRouter initialEntries={['/manager']}>
      <SessionProvider>
        <Routes>
          <Route path="/manager" element={<ManagerLayout />}>
            <Route index element={<ManagerHome />} />
            <Route path="setup" element={<SetupPage />} />
          </Route>
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  );
}

describe('setup wizard', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    localStorage.clear();
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  });

  it('walks a new owner through venue, catalogue, registers, staff and summary', async () => {
    const state = backend();
    renderManager();

    // No venue yet: the manager home opens the wizard.
    expect(await screen.findByRole('heading', { name: 'Dove vendi?' })).toBeInTheDocument();

    // 1. Venue
    await userEvent.type(screen.getByLabelText(/Nome del locale/), 'Festa patronale');
    await userEvent.click(screen.getByRole('radio', { name: /Evento/ }));
    fireEvent.change(screen.getByLabelText('Cambio giornata'), { target: { value: '05:00' } });
    await userEvent.click(screen.getByRole('button', { name: 'Aggiungi il locale' }));
    expect(await screen.findByText(/cambio giornata alle 05:00/)).toBeInTheDocument();
    expect(state.posted[0]).toEqual({
      url: '/venues',
      body: expect.objectContaining({
        name: 'Festa patronale',
        kind: 'EVENT',
        timeZone: 'Europe/Rome',
        dayChangeTime: '05:00',
      }) as unknown,
    });
    await userEvent.click(await screen.findByRole('button', { name: 'Avanti' }));

    // 2. Starter catalogue
    await userEvent.click(
      await screen.findByRole('button', { name: 'Crea il listino di esempio per «Festa patronale»' }),
    );
    expect(await screen.findByText("Il listino c'è già: 3 categorie e 8 prodotti.")).toBeInTheDocument();
    expect(state.routes.map((r) => r.name)).toEqual(['Bar', 'Cucina']);
    expect(state.posted.filter((p) => p.url === 'products')[0]?.body).toMatchObject({
      name: 'Acqua naturale',
      priceCents: 100,
      trackStock: false,
    });
    await userEvent.click(screen.getByRole('button', { name: 'Avanti' }));

    // 3. Registers: two codes, plus the print agent
    expect(await screen.findByText(/Su ogni computer o tablet che farà da cassa apri/)).toHaveTextContent(
      `${window.location.origin}/enrol`,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Una cassa in più' }));
    await userEvent.click(screen.getByRole('button', { name: 'Genera 2 codici' }));
    const codes = await screen.findByRole('list', { name: 'Codici per Festa patronale' });
    expect(within(codes).getByText('CASSA001')).toBeInTheDocument();
    expect(within(codes).getByText('CASSA002')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: "Genera il codice per l'agente di stampa" }));
    expect(await screen.findByText('CASSA003')).toBeInTheDocument();
    expect(state.posted.filter((p) => p.url === 'codes').map((p) => p.body.kind)).toEqual([
      'REGISTER',
      'REGISTER',
      'PRINT_AGENT',
    ]);
    // Nobody enrolled yet: the step can be skipped.
    await userEvent.click(screen.getByRole('button', { name: 'Salta questo passaggio' }));

    // 4. Staff
    await userEvent.type(await screen.findByLabelText('Nome'), 'Luca');
    await userEvent.type(screen.getByLabelText('PIN (4–6 cifre)'), '1357');
    await userEvent.click(screen.getByRole('button', { name: 'Aggiungi' }));
    expect(await screen.findByText('Luca')).toBeInTheDocument();
    expect(state.posted.find((p) => p.url === '/users')?.body).toEqual({
      displayName: 'Luca',
      email: null,
      password: null,
      pin: '1357',
      venueId: 'v1',
      role: 'CASHIER',
    });
    await userEvent.click(screen.getByRole('button', { name: 'Avanti' }));

    // 5. Summary
    expect(await screen.findByText('Listino: 3 categorie, 8 prodotti')).toBeInTheDocument();
    expect(screen.getByText('1 persona con un ruolo nel locale')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: "Vai all'area gestione" })).toBeInTheDocument();
  });

  it('asks a manager login only for managers, and needs a password with the email', async () => {
    const state = backend();
    state.venues.push({
      id: 'v1',
      name: 'Bar oratorio',
      kind: 'PERMANENT',
      timeZone: 'Europe/Rome',
      dayChangeTime: '04:00:00',
      receiptHeader: null,
      receiptFooter: null,
      ticketMode: 'RECEIPT_ONLY',
      active: true,
      allowNegativeStock: false,
    });
    localStorage.setItem('standpos.setup.step.t1', '3');
    render(
      <MemoryRouter initialEntries={['/manager/setup']}>
        <SessionProvider>
          <Routes>
            <Route path="/manager" element={<ManagerLayout />}>
              <Route path="setup" element={<SetupPage />} />
            </Route>
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    );

    // Resumed at the staff step.
    expect(await screen.findByRole('heading', { name: 'Cassieri e gestori' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Email')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: /Gestore/ }));
    await userEvent.type(screen.getByLabelText('Nome'), 'Mara');
    await userEvent.type(screen.getByLabelText('Email'), 'mara@example.org');
    await userEvent.click(screen.getByRole('button', { name: 'Aggiungi' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('serve una password di almeno 10 caratteri');
    expect(state.posted).toEqual([]);

    await userEvent.type(screen.getByLabelText('Password (almeno 10 caratteri)'), 'password-mara-1');
    await userEvent.click(screen.getByRole('button', { name: 'Aggiungi' }));
    expect(await screen.findByText('Mara')).toBeInTheDocument();
    expect(state.posted[0]?.body).toMatchObject({
      email: 'mara@example.org',
      password: 'password-mara-1',
      pin: null,
      role: 'MANAGER',
    });
  });
});
