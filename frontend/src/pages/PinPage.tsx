import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { api } from '../api/client';
import type { Cashier, CurrentDevice } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { useSession } from '../session/SessionContext';

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9'];

export function PinPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { refresh } = useSession();
  const [device, setDevice] = useState<CurrentDevice | null>(null);
  const [cashiers, setCashiers] = useState<Cashier[]>([]);
  const [selected, setSelected] = useState<Cashier | null>(null);
  const [pin, setPin] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api
      .get<CurrentDevice>('/devices/current')
      .then(async (current) => {
        setDevice(current);
        if (current.enrolled && current.kind === 'REGISTER') {
          setCashiers(await api.get<Cashier[]>('/register/cashiers'));
        }
      })
      .catch(setError);
  }, []);

  function press(key: string) {
    setError(null);
    setPin((value) => (value.length < 6 ? value + key : value));
  }

  async function submit() {
    if (!selected || pin.length < 4) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.post('/auth/pin', { userId: selected.userId, pin });
      await refresh();
      void navigate('/register');
    } catch (e) {
      setError(e);
      setPin('');
    } finally {
      setBusy(false);
    }
  }

  if (device && (!device.enrolled || device.kind !== 'REGISTER')) {
    return (
      <main className="centered">
        <section className="card narrow">
          <h1>{t('pin.title')}</h1>
          <p>{t('pin.notEnrolled')}</p>
          <Link className="btn btn-primary" to="/enrol">
            {t('pin.enrolLink')}
          </Link>
          <nav className="links">
            <Link to="/login">{t('pin.managerLink')}</Link>
          </nav>
        </section>
      </main>
    );
  }

  if (!selected) {
    return (
      <main className="centered">
        <section className="card wide">
          <h1>{t('pin.title')}</h1>
          <ErrorBanner error={error} />
          {device && cashiers.length === 0 && <p>{t('pin.noCashiers')}</p>}
          <div className="tile-grid">
            {cashiers.map((cashier) => (
              <button
                key={cashier.userId}
                className="tile"
                type="button"
                onClick={() => setSelected(cashier)}
              >
                {cashier.displayName}
              </button>
            ))}
          </div>
          <nav className="links">
            <Link to="/login">{t('pin.managerLink')}</Link>
          </nav>
        </section>
      </main>
    );
  }

  return (
    <main className="centered">
      <section className="card narrow">
        <h1>{t('pin.enterPin', { name: selected.displayName })}</h1>
        <output
          className="pin-dots"
          aria-live="polite"
          aria-label={t('pin.enterPin', { name: selected.displayName })}
        >
          {'●'.repeat(pin.length) || ' '}
        </output>
        <ErrorBanner error={error} />
        <div className="keypad">
          {KEYS.map((key) => (
            <button key={key} type="button" className="key" onClick={() => press(key)}>
              {key}
            </button>
          ))}
          <button type="button" className="key" onClick={() => setPin((value) => value.slice(0, -1))}>
            {t('pin.delete')}
          </button>
          <button type="button" className="key" onClick={() => press('0')}>
            0
          </button>
          <button
            type="button"
            className="key key-ok"
            disabled={busy || pin.length < 4}
            onClick={() => void submit()}
          >
            {t('pin.submit')}
          </button>
        </div>
        <button
          type="button"
          className="btn"
          onClick={() => {
            setSelected(null);
            setPin('');
            setError(null);
          }}
        >
          {t('pin.other')}
        </button>
      </section>
    </main>
  );
}
