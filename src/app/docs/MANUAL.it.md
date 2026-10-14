# AutoFillSuite — Manuale d'uso

Guida per l'operatore. Cinque minuti di configurazione, poi l'app digita e
controlla al posto tuo.

Questo stesso testo si legge dentro l'app: **Impostazioni > Manuale**.

## 1. Prima configurazione (una volta per postazione)

1. Avvia il JAR. La finestra resta **sempre in primo piano** — è voluto:
   deve sopravvivere accanto al browser.
2. Apri le **Impostazioni** (l'ingranaggio, in alto a destra). Ogni
   impostazione si salva appena la cambi.
3. Memorizza le coordinate del portale. Ogni tasto **Memo** avvia un breve
   conto alla rovescia: durante il conto, parcheggia il mouse sul bersaglio
   e aspetta.
   - **Impostazioni > A intervallo**: il primo campo del form del portale
     («Casella 1»).
   - **Impostazioni > Stampa**: il tasto di stampa del portale.
   - **Impostazioni > Verifica**: il tasto **Export CSV** del portale.
   - **Impostazioni > A scansione**: il primo campo del form, per la modalità
     a due QR.
4. **Impostazioni > Verifica > Cartella download**: la cartella dove il
   browser salva l'export CSV del portale. **Cartella report**: dove finisce
   il report giornaliero per l'ufficio qualità. **Prefisso export**: l'inizio
   del nome dei file scaricati (lascia il default salvo cambi del portale).
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
   REGISTRAZIONE, VERIFICA, TUTTO OK / PROBLEMI.
4. **Per fermare in qualunque momento: muovi il mouse.** È il fail-safe, e
   vale a ogni passo, anche nella prima etichetta: se lo muovi **prima** del
   salvataggio quell'etichetta non è registrata; **dopo**, è registrata e il
   robot si ferma lo stesso. Vale anche per la correzione del lotto col doppio
   click. Funziona anche **Stop**.

Le sicure dell'intervallo:

- **Lotto in Etichetta.** Se hai descritto i due QR (Impostazioni > A
  scansione), un lotto sparato nel campo Etichetta viene riconosciuto:
  banner giallo e **Scambia** (anche F2). Un codice che non somiglia a nessuno
  dei due viene rifiutato.
- **Oltre 999.** Le ultime tre cifre sono quelle che crescono: un intervallo
  che arriverebbe a 1000 (998 + 5) creerebbe etichette con una cifra in più,
  che non esistono. L'app lo rifiuta: «l'intervallo supera 999».
- **Già registrate oggi.** Se alcune etichette dell'intervallo sono già state
  registrate oggi con un **altro** lotto, l'app te lo dice con l'elenco prima
  di partire: sul portale vince l'ultima, quindi sarebbe una correzione. Con
  lo **stesso** lotto è solo un reinvio e lo segnala nella barra.
5. **Nuova sessione** azzera tabella, contatori e banner per ripartire
   puliti. Coordinate e tempi restano.

## 3. REGISTRA · Scansione (doppio QR con coda)

Per pezzi misti: due QR per pezzo (etichetta in **QR 1**, lotto in **QR 2**).

1. Spara QR 1 e QR 2. La coppia entra in **coda** e i campi si svuotano
   subito — continua a sparare al tuo ritmo, non si perde niente. Sul secondo
   campo funzionano sia il suffisso **INVIO** sia il suffisso **TAB** dello
   scanner; TAB su QR 1 continua invece a spostare normalmente il cursore.
2. Due ritmi:
   - **Continuo**: il robot spara ogni coppia appena lo scanner è fermo da
     un attimo. **PAUSA** lo trattiene quando serve.
   - **A blocco**: le coppie si accumulano (**IN CODA** le conta); premi
     **REGISTRA TUTTO (n)** per rilasciare tutto il blocco.
   - Prima di rilasciare il blocco puoi selezionare una riga **IN CODA** nei
     risultati e usare **Modifica** per correggere QR 1 / QR 2 oppure
     **Elimina** per rimuoverla. Appena parte **REGISTRA TUTTO**, la coda non
     e' piu' modificabile.
3. La sessione viene verificata da sola ogni N pezzi (vedi §5), o a
   richiesta con **Verifica**. **Nuova sessione** ne apre una pulita.
4. La verifica automatica **non entra mai in mezzo a un blocco**: matura al
   pezzo N e parte alla prima pausa vera — coda vuota, campi vuoti, robot
   fermo. Se stai ancora sparando, aspetta te. Il controllo non si perde: se
   arriva al pezzo N mentre la coda è piena, resta in sospeso e parte appena
   la coda si svuota.

### 3.1 Ho sparato il lotto in QR 1

Capita: lo scanner non sa quale QR sta leggendo. La correzione è il tasto
**Scambia** accanto a QR 2, o il tasto **F2**: i due campi si invertono e il
cursore torna dove serve. Finché la coppia non è entrata in coda, niente è
stato inviato.

L'app può anche accorgersene da sola. In **Impostazioni > A scansione >
Controllo formato** descrivi i due QR con un'espressione regolare
(**Modello QR 1**, **Modello QR 2**):

- coppia invertita e **Raddrizza da sola** spenta: banner giallo
  «QR invertiti», niente entra in coda, premi Scambia (F2) e INVIO;
- coppia invertita e **Raddrizza da sola** accesa: l'app scambia e registra,
  avvisando sul banner;
- codice che non rispetta nessuno dei due modelli: banner rosso, la coppia
  resta nei campi e la controlli tu.

I campi vuoti non controllano niente: se non descrivi i QR, l'app non ha
opinioni. Un'espressione scritta male si colora di rosso e viene ignorata,
non blocca mai la linea.

### 3.2 La barra bassa (HUD) e i blocchi

Quando premi **REGISTRA TUTTO** la finestra si riduce da sola alla barra in
fondo allo schermo: durante un blocco non ti servono i campi, ti serve vedere
a che punto è (`n / totale`) e avere lo **STOP** a portata. Il cockpit torna
da solo appena il blocco finisce, o subito se premi STOP, o se il robot si
ferma per qualsiasi motivo — un banner rosso dietro una finestra che non vedi
non e' un messaggio.

In **continuo** la barra non si attiva mai: stai sparando, e una finestra che
si chiude e riapre sotto le mani darebbe solo fastidio. Puoi comunque aprirla
a mano col tasto **HUD**, e spegnere del tutto l'automatismo in
**Impostazioni > Finestra**.

Due regole pratiche:

- **Durante un blocco non sparare**: i campi non sono a schermo e le letture
  andrebbero perse. Aspetta la fine, o premi STOP.
- Se lasci un solo QR nei campi, il blocco non parte e l'app te lo dice:
  completa la coppia o svuota i campi. Se succede a blocco gia' avviato, dopo
  qualche secondo la finestra torna grande da sola per farti leggere il
  motivo, e il blocco riprende appena i campi sono a posto.

### 3.3 Le sicure della modalità a scansione

- **Mouse mosso = robot fermo.** Durante il burst il robot lascia il puntatore
  sul bersaglio: se lo trova altrove, il mouse l'hai ripreso tu. **Prima** del
  salvataggio la coppia torna in cima alla coda e tutto si mette in pausa —
  controlla il form sul portale (potrebbe essere mezzo pieno) e premi
  **RIPRENDI**. **Dopo** il salvataggio la coppia è registrata davvero: resta
  contata come inviata e il robot si ferma lo stesso. Si disattiva in
  **Impostazioni > A scansione > Sicure**, ma è accesa per un motivo.
- **Doppioni.** La stessa etichetta due volte nella stessa sessione è uno
  scanner che ha sparato due volte: l'app la rifiuta e suona.
- **Già registrata oggi.** Se l'etichetta è già stata registrata oggi (in
  un'altra sessione, o prima di riaprire l'app) con un **altro** lotto, l'app
  chiede conferma dicendo quando e con che lotto: sul portale vincerà questo,
  quindi è una correzione. Con lo **stesso** lotto è un reinvio: solo un avviso.
