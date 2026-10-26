# Guida per titolari e gestori

Questa guida spiega l'**area gestione** di StandPOS (accesso con email e password da `/login`). Le pagine visibili dipendono dal ruolo:

| Ruolo | Pagine |
| --- | --- |
| Titolare | tutte, per tutti i locali, più Locali e Utenti |
| Gestore (di un locale) | Dispositivi, Stampanti, Reparti, Categorie, Prodotti, Magazzino, Carichi, Inventari, Fornitori, Blocchi |
| Magazziniere (di un locale) | Magazzino, Carichi, Inventari, Fornitori |

Il locale su cui si lavora si sceglie in alto a destra.

## Preparare il locale

1. **Categorie** e **Prodotti**: prezzo nel formato `4,50`. Spunta **Gestisci magazzino** per i prodotti contati a pezzi (bibite in bottiglia, porzioni) e indica la **soglia scorta bassa**. Lascia senza magazzino i prodotti non contati (caffè della macchina).
2. **Locali** (titolare): ora di **cambio giornata** (di solito 04:00: le vendite dopo mezzanotte contano sul giorno prima e la numerazione degli ordini riparte da 1 a quell'ora), intestazione e piè di pagina dello scontrino. **Consenti magazzino negativo** serve alle sagre in cui i carichi si registrano dopo: le vendite non si bloccano quando la giacenza arriva a zero.
3. **Utenti** (titolare): crea i volontari con un **PIN**; i gestori con email e password.
4. **Dispositivi**: genera un codice **Cassa** per ogni PC o tablet di cassa (sul dispositivo si apre `/enrol`) e un codice **Agente di stampa** per il PC a cui è collegata la stampante (vedi [installazione dell'agente](print-agent-setup.md)).
5. **Stampanti**: aggiungi la stampante dell'agente (nome Windows esatto, 80 mm, code page CP858), fai una **Stampa di prova** e, in fondo alla pagina, scegli la stampante di ogni cassa.

## Magazzino

Il magazzino è un **registro di movimenti**: ogni carico, vendita, storno, scarto, rettifica e inventario aggiunge una riga che non si può più cambiare. La giacenza è sempre la somma dei movimenti.

### Pagina Magazzino

- La tabella mostra per ogni prodotto gestito a magazzino: **giacenza**, **soglia**, **valore** e **costo medio** (calcolato dai costi dei carichi) e lo stato: *Disponibile*, *Scorta bassa*, *Esaurito*.
- **Mostra solo scorte basse ed esauriti**: la lista da controllare prima del servizio.
- Un prodotto gestito a magazzino diventa **esaurito da solo** quando la giacenza arriva a zero (se il magazzino negativo non è consentito) e torna in vendita da solo quando entra merce. Le casse si aggiornano subito.
- **Segna esaurito** («finite le lasagne»): lo toglie dalla vendita su tutte le casse anche se il magazzino dice altro; resta così finché non tocchi **Rimetti in vendita**.
- **Movimenti**: l'elenco degli ultimi movimenti del prodotto, con chi, quando e il motivo.

> Quando attivi **Gestisci magazzino** su un prodotto, la giacenza parte da zero e il prodotto risulta esaurito: registra subito un carico o un inventario.

### Rettifiche e scarti

In cima alla pagina Magazzino:

- **Scarto**: merce da buttare (rotta, scaduta). Indica i pezzi e il **motivo** (obbligatorio).
- **Rettifica**: correzioni, regali, consumi interni: numero positivo per aggiungere, negativo per togliere, sempre con il motivo.

Ogni rettifica e scarto è registrato con l'utente e l'ora (registro delle operazioni).

### Carichi di merce

Pagina **Carichi** → **Nuovo carico**:

1. scegli il **fornitore** (si creano in **Fornitori**, valgono per tutti i locali), il numero del documento (DDT, fattura) e la data;
2. aggiungi una riga per prodotto con **quantità** e, se lo conosci, il **costo unitario** (serve per il costo medio e i margini);
3. **Salva bozza** per finire dopo, oppure **Registra carico**: la merce entra in magazzino.

Un carico registrato **non si può più modificare**: un errore si corregge con una rettifica o un inventario.

### Inventari

Pagina **Inventari** → **Nuovo inventario**: la lista contiene tutti i prodotti gestiti a magazzino con la quantità **attesa**.

1. Conta la merce e scrivi i pezzi nella colonna **Contati** (puoi **Salvare** e continuare dopo).
2. **Conferma inventario**: per ogni prodotto contato StandPOS registra la differenza rispetto alla giacenza di quel momento. I prodotti lasciati vuoti non cambiano.

Meglio fare l'inventario a cassa chiusa: le vendite fatte nel frattempo vengono comunque considerate.

## Storni

Gli storni si fanno dalla cassa (**Ordini** → **Storna**) con il **PIN del gestore** o del titolare: per ogni riga si sceglie se la merce **torna a magazzino** o **va buttata**. Lo storno è registrato con il nome del gestore che l'ha autorizzato.

## Blocchi

Dopo 5 tentativi sbagliati il PIN (o l'accesso con password) si blocca per 15 minuti. La pagina **Blocchi** mostra chi è bloccato e fino a che ora:

- il **gestore** sblocca il PIN di chi lavora nel suo locale;
- il **titolare** sblocca anche l'accesso con password (anche dalla pagina Utenti).

Ogni sblocco è registrato.

## Prodotti già venduti

Un prodotto già venduto o movimentato in magazzino **non si può eliminare**: il pulsante diventa **Disattiva**. Il prodotto sparisce dalla cassa ma resta nello storico. I prodotti mai usati si possono ancora eliminare.

## Limite di vendite

Il piano gratuito comprende **2.000 vendite al mese** per organizzazione (contate anche quelle senza scontrino). Il titolare vede un avviso dall'80%. Raggiunto il limite, le casse vendono fino al **cambio giornata** del locale, poi le nuove vendite si bloccano fino al mese successivo. Per aumentare il limite contatta l'assistenza.
