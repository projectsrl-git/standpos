import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { api } from '../api/client';
import { ErrorBanner } from '../components/ErrorBanner';
import { useSession } from '../session/SessionContext';
import { useSignupAvailable } from '../session/signup';

interface Draft {
  organisationName: string;
  ownerName: string;
  email: string;
  password: string;
  confirmPassword: string;
  nonProfit: boolean;
  nonFiscalAcknowledged: boolean;
}

const EMPTY: Draft = {
  organisationName: '',
  ownerName: '',
  email: '',
  password: '',
  confirmPassword: '',
  nonProfit: false,
  nonFiscalAcknowledged: false,
};

/** "Crea la tua associazione": a new tenant and its owner, then the setup wizard. */
export function SignupPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { refresh } = useSession();
  const available = useSignupAvailable();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [error, setError] = useState<unknown>(null);
  const [mismatch, setMismatch] = useState(false);
  const [refused, setRefused] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (draft.password !== draft.confirmPassword) {
      setMismatch(true);
      return;
    }
    setMismatch(false);
    setRefused(false);
    setError(null);
    setBusy(true);
    try {
      await api.post('/auth/signup', {
        organisationName: draft.organisationName,
        ownerName: draft.ownerName,
        email: draft.email,
        password: draft.password,
        nonProfit: draft.nonProfit,
        nonFiscalAcknowledged: draft.nonFiscalAcknowledged,
      });
      // The answer is the same whether or not the email was already registered; only a new owner is logged in.
      const me = await refresh();
      if (me?.kind === 'USER') {
        void navigate('/manager/setup');
      } else {
        setRefused(true);
      }
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  if (available === null) {
    return <p className="centered">{t('app.loading')}</p>;
  }
  if (!available) {
    return (
      <main className="centered">
        <div className="card narrow">
          <h1>{t('signup.title')}</h1>
          <p>{t('signup.unavailable')}</p>
          <Link to="/login">{t('signup.loginLink')}</Link>
        </div>
      </main>
    );
  }

  return (
    <main className="centered">
      <form className="card narrow" onSubmit={(e) => void submit(e)}>
        <h1>{t('signup.title')}</h1>
        <p className="muted">{t('signup.intro')}</p>
        <label className="field">
          <span>{t('signup.organisationName')}</span>
          <input
            required
            maxLength={120}
            autoComplete="organization"
            value={draft.organisationName}
            onChange={(e) => setDraft({ ...draft, organisationName: e.target.value })}
          />
        </label>
        <label className="field">
          <span>{t('signup.ownerName')}</span>
          <input
            required
            maxLength={80}
            autoComplete="name"
            value={draft.ownerName}
            onChange={(e) => setDraft({ ...draft, ownerName: e.target.value })}
          />
        </label>
        <label className="field">
          <span>{t('signup.email')}</span>
          <input
            type="email"
            required
            maxLength={254}
            autoComplete="username"
            value={draft.email}
            onChange={(e) => setDraft({ ...draft, email: e.target.value })}
          />
        </label>
        <label className="field">
          <span>{t('signup.password')}</span>
          <input
            type="password"
            required
            minLength={10}
            maxLength={200}
            autoComplete="new-password"
            value={draft.password}
            onChange={(e) => setDraft({ ...draft, password: e.target.value })}
          />
        </label>
        <label className="field">
          <span>{t('signup.confirmPassword')}</span>
          <input
            type="password"
            required
            minLength={10}
            maxLength={200}
            autoComplete="new-password"
            value={draft.confirmPassword}
            onChange={(e) => setDraft({ ...draft, confirmPassword: e.target.value })}
          />
        </label>
        <fieldset className="declarations">
          <legend>{t('signup.declarations')}</legend>
          <label className="check">
            <input
              type="checkbox"
              required
              checked={draft.nonProfit}
              onChange={(e) => setDraft({ ...draft, nonProfit: e.target.checked })}
            />
            <span>{t('signup.nonProfit')}</span>
          </label>
          <label className="check">
            <input
              type="checkbox"
              required
              checked={draft.nonFiscalAcknowledged}
              onChange={(e) => setDraft({ ...draft, nonFiscalAcknowledged: e.target.checked })}
            />
            <span>{t('signup.nonFiscal')}</span>
          </label>
        </fieldset>
        {mismatch && (
          <p className="error" role="alert">
            {t('signup.passwordMismatch')}
          </p>
        )}
        {refused && (
          <p className="warning" role="alert">
            {t('signup.refused')}
          </p>
        )}
        <ErrorBanner error={error} />
        <button className="btn btn-primary" type="submit" disabled={busy}>
          {t('signup.submit')}
        </button>
        <nav className="links">
          <Link to="/login">{t('signup.loginLink')}</Link>
        </nav>
      </form>
    </main>
  );
}