- **Suoni.** Un suono acuto corto = tutto bene; due suoni bassi = errore.
  Volume e acceso/spento in **Impostazioni > Finestra**.
- **Coppia incompleta.** Se lasci un solo QR nei campi mentre la coda è piena,
  dopo qualche secondo il banner lo dice: **COPPIA INCOMPLETA — la coda
  aspetta**. Svuota i campi (la X) o completa la coppia.
- **Finestra di mezzo.** L'app sta sempre davanti: se copre la casella del
  portale, il robot non parte — si sposta da sola o si ferma e te lo scrive.
- **Coppia non inviata.** Se il burst fallisce a metà, la riga in tabella
  diventa **NON INVIATA** e il robot va in pausa: nessuna coppia sparisce in
  silenzio.

### 3.4 La coda è salvata su disco

Ogni coppia che entra in coda viene scritta su disco **prima** di comparire in
tabella (`AutoFillSuite_coda.giornale` nella cartella dei report). Se chiudi
l'app con la coda piena, o salta la corrente, al prossimo avvio la carta
Scansione mostra **Coda ritrovata: N coppie non inviate**:

- **Ripristina** le rimette in coda nello stesso ordine, IN CODA, nella
  modalità in cui le avevi sparate. Non parte niente da solo: in **A blocco**
  premi REGISTRA TUTTO, in **Continuo** premi RIPRENDI.
