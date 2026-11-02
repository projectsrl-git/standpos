import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import type { Role } from '../../api/types';
import { ErrorBanner } from '../../components/ErrorBanner';
import { useSession } from '../../session/SessionContext';
import type { SetupStatus } from './useSetupStatus';

const ROLES: Role[] = ['CASHIER', 'MANAGER', 'STOCK_CLERK'];

interface Draft {
  displayName: string;
  venueId: string;
  role: Role;
  pin: string;
  email: string;
  password: string;
}

const empty = (venueId: string, role: Role = 'CASHIER'): Draft => ({
  displayName: '',
  venueId,
  role,
  pin: '',
  email: '',
  password: '',
});

/** Step 4: cashiers and managers per venue, each with a PIN for the registers; managers optionally with a login. */
export function StaffStep({ status }: { status: SetupStatus }) {
  const { t } = useTranslation();
  const { me } = useSession();
  const firstVenue = status.venues[0]?.venue.id ?? '';
  const [draft, setDraft] = useState<Draft>(() => empty(firstVenue));
  const [ownPin, setOwnPin] = useState('');
  const [ownPinSaved, setOwnPinSaved] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [localError, setLocalError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const venueId = draft.venueId || firstVenue;
  const owner = status.users.find((u) => u.id === me?.user?.id);

  async function add(event: FormEvent) {
    event.preventDefault();
    const email = draft.role === 'MANAGER' ? draft.email.trim() : '';
    if (email && draft.password.length < 10) {
      setLocalError(t('setup.staff.passwordNeeded'));
      return;
    }
    setLocalError(null);
    setBusy(true);
    try {
      await api.post('/users', {
        displayName: draft.displayName.trim(),
        email: email || null,
        password: email ? draft.password : null,
        pin: draft.pin || null,
        venueId,
        role: draft.role,
      });
      setDraft(empty(venueId, draft.role));
      setError(null);
      await status.reload();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  async function saveOwnPin(event: FormEvent) {
    event.preventDefault();
    if (!me?.user) return;
    try {
      await api.put(`/users/${me.user.id}`, { pin: ownPin, clearPin: false });
      setOwnPin('');
      setOwnPinSaved(true);
      setError(null);
      await status.reload();
    } catch (e) {
      setError(e);
    }
  }

  if (status.venues.length === 0) {
    return (
      <section className="wizard-step">
        <h2>{t('setup.staff.title')}</h2>
        <p className="warning">{t('setup.needVenue')}</p>
      </section>
    );
  }

  return (
    <section className="wizard-step">
      <h2>{t('setup.staff.title')}</h2>
      <p>{t('setup.staff.intro')}</p>
      <form className="card" onSubmit={(e) => void add(e)}>
        <h3>{t('setup.staff.add')}</h3>
        <label className="field">
          <span>{t('users.displayName')}</span>
          <input
            required
            maxLength={80}
            value={draft.displayName}
            onChange={(e) => setDraft({ ...draft, displayName: e.target.value })}
          />
        </label>
        {status.venues.length > 1 && (
          <label className="field">
            <span>{t('users.venue')}</span>
            <select value={venueId} onChange={(e) => setDraft({ ...draft, venueId: e.target.value })}>
              {status.venues.map((v) => (
                <option key={v.venue.id} value={v.venue.id}>
                  {v.venue.name}
                </option>
              ))}
            </select>
          </label>
        )}
        <fieldset className="choices">
          <legend>{t('users.role')}</legend>
          {ROLES.map((role) => (
            <label key={role} className={draft.role === role ? 'choice selected' : 'choice'}>
              <input
                type="radio"
                name="staff-role"
                value={role}
                checked={draft.role === role}
                onChange={() => setDraft({ ...draft, role })}
              />
              <span>
                <strong>{t(`users.roles.${role}`)}</strong>
                <small>{t(`setup.staff.roleHints.${role}`)}</small>
              </span>
            </label>
          ))}
        </fieldset>
        <label className="field small">
          <span>{t('users.pin')}</span>
          <input
            inputMode="numeric"
            pattern="\d{4,6}"
            required={draft.role === 'CASHIER'}
            value={draft.pin}
            onChange={(e) => setDraft({ ...draft, pin: e.target.value })}
          />
        </label>
        {draft.role === 'MANAGER' && (
          <>
            <p className="muted">{t('setup.staff.managerLogin')}</p>
            <div className="inline-form">
              <label className="field">
                <span>{t('login.email')}</span>
                <input
                  type="email"
                  maxLength={254}
                  autoComplete="off"
                  value={draft.email}
                  onChange={(e) => setDraft({ ...draft, email: e.target.value })}
                />
              </label>
              <label className="field">
                <span>{t('users.password')}</span>
                <input
                  type="password"
                  minLength={10}
                  maxLength={200}
                  autoComplete="new-password"
                  value={draft.password}
                  onChange={(e) => setDraft({ ...draft, password: e.target.value })}
                />
              </label>
            </div>
          </>
        )}
        {localError && (
          <p className="error" role="alert">
            {localError}
          </p>
        )}
        <button className="btn btn-primary" type="submit" disabled={busy}>
          {t('setup.staff.submit')}
        </button>
      </form>
      <ErrorBanner error={error ?? status.error} />
      {status.venues.map((v) => (
        <div key={v.venue.id} className="card wizard-venue">
          <h3>{v.venue.name}</h3>
          {v.staff.length === 0 ? (
            <p className="muted">{t('setup.staff.nobody')}</p>
          ) : (
            <ul className="wizard-list">
              {v.staff.map((u) => (
                <li key={u.id}>
                  <strong>{u.displayName}</strong> ·{' '}
                  {u.memberships
                    .filter((m) => m.venueId === v.venue.id)
                    .map((m) => t(`users.roles.${m.role}`))
                    .join(', ')}{' '}
                  · {u.hasPin ? t('users.hasPin') : t('users.noPin')}
                </li>
              ))}
            </ul>
          )}
        </div>
      ))}
      <form className="card" onSubmit={(e) => void saveOwnPin(e)}>
        <h3>{t('setup.staff.ownPin')}</h3>
        <p className="muted">
          {owner?.hasPin || ownPinSaved ? t('setup.staff.ownPinSet') : t('setup.staff.ownPinHint')}
        </p>
        <div className="inline-form">
          <label className="field small">
            <span>{t('users.newPin')}</span>
            <input
              inputMode="numeric"
              pattern="\d{4,6}"
              required
              value={ownPin}
              onChange={(e) => setOwnPin(e.target.value)}
            />
          </label>
          <button className="btn" type="submit">
            {t('users.setPin')}
          </button>
        </div>
      </form>
    </section>
  );
}
