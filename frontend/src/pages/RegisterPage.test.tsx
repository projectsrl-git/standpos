import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RegisterGrid, RegisterStatus } from '../api/types';
import { SessionProvider } from '../session/SessionContext';
import { mockFetch } from '../test/fetchMock';
import { RegisterPage } from './RegisterPage';

// No WebSocket in tests: live events are covered by the backend's RealtimeIT.
vi.mock('../register/useVenueEvents', () => ({ useVenueEvents: () => 'online' }));

const me = () => ({
  body: {
    kind: 'CASHIER',
    user: { id: 'u1', displayName: 'Anna', email: null },
    memberships: [],
    device: { id: 'd1', venueId: 'v1', kind: 'REGISTER', name: 'Cassa 1' },
  },
});

const grid: RegisterGrid = {
  venueId: 'v1',
  venueName: 'Bar oratorio',
  categories: [
    {
      id: 'c1',
      name: 'Cucina',
      colour: '#E53935',
      products: [
        { id: 'p1', name: 'Panino', priceCents: 450, soldOut: false, onHand: 2, low: true },
        { id: 'p2', name: 'Lasagne', priceCents: 700, soldOut: true, onHand: 0, low: true },
        { id: 'p3', name: 'Acqua', priceCents: 100, soldOut: false, onHand: null, low: false },
      ],
    },
  ],
};

const shift = {
  id: 's1',
  cashierId: 'u1',
  openedAt: '2026-10-26T17:00:00Z',
  openingFloatCents: 5000,
  closedAt: null,
  paidOrders: 0,
  voidedOrders: 0,
  cashSalesCents: 0,
  manualCardCents: 0,
  expectedCashCents: 5000,
  countedCashCents: null,
  differenceCents: null,
};

function status(patch: Partial<RegisterStatus> = {}): RegisterStatus {
  return {
    shift,
    printer: { id: 'pr1', name: 'POS80', agentId: 'a1', online: true },
    usage: { capReached: false, blockedFrom: null, blocked: false },
    ...patch,
  };
}

function order(clientOrderId: string) {
  return {
    id: 'o1',
    clientOrderId,
    dailyNumber: 7,
    businessDate: '2026-10-26',
    status: 'PAID',
    totalCents: 1000,
    createdAt: '2026-10-26T18:00:00Z',
    paidAt: '2026-10-26T18:00:00Z',
    deviceId: 'd1',
    receiptPrinted: false,
    receipt: 'QUEUED',
    receiptError: null,
    lines: [],
    payment: {
      method: 'CASH',
      status: 'CONFIRMED',
      amountCents: 1000,
      tenderedCents: 2000,
      changeCents: 1000,
      needsReconciliation: false,
    },
    voidedAt: null,
    voidReason: null,
  };
}

function renderRegister() {
  return render(
    <MemoryRouter>
      <SessionProvider>
        <RegisterPage />
      </SessionProvider>
    </MemoryRouter>,
  );
}

