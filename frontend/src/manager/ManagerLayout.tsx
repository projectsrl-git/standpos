import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, NavLink, Outlet, useNavigate } from 'react-router';
import { api } from '../api/client';
import type { Usage, Venue } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { useSession } from '../session/SessionContext';
import { canManageStock, canManageVenue, isOwner } from '../session/roles';
import { useManager, type ManagerContext } from './context';

const VENUE_KEY = 'standpos.manager.venue';

function rememberedVenue(): string | null {
  try {
    return localStorage.getItem(VENUE_KEY);
  } catch {
    return null;
  }
}

export function ManagerLayout() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { me, logout } = useSession();
  const [venues, setVenues] = useState<Venue[]>([]);
  const [venueId, setVenueIdState] = useState<string | null>(rememberedVenue);
  const [error, setError] = useState<unknown>(null);
  const [usage, setUsage] = useState<Usage | null>(null);

  const setVenueId = useCallback((id: string) => {
    setVenueIdState(id);
    try {
      localStorage.setItem(VENUE_KEY, id);
    } catch {
      // Storage unavailable: the choice lasts for this page only.
    }
  }, []);

  const applyVenues = useCallback((list: Venue[]) => {
    setVenues(list);
    setVenueIdState((current) =>
      current && list.some((v) => v.id === current) ? current : (list[0]?.id ?? null),
    );
  }, []);

  const reloadVenues = useCallback(async () => {
    try {
      applyVenues(await api.get<Venue[]>('/venues'));
    } catch (e) {
      setError(e);
    }
  }, [applyVenues]);

  useEffect(() => {
    let active = true;
    api.get<Venue[]>('/venues').then(
      (list) => {
        if (active) applyVenues(list);
      },
      (e: unknown) => {
        if (active) setError(e);
      },
    );
    return () => {
      active = false;
    };
  }, [applyVenues]);

  // Owners see the monthly usage: a banner from 80% of the cap.
  useEffect(() => {
    if (!isOwner(me)) return undefined;
    let active = true;
    api.get<Usage>('/usage').then(
      (u) => {
        if (active) setUsage(u);
      },
      () => undefined,
    );
    return () => {
      active = false;
    };
  }, [me]);

  const owner = isOwner(me);
  const manager = canManageVenue(me, venueId);
  const stock = canManageStock(me, venueId);
  const context: ManagerContext = { venueId, venues, reloadVenues };

  return (
    <div className="manager">
      <header className="topbar">
        <strong>{me?.tenant?.name ?? t('manager.title')}</strong>
        <label className="venue-picker">
          <span>{t('manager.venue')}</span>
          <select value={venueId ?? ''} onChange={(e) => setVenueId(e.target.value)}>
            {venues.map((v) => (
              <option key={v.id} value={v.id}>
                {v.name}
              </option>
            ))}
          </select>
        </label>
        <span>{me?.user?.displayName}</span>
        <button
          type="button"
          className="btn"
          onClick={() => {
            void logout().then(() => navigate('/login'));
          }}
        >
          {t('common.logout')}
        </button>
      </header>
      <nav className="tabs">
        {owner && <NavLink to="/manager/venues">{t('manager.nav.venues')}</NavLink>}
        {owner && <NavLink to="/manager/users">{t('manager.nav.users')}</NavLink>}
        {manager && <NavLink to="/manager/devices">{t('manager.nav.devices')}</NavLink>}
        {manager && <NavLink to="/manager/printers">{t('manager.nav.printers')}</NavLink>}
        {manager && <NavLink to="/manager/routes">{t('manager.nav.routes')}</NavLink>}
        {manager && <NavLink to="/manager/categories">{t('manager.nav.categories')}</NavLink>}
        {manager && <NavLink to="/manager/products">{t('manager.nav.products')}</NavLink>}
        {stock && <NavLink to="/manager/stock">{t('manager.nav.stock')}</NavLink>}
        {stock && <NavLink to="/manager/receipts">{t('manager.nav.receipts')}</NavLink>}
        {stock && <NavLink to="/manager/counts">{t('manager.nav.counts')}</NavLink>}
        {stock && <NavLink to="/manager/suppliers">{t('manager.nav.suppliers')}</NavLink>}
        {manager && <NavLink to="/manager/locks">{t('manager.nav.locks')}</NavLink>}
      </nav>
      {usage?.capReached && (
        <p className="error banner" role="alert">
          {usage.blocked ? t('usage.blocked', { cap: usage.cap }) : t('usage.reached', { cap: usage.cap })}
        </p>
      )}
      {usage?.warning && !usage.capReached && (
        <p className="warning banner" role="status">
          {t('usage.warning', { sales: usage.sales, cap: usage.cap })}
        </p>
      )}
      <ErrorBanner error={error} />
      <main className="content">
        {venues.length === 0 && !error ? <p>{t('manager.noVenue')}</p> : null}
        <Outlet context={context} />
      </main>
    </div>
  );
}

/** The first page of the manager area: the catalogue for managers, the stock for stock clerks. */
export function ManagerHome() {
  const { me } = useSession();
  const { venueId } = useManager();
  if (!venueId) return null;
  return <Navigate to={canManageVenue(me, venueId) ? 'products' : 'stock'} replace />;
}
