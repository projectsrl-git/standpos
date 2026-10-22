# 0001 — Frontend: React SPA per tutta l'interfaccia

- Stato: accettata
- Data: 2026-10-22
- Fase: 0

## Contesto

StandPOS ha due interfacce molto diverse: la cassa, usata da volontari su touchscreen e tablet durante il servizio, e l'area gestione (locali, utenti, dispositivi, listino). Le alternative considerate erano una single-page application React + TypeScript per tutto, oppure pagine server-side con Thymeleaf + htmx servite da Spring Boot.

## Decisione

Tutta l'interfaccia è una SPA React + TypeScript (Vite), installabile come PWA, servita come file statici da Caddy e collegata al backend solo tramite l'API REST `/api/v1` (e, dalla Fase 1, WebSocket/STOMP su `/ws`).

## Motivazioni

- **Reattività della cassa al tocco.** Ogni tocco su un prodotto aggiorna lo schermo all'istante, senza un giro al server: con molti ordini all'ora e connessioni a volte lente, la differenza si sente.
- **Carrello lato client con retry idempotente.** Il carrello vive nel browser fino alla conferma; l'ordine parte con un UUID generato dal client, così un nuovo tentativo su rete instabile restituisce lo stesso ordine invece di crearne un secondo (regola 6 di `CLAUDE.md`).
- **PWA installabile.** La cassa si installa sul PC o sul tablet come un'app, a schermo intero, con l'app shell in cache (nessun dato offline: la vendita offline resta fuori ambito).
- **Spazio per crescere.** Tablet per i camerieri, schermi cucina in tempo reale e altre viste future riusano la stessa API e gli stessi componenti.

## Costi accettati

- **Seconda toolchain** (npm, TypeScript, Vite, ESLint, Vitest) accanto a Maven, nella build, nella CI e nelle immagini Docker.
- **Due vocabolari** per i testi: i file JSON di i18n del frontend e, quando servirà, i file `.properties` del backend (per esempio per stampe e email). Il backend restituisce codici di errore stabili (`auth.invalid_pin`) che il frontend traduce.
- **Più codice per le pagine CRUD** dell'area gestione rispetto a form generati lato server.

## Alternative scartate

- **Thymeleaf + htmx per tutto:** un solo linguaggio e meno codice per i CRUD, ma un giro al server a ogni tocco in cassa e nessun carrello robusto su rete instabile.
- **Misto React (cassa) + Thymeleaf (gestione):** due modelli di sessione, sicurezza e stile da mantenere insieme; si perderebbe il vantaggio principale di entrambe le soluzioni.
