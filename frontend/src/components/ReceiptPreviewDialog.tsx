import { useEffect, useState, type CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { ReceiptPreview } from '../api/types';
import { Dialog } from './Dialog';
import { ErrorBanner } from './ErrorBanner';

/**
 * The courtesy receipt on screen, exactly as it prints: the server builds and lays out the print document (same
 * layout, same fixed non-fiscal line); here each line is only drawn in a monospace font on a paper as wide as the
 * printer's columns (48 on 80 mm), double-size lines twice as wide or tall.
 */
export function ReceiptPreviewDialog({
  path,
  number,
  voided = false,
  onClose,
}: {
  /** API path of the preview, e.g. `/register/orders/{id}/receipt-preview`. */
  path: string;
  number: number;
  voided?: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [preview, setPreview] = useState<ReceiptPreview | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let active = true;
    api.get<ReceiptPreview>(path).then(
      (data) => {
        if (active) setPreview(data);
      },
      (e: unknown) => {
        if (active) setError(e);
      },
    );
    return () => {
      active = false;
    };
  }, [path]);

  return (
    <Dialog title={t('receiptPreview.title', { number })} onClose={onClose}>
      <ErrorBanner error={error} />
      {voided && <p className="warning">{t('receiptPreview.voided')}</p>}
      {!preview && !error && <p className="muted">{t('receiptPreview.loading')}</p>}
      {preview && (
        <figure
          className="receipt-paper"
          aria-label={t('receiptPreview.paper')}
          style={{ '--receipt-columns': preview.columns } as CSSProperties}
        >
          {preview.lines.map((line, i) => (
            <div
              key={i}
              className={`receipt-line receipt-${line.size.toLowerCase()}${line.bold ? ' receipt-bold' : ''}`}
            >
              {/* A non-breaking space keeps blank lines one line tall. */}
              <span>{line.text === '' ? ' ' : line.text}</span>
            </div>
          ))}
        </figure>
      )}
    </Dialog>
  );
}
