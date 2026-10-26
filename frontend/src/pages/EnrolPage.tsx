import { useEffect, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { api } from '../api/client';
import type { CurrentDevice, Device } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';

export function EnrolPage() {
  const { t } = useTranslation();
  const [current, setCurrent] = useState<CurrentDevice | null>(null);
  const [code, setCode] = useState('');
  const [name, setName] = useState(() => t('enrol.defaultName'));
  const [enrolled, setEnrolled] = useState<Device | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.get<CurrentDevice>('/devices/current').then(setCurrent, setError);
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const result = await api.post<{ device: Device }>('/devices/enrol', { code, name });
      setEnrolled(result.device);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  if (enrolled) {
    return (
      <main className="centered">
        <section className="card narrow">
          <h1>{t('enrol.title')}</h1>
          <p>{t('enrol.done', { name: enrolled.name })}</p>
          <Link className="btn btn-primary" to="/pin">
            {t('enrol.toPin')}
          </Link>
        </section>
      </main>
    );
  }

  return (
    <main className="centered">
      <form className="card narrow" onSubmit={(e) => void submit(e)}>
        <h1>{t('enrol.title')}</h1>
        {current?.enrolled && <p className="notice">{t('enrol.alreadyEnrolled', { name: current.name })}</p>}
        <p>{t('enrol.intro')}</p>
        <label className="field">
          <span>{t('enrol.code')}</span>
          <input
            className="code-input"
            required
            autoCapitalize="characters"
            autoComplete="off"
            maxLength={9}
            value={code}
            onChange={(e) => setCode(e.target.value.toUpperCase())}
          />
        </label>
        <label className="field">
          <span>{t('enrol.name')}</span>
          <input required maxLength={80} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <ErrorBanner error={error} />
        <button className="btn btn-primary" type="submit" disabled={busy}>
          {t('enrol.submit')}
        </button>
        <nav className="links">
          <Link to="/login">{t('pin.managerLink')}</Link>
        </nav>
      </form>
    </main>
  );
}
