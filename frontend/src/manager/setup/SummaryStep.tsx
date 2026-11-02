import { useTranslation } from 'react-i18next';
import { MANAGER_GUIDE, PRINT_AGENT_GUIDE } from './links';
import type { SetupStatus } from './useSetupStatus';

/** Step 5: what is in place per venue, and what to do next. */
export function SummaryStep({ status }: { status: SetupStatus }) {
  const { t } = useTranslation();
  const enrolUrl = `${window.location.origin}/enrol`;
  return (
    <section className="wizard-step">
      <h2>{t('setup.summary.title')}</h2>
      {status.venues.length === 0 && <p className="warning">{t('setup.needVenue')}</p>}
      {status.venues.map((v) => (
        <div key={v.venue.id} className="card wizard-venue">
          <h3>
            {v.venue.name} · {t(`venues.kinds.${v.venue.kind}`)}
          </h3>
          <ul className="wizard-list">
            <li>{t('setup.summary.catalog', { categories: v.categories, products: v.products })}</li>
            <li>{t('setup.summary.registers', { count: v.registers })}</li>
            <li>{t('setup.summary.agents', { count: v.agents })}</li>
            <li>{t('setup.summary.staff', { count: v.staff.length })}</li>
          </ul>
        </div>
      ))}
      <div className="card">
        <h3>{t('setup.summary.next')}</h3>
        <ol className="instructions">
          <li>
            {t('setup.summary.nextEnrol')} <strong className="url">{enrolUrl}</strong>
          </li>
          <li>{t('setup.summary.nextPin')}</li>
          <li>
            {t('setup.summary.nextPrinter')}{' '}
            <a href={PRINT_AGENT_GUIDE} target="_blank" rel="noreferrer">
              {t('setup.registers.agentGuide')}
            </a>
          </li>
          <li>{t('setup.summary.nextReceipt')}</li>
          <li>{t('setup.summary.nextCatalog')}</li>
          <li>{t('setup.summary.nextFiscal')}</li>
          <li>
            <a href={MANAGER_GUIDE} target="_blank" rel="noreferrer">
              {t('setup.summary.guide')}
            </a>
          </li>
        </ol>
      </div>
    </section>
  );
}
