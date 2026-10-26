import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionProvider } from '../session/SessionContext';
import { mockFetch } from '../test/fetchMock';
import type { ManagerContext } from './context';
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
  const context: ManagerContext = { venueId: 'v1', venues: [], reloadVenues: async () => {} };
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
});
