# Prova in reparto — AutoFillSuite 1.2.0

Passi da fare sul PC vero, con il portale vero, prima di usare la 1.2.0 per il
lavoro di tutti i giorni. Servono circa 30 minuti, un lettore QR e qualche
etichetta di prova che si può registrare (e correggere) senza danni.

Per ogni passo: cosa fare, cosa deve succedere. Se un passo non va come scritto,
**fermarsi**, non usare la 1.2.0 e annotare cosa si è visto (con una foto dello
schermo se si può).

Tenere a portata di mano la cartella dei report (Impostazioni → Cartelle).

---

## 1. "Vince l'ultima" sul portale (prima di tutto, 2 minuti)

Tutta la 1.2.0 si basa su questa regola: se un'etichetta viene registrata due
volte, il portale tiene l'**ultima** registrazione. Va vista con i propri occhi.

1. Sul portale, a mano, registrare un'etichetta di prova con il lotto **A**.
2. Registrare **la stessa etichetta** con il lotto **B**.
3. Fare Export CSV dal portale e aprirlo (solo lettura).

Deve succedere:
- nel CSV ci sono **tutte e due** le righe, e quella con B viene **dopo** quella con A;
- la pagina del portale, per quell'etichetta, mostra **B**.

Se il portale mostra A, o B viene prima di A: **fermarsi**. La verifica e la
modalità Da file danno per scontato il contrario.

## 2. Due export in ordine, poi il primo di nuovo

Servono due file di Registro QR della stessa postazione e della stessa
giornata: l'export 01, poi una correzione di un lotto in Registro QR, poi
l'export 02.

1. AutoFillSuite → Registra → **Da file** → scegliere l'export **01**.
   Le righe di controllo devono essere tutte verdi. Premere **PRE-VOLO E AVVIA** e lasciarlo finire.
2. Scegliere l'export **02**. Le righe devono essere tutte verdi; l'etichetta corretta
   compare con il lotto nuovo. Avviare e lasciarlo finire.
3. Scegliere di nuovo l'export **01**.

Deve succedere:
- al passo 3 la riga del registro dice **GIÀ FATTO** e quella dell'ordine dice
  **VECCHIO**, in rosso; il file non parte;
- dopo la verifica, sul portale l'etichetta corretta ha il lotto **nuovo**.

## 3. Spina staccata con 10 coppie in coda

1. Scansione, modalità **A blocco**. Sparare 10 coppie di prova. **Non** premere REGISTRA TUTTO.
2. Staccare la spina del PC (non spegnere da Windows).
3. Riaccendere e aprire AutoFillSuite.

Deve succedere:
- in Scansione compare **"Coda ritrovata: 10 coppie non inviate"** con Ripristina e Scarta;
- **Ripristina** rimette le 10 coppie IN CODA, nello stesso ordine; il robot **non** parte da solo;
- REGISTRA TUTTO le invia; la verifica le trova tutte.

Variante, se c'è tempo: staccare la spina **mentre** il robot invia. Alla
riaccensione la coppia che stava partendo è **da verificare**, mai rimessa in
coda; la verifica dice se è arrivata.

## 4. Chiusura subito dopo un invio

1. Scansione, modalità continua. Sparare una coppia e aspettare che il robot l'abbia inviata.
2. Chiudere subito AutoFillSuite con la X.
3. Aprire il report del giorno (`AutoFillSuite_report_<data>.csv`).

Deve succedere:
- il report contiene la coppia appena inviata, con l'ora;
- se si chiude **mentre** il robot sta inviando, prima compare la domanda
  "Chiudi AutoFillSuite": **INVIO non chiude** (la risposta predefinita è No),
  serve il click su Sì.

## 5. Intervallo oltre 999

1. Intervallo. Sparare un'etichetta che finisce con **995**, quantità **10**.

Deve succedere:
- la barra di stato dice che l'intervallo **supera 999**; AVVIA non parte;
- con quantità **5** (995 → 999) parte normalmente.

## 6. Lotto sparato in Etichetta

1. Intervallo. Sparare il **lotto** nel campo Etichetta e l'etichetta nel campo Lotto.

Deve succedere:
- compare in giallo **"Lotto in Etichetta: codici invertiti"** e il suggerimento di premere **Scambia (F2)**;
- AVVIA non parte finché i due codici non sono al posto giusto;
- F2 li scambia e il controllo torna verde.

## 7. "Già registrata oggi" seguito da uno sparo

1. Scansione. Sparare una coppia e lasciarla inviare (lotto **A**).
2. Sparare **la stessa etichetta** con il lotto **B**: compare la domanda
   "Già registrata oggi", che dice l'ora e il lotto A.
3. **Senza toccare la domanda**, sparare subito un'altra coppia qualsiasi.

Deve succedere:
- lo sparo chiude la domanda con **No**: la correzione **non** viene accodata
  (lo dice la barra: "Correzione non accodata");
- la coppia sparata al passo 3 **non** entra in coda (va sparata di nuovo);
- rifacendo il passo 2 e cliccando **Sì** con il mouse, la correzione entra in coda.

---

## Esito

| Passo | OK / NO | Note |
|---|---|---|
| 1. Vince l'ultima | | |
| 2. Export in ordine | | |
| 3. Spina staccata | | |
| 4. Chiusura dopo invio | | |
| 5. Oltre 999 | | |
| 6. Lotto in Etichetta | | |
| 7. Già registrata oggi | | |

Data: ____________ PC: ____________ Chi ha fatto la prova: ____________
