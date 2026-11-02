import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import type { Venue } from '../../api/types';
import { ErrorBanner } from '../../components/ErrorBanner';
import { useManager } from '../context';

interface Draft {
  name: string;
  kind: Venue['kind'];
  timeZone: string;
  dayChangeTime: string;
}

const EMPTY: Draft = { name: '', kind: 'PERMANENT', timeZone: 'Europe/Rome', dayChangeTime: '04:00' };

/** Step 1: one or more venues (an all-year bar, a festival), each with its time zone and day change. */
export function VenuesStep() {
  const { t } = useTranslation();
  const { venues, reloadVenues } = useManager();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function add(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      await api.post('/venues', {
        ...draft,
        name: draft.name.trim(),
        receiptHeader: null,
        receiptFooter: null,
        active: true,
        allowNegativeStock: false,
      });
      setDraft(EMPTY);
      setError(null);
      await reloadVenues();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="wizard-step">
      <h2>{t('setup.venues.title')}</h2>
      <p>{t('setup.venues.intro')}</p>
      {venues.length > 0 && (
        <ul className="wizard-list">
          {venues.map((v) => (
            <li key={v.id}>
              <strong>{v.name}</strong> · {t(`venues.kinds.${v.kind}`)} ·{' '}
              {t('setup.venues.dayChangeAt', { time: v.dayChangeTime.slice(0, 5) })}
            </li>
          ))}
        </ul>
      )}
      <form className="card" onSubmit={(e) => void add(e)}>
        <h3>{venues.length === 0 ? t('setup.venues.first') : t('setup.venues.another')}</h3>
        <label className="field">
          <span>{t('setup.venues.name')}</span>
          <input
            required
            maxLength={80}
            value={draft.name}
            onChange={(e) => setDraft({ ...draft, name: e.target.value })}
          />
        </label>
        <fieldset className="choices">
          <legend>{t('venues.kind')}</legend>
          {(['PERMANENT', 'EVENT'] as const).map((kind) => (
            <label key={kind} className={draft.kind === kind ? 'choice selected' : 'choice'}>
              <input
                type="radio"
                name="venue-kind"
                value={kind}
                checked={draft.kind === kind}
                onChange={() => setDraft({ ...draft, kind })}
              />
              <span>
                <strong>{t(`venues.kinds.${kind}`)}</strong>
                <small>{t(`setup.venues.kindHints.${kind}`)}</small>
              </span>
            </label>
          ))}
        </fieldset>
        <div className="inline-form">
          <label className="field">
            <span>{t('venues.timeZone')}</span>
            <input
              required
              value={draft.timeZone}
              onChange={(e) => setDraft({ ...draft, timeZone: e.target.value })}
            />
          </label>
          <label className="field small">
            <span>{t('venues.dayChangeTime')}</span>
            <input
              type="time"
              required
              value={draft.dayChangeTime}
              onChange={(e) => setDraft({ ...draft, dayChangeTime: e.target.value })}
            />
          </label>
        </div>
        <p className="muted">{t('setup.venues.dayChangeHint')}</p>
        <ErrorBanner error={error} />
        <button className="btn btn-primary" type="submit" disabled={busy}>
          {t('setup.venues.add')}
        </button>
      </form>
    </section>
  );
}
