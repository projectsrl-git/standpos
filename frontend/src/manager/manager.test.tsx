import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionProvider } from '../session/SessionContext';
import { mockFetch } from '../test/fetchMock';
import type { ManagerContext } from './context';
import { DayOverviewPage } from './DayOverviewPage';
import { LocksPage } from './LocksPage';
import { ProductsPage } from './ProductsPage';
import { StockPage } from './StockPage';

const manager = () => ({
  body: {
    kind: 'USER',
    user: { id: 'm1', displayName: 'Mara', email: 'mara@standpos.test' },
    memberships: [{ id: 'ms1', venueId: 'v1', role: 'MANAGER' }],
    device: null,
  },
});

function renderInManager(page: ReactNode) {
  const context: ManagerContext = {
    venueId: 'v1',
    venues: [],
    venuesLoaded: true,
    reloadVenues: async () => {},
  };
  return render(
    <MemoryRouter>
      <SessionProvider>
        <Routes>
          <Route element={<Outlet context={context} />}>
            <Route path="/" element={page} />
          </Route>
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  );
}

const product = (id: string, name: string, inUse: boolean) => ({
  id,
  categoryId: 'c1',
  name,
  priceCents: 450,
  routeId: null,
  active: true,
  trackStock: true,
  lowThreshold: 3,
  soldOut: false,
  sort: 0,
  inUse,
});