- **Scarta** (chiede conferma) riparte con la coda vuota. Il vecchio giornale
  non si cancella: resta rinominato con la data.

Una coppia per cui il PC si è spento proprio mentre il robot premeva INVIO
torna come **DA VERIFICARE**: non viene rimessa in coda, la chiarisce la prima
verifica.

## 4. STAMPA

Nel portale il campo quantità **non** stampa più etichette: qualunque valore
oltre 1 cambia solo la numerazione (2 = 2,4,6,8...), sempre una etichetta per
click. Tieni la casella del portale su **1** e lascia che questa modalità
faccia i click ripetuti al posto tuo.

Imposta qui **N° stampe** (quante etichette vuoi), premi **STAMPA**, e il
robot preme il tasto di stampa del portale quel numero di volte con la pausa
configurata. Stesso fail-safe: muovi il mouse per fermare.

## 5. Verifica, risultati, report

A fine run (in automatico, se il toggle della modalità è acceso — ognuna ha
il suo nel proprio tab delle impostazioni) l'app clicca **Export CSV** sul
portale, raccoglie il download fresco e confronta l'intero export col run:

- **OK** — registrata, lotto giusto. `OK ×2` = registrata due volte: il
  portale aggiunge, quindi un doppio run alza solo il conteggio. Vince
  l'ultima: se un'etichetta è stata registrata con più lotti, conta l'ultimo,
  e se l'ultimo non è quello atteso la riga è **LOTTO ERRATO**.
- **MANCANTE** — inviata ma assente dall'export.
- **NON REGISTRATA** — nell'export ma non attesa.
- **LOTTO ERRATO: ...** — l'ultima registrazione ha un altro lotto (quello
  che il portale tiene adesso).
- **NON INVIATA** — il robot si è fermato prima di consegnarla: non è mai
  arrivata al portale.

Un sito lento non produce mai un falso rosso: l'app aspetta e ri-clicca
l'export prima di arrendersi. Se una verifica fallisce, **RIPROVA** la
ripete. Doppio click sulla cella **Lotto** di una riga per **registrare di
nuovo quell'etichetta** — tieni lo stesso lotto per reinviarla tale e quale
(una seconda passata), oppure scrivi un nuovo lotto per correggerla. Doppio
click su qualsiasi altra cella per copiare l'etichetta negli appunti. Le
righe dei giri precedenti (vedi il selettore vista) sono storico in sola
lettura.

In cima al pannello risultati un selettore alterna **Ultimo giro** (solo il
giro appena concluso) e **Oggi** (tutte le etichette registrate in giornata,
numerate di seguito). Cambia solo ciò che vedi — il report giornaliero
contiene sempre tutti i giri.

