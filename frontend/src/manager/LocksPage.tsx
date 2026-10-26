import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { VenueUserLock } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { useSession } from '../session/SessionContext';
import { isOwner } from '../session/roles';
import { useManager } from './context';
import { useList } from './useList';

function locked(until: string | null): boolean {
  return until !== null && new Date(until).getTime() > Date.now();
}

/**
 * Who is locked out at this venue, and until when. Managers unlock PINs early; owners also unlock accounts locked
 * after wrong passwords.
 */
export function LocksPage() {
  const { t } = useTranslation();
  const { me } = useSession();
  const { venueId } = useManager();
  const base = venueId ? `/venues/${venueId}/users` : null;
  const { items, error, setError, reload } = useList<VenueUserLock>(base);
  const owner = isOwner(me);

  async function run(action: () => Promise<unknown>) {
    try {
      await action();
      setError(null);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  const until = (iso: string | null) =>
    locked(iso)
      ? new Date(iso ?? '').toLocaleTimeString('it-IT', { timeStyle: 'short' })
      : t('locks.notLocked');

  return (
    <section>
      <h1>{t('locks.title')}</h1>
      <p>{t('locks.intro')}</p>
      <ErrorBanner error={error} />
      <table className="table">
        <thead>
          <tr>
            <th>{t('common.name')}</th>
            <th>{t('users.memberships')}</th>
            <th>{t('locks.pin')}</th>
            <th>{t('locks.account')}</th>
            <th>{t('common.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((u) => (
            <tr key={u.userId}>
              <td>{u.displayName}</td>
              <td>{u.roles.map((r) => t(`users.roles.${r}`)).join(', ')}</td>
              <td>{until(u.pinLockedUntil)}</td>
              <td>{until(u.lockedUntil)}</td>
              <td className="row-actions">
                {locked(u.pinLockedUntil) && (
                  <button
                    className="btn btn-small"
                    type="button"
                    onClick={() => void run(() => api.post(`${base}/${u.userId}/unlock-pin`))}
                  >
                    {t('locks.unlockPin')}
                  </button>
                )}
                {owner && locked(u.lockedUntil) && (
                  <button
                    className="btn btn-small"
                    type="button"
                    onClick={() =>
                      void run(() => api.post(`/users/${u.userId}/unlock`, { account: true, pin: false }))
                    }
                  >
                    {t('locks.unlockAccount')}
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
