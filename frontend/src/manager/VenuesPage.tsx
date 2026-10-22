import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { Venue } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { useManager } from './context';

interface Draft {
  name: string;
  kind: Venue['kind'];
  timeZone: string;
  dayChangeTime: string;
  receiptHeader: string;
  receiptFooter: string;
  active: boolean;
}

const EMPTY: Draft = {
  name: '',
  kind: 'PERMANENT',
  timeZone: 'Europe/Rome',
  dayChangeTime: '04:00',
  receiptHeader: '',
  receiptFooter: '',
  active: true,
};

export function VenuesPage() {
  const { t } = useTranslation();
  const { venues, reloadVenues } = useManager();
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [error, setError] = useState<unknown>(null);

  function edit(venue: Venue | null) {
    setEditingId(venue?.id ?? null);
    setDraft(
      venue
        ? {
            ...venue,
            dayChangeTime: venue.dayChangeTime.slice(0, 5),
            receiptHeader: venue.receiptHeader ?? '',
            receiptFooter: venue.receiptFooter ?? '',
          }
        : EMPTY,
    );
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    const body = {
      ...draft,
      receiptHeader: draft.receiptHeader || null,
      receiptFooter: draft.receiptFooter || null,
    };
    try {
      if (editingId) {
        await api.put(`/venues/${editingId}`, body);
      } else {
        await api.post('/venues', body);
      }
      edit(null);
      setError(null);
      await reloadVenues();
    } catch (e) {
      setError(e);
    }
  }

  return (
    <section>
      <h1>{t('venues.title')}</h1>
      <form className="card inline-form" onSubmit={(e) => void save(e)}>
        <h2>{editingId ? t('common.edit') : t('venues.new')}</h2>
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
          <span>{t('venues.kind')}</span>
          <select
            value={draft.kind}
            onChange={(e) => setDraft({ ...draft, kind: e.target.value as Venue['kind'] })}
          >
            <option value="PERMANENT">{t('venues.kinds.PERMANENT')}</option>
            <option value="EVENT">{t('venues.kinds.EVENT')}</option>
          </select>
        </label>
        <label className="field">
          <span>{t('venues.timeZone')}</span>
          <input value={draft.timeZone} onChange={(e) => setDraft({ ...draft, timeZone: e.target.value })} />
        </label>
        <label className="field small">
          <span>{t('venues.dayChangeTime')}</span>
          <input
            type="time"
            value={draft.dayChangeTime}
            onChange={(e) => setDraft({ ...draft, dayChangeTime: e.target.value })}
          />
        </label>
        <label className="field">
          <span>{t('venues.receiptHeader')}</span>
          <input
            maxLength={500}
            value={draft.receiptHeader}
            onChange={(e) => setDraft({ ...draft, receiptHeader: e.target.value })}
          />
        </label>
        <label className="field">
          <span>{t('venues.receiptFooter')}</span>
          <input
            maxLength={500}
            value={draft.receiptFooter}
            onChange={(e) => setDraft({ ...draft, receiptFooter: e.target.value })}
          />
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={draft.active}
            onChange={(e) => setDraft({ ...draft, active: e.target.checked })}
          />
          <span>{t('venues.active')}</span>
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
            <th>{t('venues.kind')}</th>
            <th>{t('venues.dayChangeTime')}</th>
            <th>{t('venues.active')}</th>
            <th>{t('common.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {venues.map((v) => (
            <tr key={v.id}>
              <td>{v.name}</td>
              <td>{t(`venues.kinds.${v.kind}`)}</td>
              <td>{v.dayChangeTime.slice(0, 5)}</td>
              <td>{v.active ? t('common.yes') : t('common.no')}</td>
              <td className="row-actions">
                <button className="btn" type="button" onClick={() => edit(v)}>
                  {t('common.edit')}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
