import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, useNavigate } from 'react-router';
import { useSession } from '../../session/SessionContext';
import { ErrorBanner } from '../../components/ErrorBanner';
import { isOwner } from '../../session/roles';
import { useManager } from '../context';
import { CatalogStep } from './CatalogStep';
import { RegistersStep } from './RegistersStep';
import { StaffStep } from './StaffStep';
import { SummaryStep } from './SummaryStep';
import { useSetupStatus, type SetupStatus } from './useSetupStatus';
import { VenuesStep } from './VenuesStep';

const STEPS = ['venues', 'catalog', 'registers', 'staff', 'summary'] as const;
type Step = (typeof STEPS)[number];

function readStep(key: string): number {
  try {
    const stored = Number(localStorage.getItem(key));
    return Number.isInteger(stored) && stored >= 0 && stored < STEPS.length ? stored : 0;
  } catch {
    return 0;
  }
}

function writeStep(key: string, step: number | null) {
  try {
    if (step === null) localStorage.removeItem(key);
    else localStorage.setItem(key, String(step));
  } catch {
    // Storage unavailable: the wizard restarts from its first step next time.
  }
}

function isDone(step: Step, status: SetupStatus): boolean {
  switch (step) {
    case 'venues':
      return status.venues.length > 0;
    case 'catalog':
      return status.venues.some((v) => v.categories > 0);
    case 'registers':
      return status.venues.some((v) => v.registers > 0);
    case 'staff':
      return status.venues.some((v) => v.staff.length > 0);
    case 'summary':
      return false;
  }
}

/** The guided setup, owners only: shown when the tenant has no venue yet and reachable later from the tabs. */
export function SetupPage() {
  const { me, loading } = useSession();
  if (loading) return null;
  if (!isOwner(me)) return <Navigate to="/manager" replace />;
  return <SetupWizard storageKey={`standpos.setup.step.${me?.tenant?.id ?? ''}`} />;
}

function SetupWizard({ storageKey }: { storageKey: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { venues } = useManager();
  const status = useSetupStatus(venues);
  const [index, setIndex] = useState(() => readStep(storageKey));
  const step = STEPS[index] ?? 'venues';

  function go(next: number) {
    setIndex(next);
    writeStep(storageKey, next);
    window.scrollTo?.({ top: 0 });
  }

  function finish() {
    writeStep(storageKey, null);
    void navigate('/manager');
  }

  const last = index === STEPS.length - 1;
  return (
    <section className="wizard">
      <h1>{t('setup.title')}</h1>
      <p className="muted">{t('setup.intro')}</p>
      <ol className="wizard-steps">
        {STEPS.map((s, i) => (
          <li key={s}>
            <button
              type="button"
              className={i === index ? 'active' : undefined}
              aria-current={i === index ? 'step' : undefined}
              onClick={() => go(i)}
            >
              <span className="wizard-number">{status.loaded && isDone(s, status) ? '✓' : i + 1}</span>
              {t(`setup.steps.${s}`)}
            </button>
          </li>
        ))}
      </ol>
      {step === 'venues' && <VenuesStep />}
      {step !== 'venues' && !status.loaded && <ErrorBanner error={status.error} />}
      {step !== 'venues' && !status.loaded && !status.error && <p>{t('app.loading')}</p>}
      {step === 'catalog' && status.loaded && <CatalogStep status={status} />}
      {step === 'registers' && status.loaded && <RegistersStep status={status} />}
      {step === 'staff' && status.loaded && <StaffStep status={status} />}
      {step === 'summary' && status.loaded && <SummaryStep status={status} />}
      <div className="wizard-footer">
        {index > 0 && (
          <button className="btn btn-large" type="button" onClick={() => go(index - 1)}>
            {t('common.back')}
          </button>
        )}
        {last ? (
          <button className="btn btn-primary btn-large" type="button" onClick={finish}>
            {t('setup.finish')}
          </button>
        ) : (
          <button className="btn btn-primary btn-large" type="button" onClick={() => go(index + 1)}>
            {isDone(step, status) ? t('setup.next') : t('setup.skip')}
          </button>
        )}
      </div>
    </section>
  );
}
