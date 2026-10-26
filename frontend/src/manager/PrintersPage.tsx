import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { Device, Printer } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { useManager } from './context';
import { useList } from './useList';

interface Draft {
  agentId: string;
  name: string;
  windowsName: string;
  widthMm: number;
  codePage: string;
  active: boolean;
}

const CODE_PAGES = ['CP858', 'CP850', 'CP437', 'CP1252'];

/**
 * Printers of the venue, each driven by a print agent (Windows printer name exactly as Windows shows it), the link
 * from each register to its printer, and a test page.
 */
export function PrintersPage() {
  const { t } = useTranslation();
  const { venueId } = useManager();
  const base = venueId ? `/venues/${venueId}` : null;
  const { items, error, setError, reload } = useList<Printer>(base ? `${base}/printers` : null);
  const devices = useList<Device>(base ? `${base}/devices` : null);
  const agents = devices.items.filter((d) => d.kind === 'PRINT_AGENT' && !d.revokedAt);
  const registers = devices.items.filter((d) => d.kind === 'REGISTER' && !d.revokedAt);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function edit(printer: Printer | null) {
    setEditingId(printer?.id ?? null);
    setDraft(
      printer
        ? { ...printer }
        : {
            agentId: agents[0]?.id ?? '',
            name: 'POS80',
            windowsName: '',
            widthMm: 80,
            codePage: 'CP858',
            active: true,
          },
    );
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!base || !draft) return;
    try {
      if (editingId) {
        await api.put(`${base}/printers/${editingId}`, draft);
      } else {
        await api.post(`${base}/printers`, draft);
      }
      setDraft(null);
      setError(null);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  async function test(printer: Printer) {
    if (!base) return;
    try {
      await api.post(`${base}/printers/${printer.id}/test`);
      setNotice(t('printers.testQueued', { name: printer.name }));
    } catch (e) {
      setError(e);
    }
  }

  async function remove(printer: Printer) {
    if (!base || !window.confirm(t('common.confirmDelete', { name: printer.name }))) return;
    try {
      await api.delete(`${base}/printers/${printer.id}`);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  async function link(register: Device, printerId: string) {
    if (!base) return;
    try {
      await api.put(`${base}/devices/${register.id}/printer`, { printerId: printerId || null });
      await devices.reload();
    } catch (e) {
      setError(e);
    }
  }

  const suggestions = draft ? (items.find((p) => p.agentId === draft.agentId)?.localPrinters ?? []) : [];

  return (
    <section>
      <h1>{t('printers.title')}</h1>
      <p>{t('printers.intro')}</p>
      {agents.length === 0 && <p className="notice">{t('printers.noAgent')}</p>}
      {!draft && agents.length > 0 && (
        <button className="btn btn-primary" type="button" onClick={() => edit(null)}>
          {t('printers.new')}
        </button>
      )}
      {draft && (
        <form className="card inline-form" onSubmit={(e) => void save(e)}>
          <h2>{editingId ? t('common.edit') : t('printers.new')}</h2>
          <label className="field">
            <span>{t('common.name')}</span>
            <input
              required
              maxLength={60}
              value={draft.name}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            />
          </label>
          <label className="field">
            <span>{t('printers.agent')}</span>
            <select value={draft.agentId} onChange={(e) => setDraft({ ...draft, agentId: e.target.value })}>
              {agents.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{t('printers.windowsName')}</span>
            <input
              required
              list="local-printers"
              maxLength={200}
              value={draft.windowsName}
              onChange={(e) => setDraft({ ...draft, windowsName: e.target.value })}
            />
            <datalist id="local-printers">
              {suggestions.map((name) => (
                <option key={name} value={name} />
              ))}
            </datalist>
          </label>
          <label className="field">
            <span>{t('printers.width')}</span>
            <select
              value={draft.widthMm}
              onChange={(e) => setDraft({ ...draft, widthMm: Number(e.target.value) })}
            >
              <option value={80}>80 mm</option>
              <option value={58}>58 mm</option>
            </select>
          </label>
          <label className="field">
            <span>{t('printers.codePage')}</span>
            <select value={draft.codePage} onChange={(e) => setDraft({ ...draft, codePage: e.target.value })}>
              {CODE_PAGES.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </label>
          <label className="check">
            <input
              type="checkbox"
              checked={draft.active}
              onChange={(e) => setDraft({ ...draft, active: e.target.checked })}
            />
            <span>{t('printers.active')}</span>
          </label>
          <button className="btn btn-primary" type="submit">
            {t('common.save')}
          </button>
          <button className="btn" type="button" onClick={() => setDraft(null)}>
            {t('common.cancel')}
          </button>
        </form>
      )}
      {notice && (
        <p className="notice" role="status">
          {notice}
        </p>
      )}
      <ErrorBanner error={error ?? devices.error} />
      <table className="table">
        <thead>
          <tr>
            <th>{t('common.name')}</th>
            <th>{t('printers.agent')}</th>
            <th>{t('printers.windowsName')}</th>
            <th>{t('printers.width')}</th>
            <th>{t('printers.status')}</th>
            <th>{t('common.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((p) => (
            <tr key={p.id} className={p.active ? undefined : 'muted'}>
              <td>{p.name}</td>
              <td>{p.agentName}</td>
              <td>{p.windowsName}</td>
              <td>{p.widthMm} mm</td>
              <td>
                <span className={p.online ? 'badge' : 'badge badge-danger'}>
                  {p.online ? t('printers.online') : t('printers.offline')}
                </span>
              </td>
              <td className="row-actions">
                <button className="btn btn-small" type="button" onClick={() => void test(p)}>
                  {t('printers.test')}
                </button>
                <button className="btn btn-small" type="button" onClick={() => edit(p)}>
                  {t('common.edit')}
                </button>
                <button className="btn btn-small btn-danger" type="button" onClick={() => void remove(p)}>
                  {t('common.delete')}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {items.length === 0 && <p>{t('common.empty')}</p>}

      <h2 className="section-title">{t('printers.registers')}</h2>
      <table className="table">
        <thead>
          <tr>
            <th>{t('printers.register')}</th>
            <th>{t('printers.printer')}</th>
          </tr>
        </thead>
        <tbody>
          {registers.map((r) => (
            <tr key={r.id}>
              <td>{r.name}</td>
              <td>
                <select
                  aria-label={t('printers.printerOf', { name: r.name })}
                  value={r.printerId ?? ''}
                  onChange={(e) => void link(r, e.target.value)}
                >
                  <option value="">{t('common.none')}</option>
                  {items.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </select>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
