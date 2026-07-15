# AutoFillSuite — Manuale d'uso

Guida per l'operatore. Cinque minuti di configurazione, poi l'app digita e
controlla al posto tuo.

## 1. Prima configurazione (una volta per postazione)

1. Avvia il JAR. La finestra resta **sempre in primo piano** — è voluto:
   deve sopravvivere accanto al browser.
2. Apri **⚙** (in alto a destra). Ogni impostazione si salva appena la cambi.
3. Memorizza le coordinate del portale. Ogni tasto **Memo** avvia un breve
   conto alla rovescia: durante il conto, parcheggia il mouse sul bersaglio
   e aspetta.
   - **⚙ → A intervallo**: il primo campo del form del portale («Casella 1»).
   - **⚙ → Stampa**: il tasto di stampa del portale.
   - **⚙ → Verifica**: il tasto **Export CSV** del portale.
4. **⚙ → Verifica → Cartella download**: la cartella dove il browser salva
   l'export CSV del portale. **Cartella report**: dove finisce il report
   giornaliero per l'ufficio qualità. **Prefisso export**: l'inizio del nome
   dei file scaricati (lascia il default salvo cambi del portale).
5. Il tema (Mocha scuro / Latte chiaro) è nell'intestazione delle
   impostazioni. Si applica al prossimo avvio.

Se la finestra dell'app coprisse uno dei suoi stessi bersagli di click, si
sposta da sola prima di partire — o rifiuta di partire e te lo dice.

## 2. REGISTRA · Intervallo

Per N etichette consecutive dello stesso lotto.

1. Spara un'etichetta in **Etichetta** — l'app ricava prefisso e sequenza.
   Spara il lotto in **Lotto**. Imposta la **Quantità**.
2. La barra di stato mostra l'anteprima del range
   (`Da <prima> -> ...<ultime cifre>`).
3. Premi **AVVIA**. Un breve conto alla rovescia ti lascia mettere a fuoco
   il browser; poi il robot registra ogni seriale, con una riga per
   etichetta che appare in tabella in tempo reale. Il banner segue la fase:
   REGISTRAZIONE → VERIFICA → TUTTO OK / PROBLEMI.
4. **Per fermare in qualunque momento: muovi il mouse.** È il fail-safe.
   Funziona anche **Stop**.
5. **Nuova sessione** azzera tabella, contatori e banner per ripartire
   puliti. Coordinate e tempi restano.

## 3. REGISTRA · Scansione (doppio QR con coda)

Per pezzi misti: due QR per pezzo (etichetta, poi lotto).

1. Spara QR 1 e QR 2. La coppia entra in **coda** e i campi si svuotano
   subito — continua a sparare al tuo ritmo, non si perde niente.
2. Due ritmi:
   - **Continuo**: il robot spara ogni coppia appena lo scanner è fermo da
     un attimo. **PAUSA** lo trattiene quando serve.
   - **A blocco**: le coppie si accumulano (**IN CODA** le conta); premi
     **REGISTRA TUTTO (n)** per rilasciare tutto il blocco.
3. La sessione viene verificata da sola ogni N pezzi (vedi §5), o a
   richiesta con **Verifica**. **Nuova sessione** ne apre una pulita.

## 4. STAMPA

Nel portale il campo quantità **non** stampa più etichette: qualunque valore
oltre 1 cambia solo la numerazione (2 → 2,4,6,8…), sempre una etichetta per
click. Tieni la casella del portale su **1** e lascia che questa modalità
faccia i click ripetuti al posto tuo.

Imposta qui **N° stampe** (quante etichette vuoi), premi **STAMPA**, e il
robot preme il tasto di stampa del portale quel numero di volte con la pausa
configurata. Stesso fail-safe: muovi il mouse per fermare.

## 5. Verifica, risultati, report

A fine run (in automatico, se il toggle della modalità è acceso — ognuna ha
il suo nel proprio tab di ⚙) l'app clicca **Export CSV** sul portale,
raccoglie il download fresco e confronta l'intero export col run:

- **OK** — registrata, lotto giusto. `OK ×2` = registrata due volte: il
  portale aggiunge, quindi un doppio run alza solo il conteggio.
- **MANCANTE** — inviata ma assente dall'export.
- **NON REGISTRATA** — nell'export ma non attesa.
- **LOTTO ERRATO: …** — registrata sotto un altro lotto.

Un sito lento non produce mai un falso rosso: l'app aspetta e ri-clicca
l'export prima di arrendersi. Se una verifica fallisce, **RIPROVA** la
ripete. Doppio click sulla cella **Lotto** di una riga per **registrare di nuovo quell'etichetta** — tieni lo stesso lotto per reinviarla tale e quale (una seconda passata), oppure scrivi un nuovo lotto per correggerla. Doppio click su qualsiasi altra cella per copiare l'etichetta negli appunti. Le righe dei giri precedenti (vedi il selettore vista) sono storico in sola lettura.

In cima al pannello risultati un selettore alterna **Ultimo giro** (solo il giro appena concluso) e **Oggi** (tutte le etichette registrate in giornata, numerate di seguito). Cambia solo ciò che vedi — il report giornaliero contiene sempre tutti i giri.

Il **report giornaliero** (`AutoFillSuite_report_aaaa-mm-gg.csv`, cartella
in ⚙) **si scrive da solo**: ogni etichetta inviata finisce nel file nel
momento in cui parte, con la sua ora esatta — a fine giornata il file è
completo senza aver premuto niente. La verifica sostituisce la sezione del
run coi verdetti veri; sistemi i problemi sul portale, premi RIPROVA e la
sezione viene riscritta al suo posto. **Report CSV** resta come salvataggio
manuale. **⚙ → Storico** mostra
run, percentuale di puliti e problemi per giorno, riletti dal log delle
verifiche.

Dopo ogni verifica l'app torna in primo piano col cursore nel campo di
scansione — pronta per il giro successivo.

## 6. HUD

Mentre il robot lavora non ti servono i campi, ti serve lo stato: la
finestra può ridursi a una barra sottile in fondo allo schermo (banda,
contatore, STOP) e si ripristina da sola a verifica finita. Si attiva col
tasto **HUD**.

## 7. Problemi comuni

| Sintomo | Causa e rimedio |
|---|---|
| Il run si ferma da solo | Hai mosso il mouse — è il fail-safe. Riparti quando vuoi. |
| «Memorizza … nelle Impostazioni» | Manca una coordinata: ⚙, nel tab indicato dal messaggio. |
| Verifica sempre rossa sul passo export | **Cartella download** sbagliata, o **Prefisso export** che non combacia coi nomi file, o sito lento: alza il timeout in ⚙ → Verifica. |
| Digitata una quantità, è partita quella vecchia | Risolto — assicurati di avere la build corrente. |
| Finestra fuori schermo dopo aver staccato un monitor | Al riavvio si ricentra da sola. |
| Un run è crashato prima della verifica | Al prossimo avvio l'app offre **VERIFICA ORA** per il run in sospeso. |
