import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, api } from '../api/client';
import type { Category, Product, RouteItem } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { centsToInput, formatCents, parseEuroToCents } from '../money';
import { useManager } from './context';
import { useList } from './useList';

interface Draft {
  categoryId: string;
  name: string;
  price: string;
  routeId: string | null;
  active: boolean;
  trackStock: boolean;
  lowThreshold: string;
  soldOut: boolean;
  sort: number;
}

const EMPTY: Draft = {
  categoryId: '',
  name: '',
  price: '',
  routeId: null,
  active: true,
  trackStock: false,
  lowThreshold: '',
  soldOut: false,
  sort: 0,
};

export function ProductsPage() {
  const { t } = useTranslation();
  const { venueId } = useManager();
  const base = venueId ? `/venues/${venueId}/products` : null;
  const { items, error, setError, reload } = useList<Product>(base);
  const categories = useList<Category>(venueId ? `/venues/${venueId}/categories` : null);
  const routes = useList<RouteItem>(venueId ? `/venues/${venueId}/routes` : null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [priceError, setPriceError] = useState(false);

  function edit(product: Product | null) {
    setEditingId(product?.id ?? null);
    setPriceError(false);
    setDraft(
      product
        ? {
            ...product,
            price: centsToInput(product.priceCents),
            lowThreshold: product.lowThreshold === null ? '' : String(product.lowThreshold),
          }
        : { ...EMPTY, categoryId: categories.items[0]?.id ?? '' },
    );
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!base) return;
    const priceCents = parseEuroToCents(draft.price);
    if (priceCents === null) {
      setPriceError(true);
      return;
    }
    setPriceError(false);
    const body = {
      categoryId: draft.categoryId || categories.items[0]?.id,
      name: draft.name,
      priceCents,
      routeId: draft.routeId,
      active: draft.active,
      trackStock: draft.trackStock,
      lowThreshold: draft.lowThreshold === '' ? null : Number.parseInt(draft.lowThreshold, 10),
      soldOut: draft.soldOut,
      sort: draft.sort,
    };
    try {
      if (editingId) {
        await api.put(`${base}/${editingId}`, body);
      } else {
        await api.post(base, body);
      }
      edit(null);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  async function remove(product: Product) {
    if (!base || !window.confirm(t('common.confirmDelete', { name: product.name }))) return;
    try {
      await api.delete(`${base}/${product.id}`);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  /** Sold or stocked products keep their history: they are hidden from the register, not deleted. */
  async function deactivate(product: Product) {
    if (!base || !window.confirm(t('products.confirmDeactivate', { name: product.name }))) return;
    try {
      await api.post(`${base}/${product.id}/deactivate`);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  const categoryName = (id: string) => categories.items.find((c) => c.id === id)?.name ?? '';

  if (categories.items.length === 0) {
    return (
      <section>
        <h1>{t('products.title')}</h1>
        <p>{t('products.needCategory')}</p>
      </section>
    );
  }

  return (
    <section>
      <h1>{t('products.title')}</h1>
      <form className="card inline-form" onSubmit={(e) => void save(e)}>
        <h2>{editingId ? t('common.edit') : t('products.new')}</h2>
        <label className="field">
          <span>{t('common.name')}</span>
          <input
            required
            maxLength={80}
            value={draft.name}
            onChange={(e) => setDraft({ ...draft, name: e.target.value })}
          />
        </label>
        <label className="field">
          <span>{t('products.category')}</span>
          <select
            required
            value={draft.categoryId || categories.items[0]?.id}
            onChange={(e) => setDraft({ ...draft, categoryId: e.target.value })}
          >
            {categories.items.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label className="field small">
          <span>{t('products.price')}</span>
          <input
            required
            inputMode="decimal"
            placeholder={t('products.pricePlaceholder')}
            aria-invalid={priceError}
            value={draft.price}
            onChange={(e) => setDraft({ ...draft, price: e.target.value })}
          />
        </label>
        <label className="field">
          <span>{t('products.route')}</span>
          <select
            value={draft.routeId ?? ''}
            onChange={(e) => setDraft({ ...draft, routeId: e.target.value || null })}
          >
            <option value="">{t('common.none')}</option>
            {routes.items.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </label>
        <label className="field small">
          <span>{t('common.sort')}</span>
          <input
            type="number"
            value={draft.sort}
            onChange={(e) => setDraft({ ...draft, sort: Number(e.target.value) })}
          />
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={draft.active}
            onChange={(e) => setDraft({ ...draft, active: e.target.checked })}
          />
          <span>{t('products.active')}</span>
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={draft.trackStock}
            onChange={(e) => setDraft({ ...draft, trackStock: e.target.checked })}
          />
          <span>{t('products.trackStock')}</span>
        </label>
        <label className="field small">
          <span>{t('products.lowThreshold')}</span>
          <input
            type="number"
            min={0}
            value={draft.lowThreshold}
            onChange={(e) => setDraft({ ...draft, lowThreshold: e.target.value })}
          />
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={draft.soldOut}
            onChange={(e) => setDraft({ ...draft, soldOut: e.target.checked })}
          />
          <span>{t('products.soldOut')}</span>
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
      <ErrorBanner error={priceError ? new ApiError(400, 'invalidPrice') : error} />
      <table className="table">
        <thead>
          <tr>
            <th>{t('common.name')}</th>
            <th>{t('products.category')}</th>
            <th>{t('products.price')}</th>
            <th>{t('products.active')}</th>
            <th>{t('products.soldOut')}</th>
            <th>{t('common.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((p) => (
            <tr key={p.id}>
              <td>{p.name}</td>
              <td>{categoryName(p.categoryId)}</td>
              <td>{formatCents(p.priceCents)}</td>
              <td>{p.active ? t('common.yes') : t('common.no')}</td>
              <td>{p.soldOut ? t('common.yes') : t('common.no')}</td>
              <td className="row-actions">
                <button className="btn" type="button" onClick={() => edit(p)}>
                  {t('common.edit')}
                </button>
                {p.inUse ? (
                  p.active && (
                    <button className="btn btn-danger" type="button" onClick={() => void deactivate(p)}>
                      {t('products.deactivate')}
                    </button>
                  )
                ) : (
                  <button className="btn btn-danger" type="button" onClick={() => void remove(p)}>
                    {t('common.delete')}
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {items.length === 0 && <p>{t('common.empty')}</p>}
    </section>
  );
}
