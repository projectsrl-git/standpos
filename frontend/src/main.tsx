import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { registerSW } from 'virtual:pwa-register';
import { App } from './App';
import './i18n';
import { SessionProvider } from './session/SessionContext';
import './styles.css';

// Service worker for the app shell only; API calls always go to the network.
registerSW({ immediate: true });

const root = document.getElementById('root');
if (!root) {
  throw new Error('Missing #root element');
}

createRoot(root).render(
  <StrictMode>
    <BrowserRouter>
      <SessionProvider>
        <App />
      </SessionProvider>
    </BrowserRouter>
  </StrictMode>,
);
