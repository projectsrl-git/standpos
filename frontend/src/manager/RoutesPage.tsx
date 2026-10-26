import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { RouteItem } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { useManager } from './context';
import { useList } from './useList';

export function RoutesPage() {
  const { t } = useTranslation();
  const { venueId } = useManager();
  const base = venueId ? `/venues/${venueId}/routes` : null;
  const { items, error, setError, reload } = useList<RouteItem>(base);
  const [editing, setEditing] = useState<RouteItem | null>(null);
  const [name, setName] = useState('');
  const [sort, setSort] = useState(0);

  function edit(route: RouteItem | null) {
    setEditing(route);
    setName(route?.name ?? '');
    setSort(route?.sort ?? 0);
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!base) return;
    try {
      if (editing) {
        await api.put(`${base}/${editing.id}`, { name, sort });
      } else {
        await api.post(base, { name, sort });
      }
      edit(null);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  async function remove(route: RouteItem) {
    if (!base || !window.confirm(t('common.confirmDelete', { name: route.name }))) return;
    try {
      await api.delete(`${base}/${route.id}`);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  return (
    <section>
      <h1>{t('routes.title')}</h1>
      <p>{t('routes.intro')}</p>
      <form className="card inline-form" onSubmit={(e) => void save(e)}>
        <h2>{editing ? t('common.edit') : t('routes.new')}</h2>
        <label className="field">
          <span>{t('common.name')}</span>
          <input required maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="field small">
          <span>{t('common.sort')}</span>
          <input type="number" value={sort} onChange={(e) => setSort(Number(e.target.value))} />
        </label>
        <button className="btn btn-primary" type="submit">
          {t('common.save')}
        </button>
        {editing && (
          <button className="btn" type="button" onClick={() => edit(null)}>
            {t('common.cancel')}
          </button>
        )}
      </form>
      <ErrorBanner error={error} />
      <table className="table">
        <thead>
          <tr>
            <th>{t('common.name')}</th>
            <th>{t('common.sort')}</th>
            <th>{t('common.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((route) => (
            <tr key={route.id}>
              <td>{route.name}</td>
              <td>{route.sort}</td>
              <td className="row-actions">
                <button className="btn" type="button" onClick={() => edit(route)}>
                  {t('common.edit')}
                </button>
                <button className="btn btn-danger" type="button" onClick={() => void remove(route)}>
                  {t('common.delete')}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {items.length === 0 && <p>{t('common.empty')}</p>}
    </section>
  );
}
