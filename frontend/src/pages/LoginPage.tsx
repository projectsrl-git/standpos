import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { api } from '../api/client';
import type { Me } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { useSession } from '../session/SessionContext';

export function LoginPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { refresh } = useSession();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post<Me>('/auth/login', { email, password });
      await refresh();
      void navigate('/manager');
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="centered">
      <form className="card narrow" onSubmit={(e) => void submit(e)}>
        <h1>{t('login.title')}</h1>
        <label className="field">
          <span>{t('login.email')}</span>
          <input
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </label>
        <label className="field">
          <span>{t('login.password')}</span>
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        <ErrorBanner error={error} />
        <button className="btn btn-primary" type="submit" disabled={busy}>
          {t('login.submit')}
        </button>
        <nav className="links">
          <Link to="/pin">{t('login.pinLink')}</Link>
          <Link to="/enrol">{t('login.enrolLink')}</Link>
        </nav>
      </form>
    </main>
  );
}
