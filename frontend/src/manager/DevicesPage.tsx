import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { Device, DeviceKind, EnrolmentCode } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { useManager } from './context';
import { useList } from './useList';

const KINDS: DeviceKind[] = ['REGISTER', 'KITCHEN', 'PRINT_AGENT'];

export function DevicesPage() {
  const { t } = useTranslation();
  const { venueId } = useManager();
  const base = venueId ? `/venues/${venueId}` : null;
  const { items, error, setError, reload } = useList<Device>(base ? `${base}/devices` : null);
  const [kind, setKind] = useState<DeviceKind>('REGISTER');
  const [code, setCode] = useState<EnrolmentCode | null>(null);

  async function generate() {
    if (!base) return;
    try {
      setCode(await api.post<EnrolmentCode>(`${base}/enrolment-codes`, { kind }));
    } catch (e) {
      setError(e);
    }
  }

  async function rename(device: Device) {
    if (!base) return;
    const name = window.prompt(t('devices.rename'), device.name);
    if (!name) return;
    try {
      await api.put(`${base}/devices/${device.id}`, { name });
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  async function revoke(device: Device) {
    if (!base || !window.confirm(t('devices.confirmRevoke', { name: device.name }))) return;
    try {
      await api.post(`${base}/devices/${device.id}/revoke`);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  const time = (iso: string | null) =>
    iso
      ? new Date(iso).toLocaleString('it-IT', { dateStyle: 'short', timeStyle: 'short' })
      : t('devices.never');

  return (
    <section>
      <h1>{t('devices.title')}</h1>
      <div className="card inline-form">
        <label className="field">
          <span>{t('devices.kind')}</span>
          <select value={kind} onChange={(e) => setKind(e.target.value as DeviceKind)}>
            {KINDS.map((k) => (
              <option key={k} value={k}>
                {t(`devices.kinds.${k}`)}
              </option>
            ))}
          </select>
        </label>
        <button className="btn btn-primary" type="button" onClick={() => void generate()}>
          {t('devices.generate')}
        </button>
        {code && (
          <div className="enrolment-code" role="status">
            <strong>{t('devices.code', { code: code.code })}</strong>
            <span>
              {t('devices.expires', {
                time: new Date(code.expiresAt).toLocaleTimeString('it-IT', { timeStyle: 'short' }),
              })}
            </span>
          </div>
        )}
      </div>
      <ErrorBanner error={error} />
      <table className="table">
        <thead>
          <tr>
            <th>{t('common.name')}</th>
            <th>{t('devices.kind')}</th>
            <th>{t('devices.lastSeen')}</th>
            <th>{t('devices.status')}</th>
            <th>{t('common.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((d) => (
            <tr key={d.id} className={d.revokedAt ? 'muted' : undefined}>
              <td>{d.name}</td>
              <td>{t(`devices.kinds.${d.kind}`)}</td>
              <td>{time(d.lastSeenAt)}</td>
              <td>{d.revokedAt ? t('devices.revoked') : t('devices.active')}</td>
              <td className="row-actions">
                {!d.revokedAt && (
                  <>
                    <button className="btn" type="button" onClick={() => void rename(d)}>
                      {t('devices.rename')}
                    </button>
                    <button className="btn btn-danger" type="button" onClick={() => void revoke(d)}>
                      {t('devices.revoke')}
                    </button>
                  </>
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