Il **report giornaliero** (`AutoFillSuite_report_aaaa-mm-gg.csv`, cartella
nelle impostazioni) **si scrive da solo**: ogni etichetta inviata finisce nel
file nel momento in cui parte, con la sua ora esatta — a fine giornata il
file è completo senza aver premuto niente. La verifica sostituisce la sezione
del run coi verdetti veri; sistemi i problemi sul portale, premi RIPROVA e la
sezione viene riscritta al suo posto. **Report CSV** resta come salvataggio
manuale. **Impostazioni > Storico** mostra run, percentuale di puliti e
problemi per giorno, riletti dal log delle verifiche.

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
| «Mouse mosso: robot fermo» in scansione | La coppia è tornata in coda: controlla il form sul portale, svuotalo se è mezzo pieno, poi **RIPRENDI**. |
| «QR invertiti» | Hai sparato il lotto in QR 1: premi **Scambia** (F2) e INVIO. |
| «Etichetta già in sessione» | Lo scanner ha sparato due volte, o il pezzo era già passato. |
| COPPIA INCOMPLETA — la coda aspetta | C'è un solo QR nei campi: completa la coppia o svuota i campi con la X. |
| «Memorizza ... nelle Impostazioni» | Manca una coordinata: impostazioni, nel tab indicato dal messaggio. |
| Verifica sempre rossa sul passo export | **Cartella download** sbagliata, o **Prefisso export** che non combacia coi nomi file, o sito lento: alza il timeout in Impostazioni > Verifica. |
| Finestra fuori schermo dopo aver staccato un monitor | Al riavvio si ricentra da sola. |
| Un run è crashato prima della verifica | Al prossimo avvio l'app offre **VERIFICA ORA** per il run in sospeso. |
| «l'intervallo supera 999» | Le tre cifre finali arriverebbero a 1000: dividi l'intervallo in due, il secondo partendo dalla nuova etichetta. |
| «Lotto in Etichetta» nell'Intervallo | Hai sparato il lotto nel campo Etichetta: premi **Scambia** (F2). |
| Domanda «già registrata oggi» | Quell'etichetta ha già un lotto sul portale da stamattina: conferma solo se vuoi davvero correggerlo. |
| Ho chiuso l'app con la coda piena | Niente è perso: riapri, **Ripristina** sulla carta Scansione, poi REGISTRA TUTTO o RIPRENDI. |
| «GIÀ FATTO» aprendo un file | Quel file è già stato registrato. Se serve davvero rifarlo: **Registra comunque...** |
| «FUORI ORDINE» / «VECCHIO» | Manca l'export precedente della stessa giornata, o ne è già passato uno più nuovo: registra i file in ordine. |
| «MODIFICATO» | Il CSV è stato risalvato fuori da Registro QR (Excel): chiedi il file originale, o rigeneralo da Registro QR (Storico). |
| «CONFLITTO» nel pre-volo | Sul portale c'è un altro lotto per quell'etichetta: controlla quale è giusto, correggi in Registro QR o sul portale, riapri il file. |

## 8. REGISTRA · Da file (file di Registro QR)

Una seconda linea senza portale spara le etichette con **Registro QR** e ne
esporta un CSV (chiavetta o cartella di rete). Qui lo registri sul portale con
il robot, a blocchi, con la stessa verifica di sempre.

1. **Apri CSV...** e scegli il file `registro_<postazione>_<giornata>_<NN>.csv`.
2. Leggi l'elenco di controllo. Ogni riga rossa ferma:
   - **impronta e righe** — il file ha un'impronta in fondo. **MODIFICATO** vuol
     dire che qualcuno l'ha aperto e risalvato (per esempio in Excel): i lotti
     potrebbero non essere quelli sparati. **TRONCATO** vuol dire che è arrivato
     a metà: fattelo rimandare, non si registra.
   - **registro dei file** — **NUOVO**, **GIÀ FATTO** (registrato prima: rifarlo
     sporca lo storico del portale) o **INTERROTTO** (si riprende da dove si era
     fermato).
   - **ordine** — gli export di una giornata vanno registrati in ordine: il 02
     solo dopo l'01, mai l'01 dopo il 02. Un file vecchio registrato dopo una
     correzione rimetterebbe il lotto sbagliato, in silenzio, perché sul portale
     **vince l'ultima** registrazione.
   - **forma dei codici** — le stesse regole QR 1 / QR 2 della Scansione. Le
     righe fuori forma si vedono e **non** partono.
   - **pre-volo sul portale** — prima del primo click il robot fa un Export dal
     portale e guarda l'ultimo lotto di ogni etichetta: salta quelle che ci sono
     già (**GIÀ PRESENTE**) e si ferma su un **CONFLITTO** (un'etichetta
     registrata in un altro modo). Lì decidi tu, mai il programma.
3. **MODIFICATO**, **GIÀ FATTO** e fuori ordine si sbloccano solo con
   **Registra comunque...**, che spiega cosa rischi.
4. **PRE-VOLO E AVVIA**: pre-volo, poi registrazione a blocchi (50 coppie per
   blocco, da Impostazioni > Da file). Alla fine di ogni blocco la verifica
   controlla tutte le coppie inviate dal file. Mai a metà blocco.

Le righe del file **non** si correggono qui: si correggono in Registro QR, che
le manda nel file successivo come **CORREZIONE**. Le correzioni partono come un
reinvio con il lotto nuovo, la stessa cosa del doppio click sul lotto.

**Se si ferma** (mouse mosso, STOP, app chiusa a metà): riapri lo stesso file,
o riapri l'app — la carta mostra **FERMATO — alla riga n** e **Riprendi da n**.
Prima di riprendere il pre-volo controlla di nuovo il portale: copre anche la
coppia inviata un attimo prima che il PC si spegnesse.

AutoFillSuite tiene una copia di ogni file importato nella cartella dei report
(`importazioni/`): la chiavetta si può perdere dopo l'import.
