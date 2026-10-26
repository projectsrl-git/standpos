import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { Category, RouteItem } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { useManager } from './context';
import { useList } from './useList';

interface Draft {
  name: string;
  colour: string;
  sort: number;
  defaultRouteId: string | null;
}

const EMPTY: Draft = { name: '', colour: '#1E88E5', sort: 0, defaultRouteId: null };

export function CategoriesPage() {
  const { t } = useTranslation();
  const { venueId } = useManager();
  const base = venueId ? `/venues/${venueId}/categories` : null;
  const { items, error, setError, reload } = useList<Category>(base);
  const routes = useList<RouteItem>(venueId ? `/venues/${venueId}/routes` : null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);

  function edit(category: Category | null) {
    setEditingId(category?.id ?? null);
    setDraft(category ? { ...category } : EMPTY);
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!base) return;
    try {
      if (editingId) {
        await api.put(`${base}/${editingId}`, draft);
      } else {
        await api.post(base, draft);
      }
      edit(null);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  async function remove(category: Category) {
    if (!base || !window.confirm(t('common.confirmDelete', { name: category.name }))) return;
    try {
      await api.delete(`${base}/${category.id}`);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  const routeName = (id: string | null) => routes.items.find((r) => r.id === id)?.name ?? t('common.none');

  return (
    <section>
      <h1>{t('categories.title')}</h1>
      <form className="card inline-form" onSubmit={(e) => void save(e)}>
        <h2>{editingId ? t('common.edit') : t('categories.new')}</h2>
        <label className="field">
          <span>{t('common.name')}</span>
          <input
            required
            maxLength={60}
            value={draft.name}
            onChange={(e) => setDraft({ ...draft, name: e.target.value })}
          />
        </label>
        <label className="field small">
          <span>{t('categories.colour')}</span>
          <input
            type="color"
            value={draft.colour}
            onChange={(e) => setDraft({ ...draft, colour: e.target.value.toUpperCase() })}
          />
        </label>
        <label className="field small">
          <span>{t('common.sort')}</span>
          <input
            type="number"
            value={draft.sort}
            onChange={(e) => setDraft({ ...draft, sort: Number(e.target.value) })}
          />
        </label>
        <label className="field">
          <span>{t('categories.defaultRoute')}</span>
          <select
            value={draft.defaultRouteId ?? ''}
            onChange={(e) => setDraft({ ...draft, defaultRouteId: e.target.value || null })}
          >
            <option value="">{t('common.none')}</option>
            {routes.items.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </label>
        <button className="btn btn-primary" type="submit">
          {t('common.save')}
        </button>
        {editingId && (
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
            <th>{t('categories.colour')}</th>
            <th>{t('categories.defaultRoute')}</th>
            <th>{t('common.sort')}</th>
            <th>{t('common.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((c) => (
            <tr key={c.id}>
              <td>{c.name}</td>
              <td>
                <span className="swatch" style={{ background: c.colour }} /> {c.colour}
              </td>
              <td>{routeName(c.defaultRouteId)}</td>
              <td>{c.sort}</td>
              <td className="row-actions">
                <button className="btn" type="button" onClick={() => edit(c)}>
                  {t('common.edit')}
                </button>
                <button className="btn btn-danger" type="button" onClick={() => void remove(c)}>
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
