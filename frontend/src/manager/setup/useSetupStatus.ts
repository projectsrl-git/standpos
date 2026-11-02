import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { Category, Device, Product, User, Venue } from '../../api/types';

export interface VenueStatus {
  venue: Venue;
  categories: number;
  products: number;
  /** Enrolled, not revoked. */
  registers: number;
  agents: number;
  /** Users holding a role on this venue (owners hold every venue and are not listed). */
  staff: User[];
}

export interface SetupStatus {
  venues: VenueStatus[];
  users: User[];
  loaded: boolean;
  error: unknown;
  reload: () => Promise<void>;
}

async function load(venues: Venue[]): Promise<{ venues: VenueStatus[]; users: User[] }> {
  const users = await api.get<User[]>('/users');
  const perVenue = await Promise.all(
    venues.map(async (venue) => {
      const base = `/venues/${venue.id}`;
      const [categories, products, devices] = await Promise.all([
        api.get<Category[]>(`${base}/categories`),
        api.get<Product[]>(`${base}/products`),
        api.get<Device[]>(`${base}/devices`),
      ]);
      const live = devices.filter((d) => !d.revokedAt);
      return {
        venue,
        categories: categories.length,
        products: products.length,
        registers: live.filter((d) => d.kind === 'REGISTER').length,
        agents: live.filter((d) => d.kind === 'PRINT_AGENT').length,
        staff: users.filter((u) => u.memberships.some((m) => m.venueId === venue.id)),
      };
    }),
  );
  return { venues: perVenue, users };
}

/** What the tenant already has, per venue: the wizard is resumable because it reads its progress from the server. */
export function useSetupStatus(venues: Venue[]): SetupStatus {
  const [data, setData] = useState<{ venues: VenueStatus[]; users: User[] }>({ venues: [], users: [] });
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let active = true;
    load(venues).then(
      (result) => {
        if (!active) return;
        setData(result);
        setLoaded(true);
        setError(null);
      },
      (e: unknown) => {
        if (active) setError(e);
      },
    );
    return () => {
      active = false;
    };
  }, [venues]);

  const reload = useCallback(async () => {
    try {
      setData(await load(venues));
      setError(null);
    } catch (e) {
      setError(e);
    }
  }, [venues]);

  return { ...data, loaded, error, reload };
}