describe('RegisterPage', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
  });

  it('shows availability: what is left below the threshold, sold out greyed', async () => {
    mockFetch({
      'GET /api/v1/auth/me': me,
      'GET /api/v1/register/catalogue': () => ({ body: grid }),
      'GET /api/v1/register/status': () => ({ body: status() }),
    });
    renderRegister();

    const panino = await screen.findByRole('button', { name: /Panino/ });
    expect(panino).toHaveTextContent(/4,50\s€/);
    expect(panino).toHaveTextContent('Ne restano 2');
    expect(screen.getByRole('button', { name: /Lasagne/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Acqua/ })).not.toHaveTextContent('Ne restano');
    expect(await screen.findByText('Cassiere: Anna')).toBeInTheDocument();
  });

  it('sells for cash with change, and a retry after a network error reuses the order id', async () => {
    const sent: { clientOrderId: string; lines: unknown; tenderedCents: number; printReceipt: boolean }[] =
      [];
    let failFirst = true;
    const fetchMock = mockFetch({
      'GET /api/v1/auth/me': me,
      'GET /api/v1/register/catalogue': () => ({ body: grid }),
      'GET /api/v1/register/status': () => ({ body: status() }),
      'POST /api/v1/orders': (init) => {
        const body = JSON.parse(String(init?.body)) as (typeof sent)[number];
        sent.push(body);
        return { status: 201, body: order(body.clientOrderId) };
      },
    });
    // The first attempt never reaches the server.
    const realImpl = fetchMock.getMockImplementation();
    fetchMock.mockImplementation(async (input, init) => {
      if (String(input).endsWith('/orders') && failFirst) {
        failFirst = false;
        throw new TypeError('Failed to fetch');
      }
      return realImpl!(input, init);
    });
    renderRegister();

    await userEvent.click(await screen.findByRole('button', { name: /Panino/ }));
    await userEvent.click(screen.getByRole('button', { name: /Acqua/ }));
    await userEvent.click(screen.getByRole('button', { name: /Uno in più: Acqua/ }));
    const cart = screen.getByRole('complementary', { name: 'Ordine' });
    expect(within(cart).getByText(/6,50\s€/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Contanti' }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('Ricevuto (€)'), '10');
    expect(within(dialog).getByRole('status')).toHaveTextContent(/Resto: 3,50\s€/);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Conferma e stampa' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Connessione assente');

    await userEvent.click(within(dialog).getByRole('button', { name: 'Conferma e stampa' }));
    expect(await screen.findByText('Ordine N. 7')).toBeInTheDocument();
    expect(sent).toHaveLength(1);
    const retried = sent[0]!;
    expect(retried.tenderedCents).toBe(1000);
    expect(retried.printReceipt).toBe(true);
    expect(retried.lines).toEqual([
      { productId: 'p1', quantity: 1, note: null },
      { productId: 'p3', quantity: 2, note: null },
    ]);
    const attempts = fetchMock.mock.calls
      .filter(([url, init]) => String(url).endsWith('/orders') && init?.method === 'POST')
      .map(([, init]) => (JSON.parse(String(init?.body)) as { clientOrderId: string }).clientOrderId);
    expect(attempts).toHaveLength(2);
    expect(attempts[0]).toBe(attempts[1]);
  });

  it('asks for the opening float when no shift is open', async () => {
    let opened: unknown;
    mockFetch({
      'GET /api/v1/auth/me': me,
      'GET /api/v1/register/catalogue': () => ({ body: grid }),
      'GET /api/v1/register/status': () => ({ body: status({ shift: opened ? shift : null }) }),
      'POST /api/v1/register/shift/open': (init) => {
        opened = JSON.parse(String(init?.body));
        return { status: 201, body: shift };
      },
    });
    renderRegister();

    await userEvent.type(await screen.findByLabelText('Fondo cassa (€)'), '50');
    await userEvent.click(screen.getByRole('button', { name: 'Apri turno' }));
    expect(opened).toEqual({ openingFloatCents: 5000 });
    expect(await screen.findByRole('button', { name: /Panino/ })).toBeInTheDocument();
  });

  it('warns when the printer is offline and blocks payment once the cap blocks sales', async () => {
    mockFetch({
      'GET /api/v1/auth/me': me,
      'GET /api/v1/register/catalogue': () => ({ body: grid }),
      'GET /api/v1/register/status': () => ({
        body: status({
          printer: { id: 'pr1', name: 'POS80', agentId: 'a1', online: false },
          usage: { capReached: true, blockedFrom: '2026-10-27T03:00:00Z', blocked: true },
        }),
      }),
    });
    renderRegister();

    expect(await screen.findByText(/Stampante offline \(POS80\)/)).toBeInTheDocument();
    expect(screen.getByText(/le nuove vendite sono bloccate/)).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: /Panino/ }));
    expect(screen.getByRole('button', { name: 'Contanti' })).toBeDisabled();
  });
});
