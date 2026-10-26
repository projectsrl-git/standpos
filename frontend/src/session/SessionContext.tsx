import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ApiError, api } from '../api/client';
import type { Me } from '../api/types';

interface Session {
  me: Me | null;
  loading: boolean;
  refresh: () => Promise<Me | null>;
  logout: () => Promise<void>;
}

const SessionContext = createContext<Session | null>(null);

/** The current caller, or null when nobody is logged in and the browser is not an enrolled device. */
function loadMe(): Promise<Me | null> {
  return api.get<Me>('/auth/me').catch((error: unknown) => {
    if (error instanceof ApiError && error.status === 401) {
      return null;
    }
    throw error;
  });
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    loadMe()
      .then(
        (current) => {
          if (active) setMe(current);
        },
        () => undefined,
      )
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const refresh = useCallback(async () => {
    const current = await loadMe();
    setMe(current);
    setLoading(false);
    return current;
  }, []);

  const logout = useCallback(async () => {
    await api.post('/auth/logout');
    await refresh();
  }, [refresh]);

  const value = useMemo(() => ({ me, loading, refresh, logout }), [me, loading, refresh, logout]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useSession(): Session {
  const session = useContext(SessionContext);
  if (!session) {
    throw new Error('useSession outside SessionProvider');
  }
  return session;
}
