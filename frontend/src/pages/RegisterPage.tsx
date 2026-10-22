import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { api } from '../api/client';
import type { RegisterGrid } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { formatCents } from '../money';
import { useSession } from '../session/SessionContext';

/** Product grid by category. Read-only in Phase 0: the cart arrives in Phase 1. */
export function RegisterPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { me, logout } = useSession();
  const [grid, setGrid] = useState<RegisterGrid | null>(null);
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    api.get<RegisterGrid>('/register/catalogue').then((data) => {
      setGrid(data);
      setCategoryId(data.categories[0]?.id ?? null);
    }, setError);
  }, []);

  const category = grid?.categories.find((c) => c.id === categoryId);

  return (
    <div className="register">
      <header className="topbar">
        <strong>{grid?.venueName ?? t('register.title')}</strong>
        <span>{t('register.cashier', { name: me?.user?.displayName ?? '' })}</span>
        <button
          type="button"
          className="btn"
          onClick={() => {
            void logout().then(() => navigate('/pin'));
          }}
        >
          {t('common.logout')}
        </button>
      </header>
      <ErrorBanner error={error} />
      <p className="notice">{t('register.readOnly')}</p>
      {grid && grid.categories.length === 0 && <p>{t('register.emptyCatalogue')}</p>}
      <nav className="category-tabs" aria-label={t('categories.title')}>
        {grid?.categories.map((c) => (
          <button
            key={c.id}
            type="button"
            className={c.id === categoryId ? 'category-tab selected' : 'category-tab'}
            style={{ borderColor: c.colour, background: c.id === categoryId ? c.colour : undefined }}
            aria-pressed={c.id === categoryId}
            onClick={() => setCategoryId(c.id)}
          >
            {c.name}
          </button>
        ))}
      </nav>
      <section className="product-grid">
        {category?.products.map((p) => (
          <button
            key={p.id}
            type="button"
            className={p.soldOut ? 'product sold-out' : 'product'}
            style={{ borderLeftColor: category.colour }}
            disabled={p.soldOut}
          >
            <span className="product-name">{p.name}</span>
            <span className="product-price">
              {p.soldOut ? t('register.soldOut') : formatCents(p.priceCents)}
            </span>
          </button>
        ))}
      </section>
    </div>
  );
}
