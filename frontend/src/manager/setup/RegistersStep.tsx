import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import type { DeviceKind, EnrolmentCode } from '../../api/types';
import { ErrorBanner } from '../../components/ErrorBanner';
import { PRINT_AGENT_GUIDE } from './links';
import type { SetupStatus } from './useSetupStatus';

const MAX_REGISTERS = 10;

const time = (iso: string) => new Date(iso).toLocaleTimeString('it-IT', { timeStyle: 'short' });

/** Step 3: enrolment codes for each venue's registers, plus an optional print agent code. */
export function RegistersStep({ status }: { status: SetupStatus }) {
  const { t } = useTranslation();
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [codes, setCodes] = useState<Record<string, EnrolmentCode[]>>({});
  const [agentCodes, setAgentCodes] = useState<Record<string, EnrolmentCode>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const enrolUrl = `${window.location.origin}/enrol`;

  const countOf = (venueId: string) => counts[venueId] ?? 1;
  const setCount = (venueId: string, n: number) =>
    setCounts({ ...counts, [venueId]: Math.min(MAX_REGISTERS, Math.max(1, n)) });

  const code = (venueId: string, kind: DeviceKind) =>
    api.post<EnrolmentCode>(`/venues/${venueId}/enrolment-codes`, { kind });

  async function generateRegisters(venueId: string) {
    setBusy(true);
    try {
      const created: EnrolmentCode[] = [];
      for (let i = 0; i < countOf(venueId); i++) {
        created.push(await code(venueId, 'REGISTER'));
      }
      setCodes({ ...codes, [venueId]: created });
      setError(null);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  async function generateAgent(venueId: string) {
    setBusy(true);
    try {
      setAgentCodes({ ...agentCodes, [venueId]: await code(venueId, 'PRINT_AGENT') });
      setError(null);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="wizard-step">
      <h2>{t('setup.registers.title')}</h2>
      <ol className="instructions">
        <li>
          {t('setup.registers.open')} <strong className="url">{enrolUrl}</strong>
        </li>
        <li>{t('setup.registers.typeCode')}</li>
        <li>{t('setup.registers.expiry')}</li>
        <li>{t('setup.registers.pin')}</li>
      </ol>
      {status.venues.length === 0 && <p className="warning">{t('setup.needVenue')}</p>}
      {status.venues.map((v) => {
        const venueId = v.venue.id;
        const venueCodes = codes[venueId] ?? [];
        const agent = agentCodes[venueId];
        return (
          <div key={venueId} className="card wizard-venue">
            <h3>{v.venue.name}</h3>
            <p>{t('setup.registers.enrolled', { count: v.registers })}</p>
            <div className="inline-form">
              <div className="stepper" role="group" aria-label={t('setup.registers.howMany')}>
                <span>{t('setup.registers.howMany')}</span>
                <button
                  className="btn"
                  type="button"
                  aria-label={t('setup.registers.fewer')}
                  onClick={() => setCount(venueId, countOf(venueId) - 1)}
                >
                  −
                </button>
                <output aria-live="polite">{countOf(venueId)}</output>
                <button
                  className="btn"
                  type="button"
                  aria-label={t('setup.registers.more')}
                  onClick={() => setCount(venueId, countOf(venueId) + 1)}
                >
                  +
                </button>
              </div>
              <button
                className="btn btn-primary"
                type="button"
                disabled={busy}
                onClick={() => void generateRegisters(venueId)}
              >
                {t('setup.registers.generate', { count: countOf(venueId) })}
              </button>
            </div>
            {venueCodes.length > 0 && (
              <ul className="code-list" aria-label={t('setup.registers.codesFor', { venue: v.venue.name })}>
                {venueCodes.map((c, i) => (
                  <li key={c.code}>
                    <span>{t('setup.registers.registerName', { n: v.registers + i + 1 })}</span>
                    <strong>{c.code}</strong>
                    <small>{t('setup.registers.validUntil', { time: time(c.expiresAt) })}</small>
                  </li>
                ))}
              </ul>
            )}
            <div className="agent-box">
              <p>{t('setup.registers.agentIntro', { count: v.agents })}</p>
              {agent ? (
                <ul className="code-list">
                  <li>
                    <span>{t('devices.kinds.PRINT_AGENT')}</span>
                    <strong>{agent.code}</strong>
                    <small>{t('setup.registers.validUntil', { time: time(agent.expiresAt) })}</small>
                  </li>
                </ul>
              ) : (
                <button
                  className="btn"
                  type="button"
                  disabled={busy}
                  onClick={() => void generateAgent(venueId)}
                >
                  {t('setup.registers.agentGenerate')}
                </button>
              )}
              <a href={PRINT_AGENT_GUIDE} target="_blank" rel="noreferrer">
                {t('setup.registers.agentGuide')}
              </a>
            </div>
          </div>
        );
      })}
      <ErrorBanner error={error ?? status.error} />
    </section>
  );
}
