import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ErrorBanner } from '../../components/ErrorBanner';
import { formatCents } from '../../money';
import { STARTER_CATEGORIES, createStarterCatalog } from './starterCatalog';
import type { SetupStatus } from './useSetupStatus';

/** Step 2 (optional): an example catalogue per venue, to edit or remove from Categorie and Prodotti. */
export function CatalogStep({ status }: { status: SetupStatus }) {
  const { t } = useTranslation();
  const [busyVenue, setBusyVenue] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  async function create(venueId: string) {
    setBusyVenue(venueId);
    try {
      await createStarterCatalog(venueId, t);
      setError(null);
    } catch (e) {
      setError(e);
    } finally {
      setBusyVenue(null);
      await status.reload();
    }
  }

  return (
    <section className="wizard-step">
      <h2>{t('setup.catalog.title')}</h2>
      <p>{t('setup.catalog.intro')}</p>
      <div className="card starter-preview">
        <h3>{t('setup.catalog.preview')}</h3>
        <p className="muted">{t('setup.catalog.routes')}</p>
        <ul className="wizard-list">
          {STARTER_CATEGORIES.map((c) => (
            <li key={c.key}>
              <span className="swatch" style={{ background: c.colour }} />
              <strong>{t(`setup.starter.categories.${c.key}`)}</strong>:{' '}
              {c.products
                .map((p) => `${t(`setup.starter.products.${p.key}`)} ${formatCents(p.priceCents)}`)
                .join(', ')}
            </li>
          ))}
        </ul>
        <p className="muted">{t('setup.catalog.editLater')}</p>
      </div>
      {status.venues.length === 0 && <p className="warning">{t('setup.needVenue')}</p>}
      {status.venues.map((v) => (
        <div key={v.venue.id} className="card wizard-venue">
          <h3>{v.venue.name}</h3>
          {v.categories > 0 ? (
            <p>{t('setup.catalog.hasCatalog', { categories: v.categories, products: v.products })}</p>
          ) : (
            <button
              className="btn btn-primary"
              type="button"
              disabled={busyVenue !== null}
              onClick={() => void create(v.venue.id)}
            >
              {busyVenue === v.venue.id
                ? t('setup.catalog.creating')
                : t('setup.catalog.create', { venue: v.venue.name })}
            </button>
          )}
        </div>
      ))}
      <ErrorBanner error={error ?? status.error} />
    </section>
  );
}
