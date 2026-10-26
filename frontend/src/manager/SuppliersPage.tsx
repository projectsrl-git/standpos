import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { Supplier } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { useList } from './useList';

interface Draft {
  name: string;
  contactName: string;
  phone: string;
  email: string;
  notes: string;
  active: boolean;
}

const EMPTY: Draft = { name: '', contactName: '', phone: '', email: '', notes: '', active: true };

/** Suppliers are shared by every venue of the organisation. */
export function SuppliersPage() {
  const { t } = useTranslation();
  const { items, error, setError, reload } = useList<Supplier>('/suppliers');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);

  function edit(s: Supplier | null) {
    setEditingId(s?.id ?? null);
    setDraft(
      s
        ? {
            name: s.name,
            contactName: s.contactName ?? '',
            phone: s.phone ?? '',
            email: s.email ?? '',
            notes: s.notes ?? '',
            active: s.active,
          }
        : EMPTY,
    );
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    const body = {
      ...draft,
      contactName: draft.contactName || null,
      phone: draft.phone || null,
      email: draft.email || null,
      notes: draft.notes || null,
    };
    try {
      if (editingId) {
        await api.put(`/suppliers/${editingId}`, body);
      } else {
        await api.post('/suppliers', body);
      }
      edit(null);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  async function remove(s: Supplier) {
    if (!window.confirm(t('common.confirmDelete', { name: s.name }))) return;
    try {
      await api.delete(`/suppliers/${s.id}`);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  const field = (key: keyof Omit<Draft, 'active'>, label: string, type = 'text') => (
    <label className="field">
      <span>{label}</span>
      <input
        type={type}
        required={key === 'name'}
        value={draft[key]}
        onChange={(e) => setDraft({ ...draft, [key]: e.target.value })}
      />
    </label>
  );

  return (
    <section>
      <h1>{t('suppliers.title')}</h1>
      <form className="card inline-form" onSubmit={(e) => void save(e)}>
        <h2>{editingId ? t('common.edit') : t('suppliers.new')}</h2>
        {field('name', t('common.name'))}
        {field('contactName', t('suppliers.contactName'))}
        {field('phone', t('suppliers.phone'), 'tel')}
        {field('email', t('suppliers.email'), 'email')}
        {field('notes', t('suppliers.notes'))}
        <label className="check">
          <input
            type="checkbox"
            checked={draft.active}
            onChange={(e) => setDraft({ ...draft, active: e.target.checked })}
          />
          <span>{t('suppliers.active')}</span>
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
            <th>{t('suppliers.contactName')}</th>
            <th>{t('suppliers.phone')}</th>
            <th>{t('suppliers.email')}</th>
            <th>{t('common.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((s) => (
            <tr key={s.id} className={s.active ? undefined : 'muted'}>
              <td>{s.name}</td>
              <td>{s.contactName}</td>
              <td>{s.phone}</td>
              <td>{s.email}</td>
              <td className="row-actions">
                <button className="btn" type="button" onClick={() => edit(s)}>
                  {t('common.edit')}
                </button>
                <button className="btn btn-danger" type="button" onClick={() => void remove(s)}>
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