describe('manager area', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  it('offers to deactivate, not delete, a product that has been sold', async () => {
    let deactivated = false;
    mockFetch({
      'GET /api/v1/auth/me': manager,
      'GET /api/v1/venues/v1/products': () => ({
        body: [product('p1', 'Panino', true), product('p2', 'Nuovo', false)],
      }),
      'GET /api/v1/venues/v1/categories': () => ({
        body: [{ id: 'c1', name: 'Cucina', colour: '#E53935', sort: 0, defaultRouteId: null }],
      }),
      'GET /api/v1/venues/v1/routes': () => ({ body: [] }),
      'POST /api/v1/venues/v1/products/p1/deactivate': () => {
        deactivated = true;
        return { body: { ...product('p1', 'Panino', true), active: false } };
      },
    });
    renderInManager(<ProductsPage />);

    expect(await screen.findByRole('button', { name: 'Disattiva' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Elimina' })).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: 'Disattiva' }));
    expect(deactivated).toBe(true);
  });

  it('records waste with its reason and shows the stock state', async () => {
    let sent: unknown;
    mockFetch({
      'GET /api/v1/auth/me': manager,
      'GET /api/v1/venues/v1/stock': () => ({
        body: [
          {
            productId: 'p1',
            productName: 'Birra',
            categoryId: 'c1',
            active: true,
            onHand: 4,
            valueCents: 360,
            averageCostCents: 90,
            lowThreshold: 5,
            low: true,
            soldOut: false,
            manualSoldOut: false,
          },
        ],
      }),
      'POST /api/v1/venues/v1/stock/adjustments': (init) => {
        sent = JSON.parse(String(init?.body));
        return { status: 201, body: {} };
      },
    });
    renderInManager(<StockPage />);

    expect(await screen.findByText('Scorta bassa')).toBeInTheDocument();
    expect(screen.getByText(/0,90\s€/)).toBeInTheDocument();
    const quantity = screen.getByLabelText('Pezzi da scartare');
    await userEvent.clear(quantity);
    await userEvent.type(quantity, '2');
    await userEvent.type(screen.getByLabelText('Motivo (obbligatorio)'), 'Bottiglie rotte');
    await userEvent.click(screen.getByRole('button', { name: 'Salva' }));
    expect(sent).toEqual({ productId: 'p1', type: 'WASTE', quantity: 2, reason: 'Bottiglie rotte' });
  });

  it('lets the venue manager unlock a PIN before the 15 minutes', async () => {
    const future = new Date(Date.now() + 10 * 60_000).toISOString();
    let unlocked = false;
    mockFetch({
      'GET /api/v1/auth/me': manager,
      'GET /api/v1/venues/v1/users': () => ({
        body: [
          {
            userId: 'u1',
            displayName: 'Gina',
            roles: ['CASHIER'],
            lockedUntil: null,
            pinLockedUntil: unlocked ? null : future,
          },
        ],
      }),
      'POST /api/v1/venues/v1/users/u1/unlock-pin': () => {
        unlocked = true;
        return { body: {} };
      },
    });
    renderInManager(<LocksPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Sblocca PIN' }));
    expect(unlocked).toBe(true);
    expect(await screen.findAllByText('Non bloccato')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'Sblocca accesso' })).not.toBeInTheDocument();
  });

  it('shows the orders, shifts and cashier totals of a business day, with each receipt on screen', async () => {
    const day = (date: string) => ({
      businessDate: date,
      today: '2026-10-26',
      timeZone: 'Europe/Rome',
      orders:
        date === '2026-10-26'
          ? [
              {
                id: 'o2',
                dailyNumber: 2,
                createdAt: '2026-10-26T18:05:00Z',
                paidAt: '2026-10-26T18:05:00Z',
                cashierId: 'u1',
                cashierName: 'Anna',
                registerId: 'd1',
                registerName: 'Cassa 1',
                paymentMethod: 'CASH',
                totalCents: 450,
                status: 'VOIDED',
                receipt: 'NOT_REQUESTED',
                receiptPrinted: false,
                voidedAt: '2026-10-26T18:10:00Z',
                voidReason: 'Errore',
              },
              {
                id: 'o1',
                dailyNumber: 1,
                createdAt: '2026-10-26T18:00:00Z',
                paidAt: '2026-10-26T18:00:00Z',
                cashierId: 'u1',
                cashierName: 'Anna',
                registerId: 'd1',
                registerName: 'Cassa 1',
                paymentMethod: 'MANUAL_CARD',
                totalCents: 1020,
                status: 'PAID',
                receipt: 'PRINTED',
                receiptPrinted: true,
                voidedAt: null,
                voidReason: null,
              },
            ]
          : [],
      shifts:
        date === '2026-10-26'
          ? [
              {
                id: 's1',
                registerId: 'd1',
                registerName: 'Cassa 1',
                cashierId: 'u1',
                cashierName: 'Anna',
                openedAt: '2026-10-26T16:00:00Z',
                closedAt: '2026-10-26T21:00:00Z',
                openingFloatCents: 5000,
                paidOrders: 1,
                voidedOrders: 1,
                cashSalesCents: 0,
                manualCardCents: 1020,
                expectedCashCents: 5000,
                countedCashCents: 4950,
                differenceCents: -50,
              },
            ]
          : [],
      cashiers:
        date === '2026-10-26'
          ? [
              {
                cashierId: 'u1',
                cashierName: 'Anna',
                shifts: 1,
                paidOrders: 1,
                voidedOrders: 1,
                cashSalesCents: 0,
                manualCardCents: 1020,
                totalCents: 1020,
                differenceCents: -50,
              },
            ]
          : [],
      totals: { paidOrders: 1, voidedOrders: 1, cashCents: 0, cardCents: 1020, totalCents: 1020 },
    });
    mockFetch({
      'GET /api/v1/auth/me': manager,
      'GET /api/v1/venues/v1/day-overview': () => ({ body: day('2026-10-26') }),
      'GET /api/v1/venues/v1/day-overview?date=2026-10-25': () => ({ body: day('2026-10-25') }),
      'GET /api/v1/venues/v1/orders/o1/receipt-preview': () => ({
        body: {
          columns: 48,
          lines: [{ text: 'Scontrino di cortesia – documento non fiscale', bold: false, size: 'NORMAL' }],
        },
      }),
    });
    renderInManager(<DayOverviewPage />);

    const orders = await screen.findByRole('table', { name: 'Ordini' });
    const rows = within(orders).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('Stornato');
    expect(rows[1]).toHaveTextContent('Errore');
    // Times in the venue's time zone: 18:00Z is 19:00 in Rome in late October.
    expect(rows[2]).toHaveTextContent('19:00');
    expect(rows[2]).toHaveTextContent('Anna');
    expect(rows[2]).toHaveTextContent('Cassa 1');
    expect(rows[2]).toHaveTextContent('Carta (manuale)');
    expect(rows[2]).toHaveTextContent('Stampato');
    const shifts = screen.getByRole('table', { name: 'Turni' });
    expect(within(shifts).getAllByRole('row')[1]).toHaveTextContent(/-0,50\s€/);
    const cashiers = screen.getByRole('table', { name: 'Totali per cassiere' });
    expect(within(cashiers).getAllByRole('row')[1]).toHaveTextContent(/10,20\s€/);
    expect(screen.getByLabelText('Giornata')).toHaveValue('2026-10-26');

    // Only paid and voided orders have a receipt; the voided one is flagged in the preview.
    await userEvent.click(within(rows[2]!).getByRole('button', { name: 'Vedi scontrino' }));
    expect(await screen.findByRole('dialog', { name: 'Scontrino N. 1' })).toHaveTextContent(
      'documento non fiscale',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Chiudi' }));

    fireEvent.change(screen.getByLabelText('Giornata'), { target: { value: '2026-10-25' } });
    expect(await screen.findByText('Nessun ordine in questa giornata.')).toBeInTheDocument();
    expect(screen.getByText('Nessun turno aperto in questa giornata.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Oggi' }));
    expect(await screen.findByRole('table', { name: 'Ordini' })).toBeInTheDocument();
  });
});
