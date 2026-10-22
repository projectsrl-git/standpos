import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { Role, User, Venue } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { useManager } from './context';
import { useList } from './useList';

const ROLES: Role[] = ['OWNER', 'MANAGER', 'STOCK_CLERK', 'CASHIER', 'KITCHEN'];

interface Draft {
  displayName: string;
  email: string;
  password: string;
  pin: string;
  role: Role;
  venueId: string;
}

export function UsersPage() {
  const { t } = useTranslation();
  const { venues, venueId } = useManager();
  const { items, error, setError, reload } = useList<User>('/users');
  const [draft, setDraft] = useState<Draft>({
    displayName: '',
    email: '',
    password: '',
    pin: '',
    role: 'CASHIER',
    venueId: venueId ?? '',
  });
  const [roleToAdd, setRoleToAdd] = useState<{ role: Role; venueId: string }>({
    role: 'CASHIER',
    venueId: venueId ?? '',
  });

  const venueName = (id: string | null) =>
    id === null ? t('users.allVenues') : (venues.find((v) => v.id === id)?.name ?? id);
  const membershipVenue = (role: Role, chosen: string) => (role === 'OWNER' ? null : chosen || venueId);

  async function run(action: () => Promise<unknown>) {
    try {
      await action();
      setError(null);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  function create(event: FormEvent) {
    event.preventDefault();
    void run(async () => {
      await api.post('/users', {
        displayName: draft.displayName,
        email: draft.email || null,
        password: draft.password || null,
        pin: draft.pin || null,
        role: draft.role,
        venueId: membershipVenue(draft.role, draft.venueId),
      });
      setDraft({ ...draft, displayName: '', email: '', password: '', pin: '' });
    });
  }

  function setPin(user: User) {
    const pin = window.prompt(t('users.newPin'));
    if (!pin) return;
    void run(() => api.put(`/users/${user.id}`, { pin }));
  }

  return (
    <section>
      <h1>{t('users.title')}</h1>
      <form className="card inline-form" onSubmit={create}>
        <h2>{t('users.new')}</h2>
        <label className="field">
          <span>{t('users.displayName')}</span>
          <input
            required
            maxLength={80}
            value={draft.displayName}
            onChange={(e) => setDraft({ ...draft, displayName: e.target.value })}
          />
        </label>
        <label className="field">
          <span>{t('users.email')}</span>
          <input
            type="email"
            value={draft.email}
            onChange={(e) => setDraft({ ...draft, email: e.target.value })}
          />
        </label>
        <label className="field">
          <span>{t('users.password')}</span>
          <input
            type="password"
            autoComplete="new-password"
            minLength={10}
            value={draft.password}
            onChange={(e) => setDraft({ ...draft, password: e.target.value })}
          />
        </label>
        <label className="field small">
          <span>{t('users.pin')}</span>
          <input
            inputMode="numeric"
            pattern="\d{4,6}"
            value={draft.pin}
            onChange={(e) => setDraft({ ...draft, pin: e.target.value })}
          />
        </label>
        <RolePicker
          venues={venues}
          defaultVenueId={venueId}
          role={draft.role}
          venueId={draft.venueId}
          onChange={(role, venue) => setDraft({ ...draft, role, venueId: venue })}
        />
        <button className="btn btn-primary" type="submit">
          {t('common.create')}
        </button>
      </form>
      <ErrorBanner error={error} />
      <table className="table">
        <thead>
          <tr>
            <th>{t('users.displayName')}</th>
            <th>{t('login.email')}</th>
            <th>{t('users.pin')}</th>
            <th>{t('users.memberships')}</th>
            <th>{t('common.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((user) => (
            <tr key={user.id}>
              <td>{user.displayName}</td>
              <td>{user.email}</td>
              <td>{user.hasPin ? t('users.hasPin') : t('users.noPin')}</td>
              <td>
                <ul className="plain">
                  {user.memberships.map((m) => (
                    <li key={m.id}>
                      {t(`users.roles.${m.role}`)} · {venueName(m.venueId)}{' '}
                      <button
                        className="btn btn-small"
                        type="button"
                        onClick={() => void run(() => api.delete(`/users/${user.id}/memberships/${m.id}`))}
                      >
                        {t('users.removeRole')}
                      </button>
                    </li>
                  ))}
                </ul>
              </td>
              <td className="row-actions">
                <button className="btn" type="button" onClick={() => setPin(user)}>
                  {t('users.setPin')}
                </button>
                {user.hasPin && (
                  <button
                    className="btn"
                    type="button"
                    onClick={() => void run(() => api.put(`/users/${user.id}`, { clearPin: true }))}
                  >
                    {t('users.clearPin')}
                  </button>
                )}
                <button
                  className="btn"
                  type="button"
                  onClick={() =>
                    void run(() =>
                      api.post(`/users/${user.id}/memberships`, {
                        role: roleToAdd.role,
                        venueId: membershipVenue(roleToAdd.role, roleToAdd.venueId),
                      }),
                    )
                  }
                >
                  {t('users.addRole')}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="card inline-form">
        <h2>{t('users.addRole')}</h2>
        <RolePicker
          venues={venues}
          defaultVenueId={venueId}
          role={roleToAdd.role}
          venueId={roleToAdd.venueId}
          onChange={(role, venue) => setRoleToAdd({ role, venueId: venue })}
        />
      </div>
    </section>
  );
}

function RolePicker(props: {
  role: Role;
  venueId: string;
  venues: Venue[];
  defaultVenueId: string | null;
  onChange: (role: Role, venueId: string) => void;
}) {
  const { t } = useTranslation();
  const { venues, defaultVenueId } = props;
  return (
    <>
      <label className="field">
        <span>{t('users.role')}</span>
        <select value={props.role} onChange={(e) => props.onChange(e.target.value as Role, props.venueId)}>
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {t(`users.roles.${r}`)}
            </option>
          ))}
        </select>
      </label>
      {props.role !== 'OWNER' && (
        <label className="field">
          <span>{t('users.venue')}</span>
          <select
            value={props.venueId || defaultVenueId || ''}
            onChange={(e) => props.onChange(props.role, e.target.value)}
          >
            {venues.map((v) => (
              <option key={v.id} value={v.id}>
                {v.name}
              </option>
            ))}
          </select>
        </label>
      )}
    </>
  );
}
