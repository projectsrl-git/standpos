import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';

/** Loads a list from the API and reloads it on demand; the path may be null while a venue is not chosen. */
export function useList<T>(path: string | null) {
  const [items, setItems] = useState<T[]>([]);
  const [error, setError] = useState<unknown>(null);

  const fetchItems = useCallback(() => (path ? api.get<T[]>(path) : Promise.resolve([] as T[])), [path]);

  useEffect(() => {
    let active = true;
    fetchItems().then(
      (list) => {
        if (active) {
          setItems(list);
          setError(null);
        }
      },
      (e: unknown) => {
        if (active) setError(e);
      },
    );
    return () => {
      active = false;
    };
  }, [fetchItems]);

  const reload = useCallback(async () => {
    try {
      setItems(await fetchItems());
      setError(null);
    } catch (e) {
      setError(e);
    }
  }, [fetchItems]);

  return { items, error, setError, reload };
}
