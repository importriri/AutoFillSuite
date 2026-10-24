# AutoFillSuite — User Manual

Operator guide. Five minutes of setup, then the app does the typing and the
checking.

The same text is readable inside the app: **Impostazioni > Manuale**.

## 1. First setup (once per workstation)

1. Launch **AutoFillSuite.exe** (from the folder extracted from the release
   zip: no Java, nothing to install) or the JAR when the PC has Java. The
   window stays **always on top** — that is by design: it must survive next
   to the browser. If Windows says "Windows protected your PC", press **More
   info** and then **Run anyway**: no administrator rights are needed.
2. Open the settings (the gear, top right). Every setting saves the moment
   you change it.
3. Memorize the portal coordinates. Each **Memo** button starts a short
   countdown: during it, park the mouse cursor on the target and wait.
   - **Impostazioni > A intervallo**: the portal's first form field
     ("Casella 1").
   - **Impostazioni > Stampa**: the portal's print button.
   - **Impostazioni > Verifica**: the portal's **Export CSV** button.
   - **Impostazioni > A scansione**: the first form field again, for the
     dual-QR mode.
   - **Impostazioni > Cancella**: the serial field of the portal's **Delete**
     box ("Casella Cancella").
4. **Impostazioni > Verifica > Cartella download**: the folder where the
   browser saves the portal's CSV export. **Cartella report**: where the
   daily CSV report for the quality office is written. **Prefisso export**:
   the beginning of the export file name (leave the default unless the portal
   changes).
5. Theme (Latte light, the default / Mocha dark) is in the settings header.
   It applies at the next launch.
6. **Impostazioni > Giornata > Postazione all'avvio**: paste the portal's
   address and pick the print program (the `.exe` file, not the shortcut).
   From the next day on, the first start of the day opens the portal and
   starts the printer by itself (see section 10).

When a coordinate is missing, the app says so and opens the settings on the
right tab.

**The window in the robot's way.** The app is always on top: over a point the
robot clicks, the robot would click the app instead of the portal. The window
is watched while it is open:

- with the **robot still**, the header turns **red** ("Copro: Casella 1")
  with a **Spostala** button. It does not move by itself: you are handling it;
- with the **robot working**, it moves into a free corner by itself, always
  between two labels, never in the middle of a send. With automatic moving
  switched off in **Impostazioni > Finestra** it only turns red (the bottom
  bar too: "SPOSTAMI").

The portal may also sit on a second monitor, right or left of the main one.

## 2. REGISTRA · Intervallo (range mode)

For N consecutive labels of the same batch.

1. Scan one label into **Etichetta** — the app derives prefix and sequence.
   Scan the batch into **Lotto**. Set **Quantità**.
2. The status bar previews the range (`Da <first> -> ...<last digits>`).
3. Press **AVVIA**. A short countdown lets you focus the browser; then the
   robot registers every serial, one row per label appearing live in the
   results table. The state banner tracks the phase: REGISTRAZIONE,
   VERIFICA, TUTTO OK / PROBLEMI.
4. **To stop at any time: just move the mouse.** That is the fail-safe, and it
   is checked at every step, the first label included: moved **before** the
   save, that label is not registered; **after** it, it is registered and the
   robot stops anyway. The lot correction by double click is guarded the same
   way. **Stop** works too.

The range safeties:

- **Lot in Etichetta.** When the two QR codes are described (Impostazioni >
  A scansione), a lot scanned into Etichetta is recognised: yellow banner and
  **Scambia** (also F2). A code that looks like neither is refused.
- **Past 999.** The last three digits are the ones that grow: a range reaching
  1000 (998 + 5) would build labels with one digit more, which do not exist.
  The app refuses it: «l'intervallo supera 999».
- **Registered today.** If some labels of the range were already registered
  today with **another** lot, the app lists them before starting: the last one
  wins on the portal, so it would be a correction. With the **same** lot it is
  only a re-send, shown in the status bar.
5. **Nuova sessione** clears the table, counters and banner for a fresh
   start. Coordinates and timings are kept.

## 3. REGISTRA · Scansione (dual-QR queue mode)

For mixed items: two QR codes per piece (label in **QR 1**, lot in **QR 2**).

1. Scan QR 1 and QR 2. The pair enters the **queue** and the fields clear
   at once — keep scanning at your own pace, nothing is lost. The second field
   accepts either an **ENTER** or **TAB** scanner suffix; TAB on QR 1 keeps
   normal focus traversal.
2. Two tempos:
   - **Continuo**: the robot fires each pair as soon as the scanner has
     been quiet for a moment. **PAUSA** holds it whenever you need.
   - **A blocco**: pairs pile up (`IN CODA` counts them); press
     **REGISTRA TUTTO (n)** to release the whole block.
   - Before releasing the block, select an **IN CODA** row in the results and
     use **Modifica** to correct QR 1 / QR 2 or **Elimina** to remove it. As
     soon as **REGISTRA TUTTO** starts, queued rows are locked.
3. The session is verified automatically every N pieces (see §5), or on
   demand with **Verifica**. **Nuova sessione** starts a clean one.
4. The automatic check **never cuts into a block**: it falls due at the Nth
   piece and starts at the first real lull — queue empty, fields empty, robot
   still. While you are scanning, it waits for you. It is never dropped: if it
   falls due with a full queue, it stays owed and runs the moment the queue
   drains.

### 3.1 I scanned the lot into QR 1

It happens: the scanner has no idea which QR it is reading. The fix is the
**Scambia** button next to QR 2, or the **F2** key: the two fields trade
places and the caret goes back where it belongs. As long as the pair has not
entered the queue, nothing has been sent.

The app can also catch it on its own. Under **Impostazioni > A scansione >
Controllo formato** describe the two codes with a regular expression
(**Modello QR 1**, **Modello QR 2**):

- inverted pair, **Raddrizza da sola** off: a yellow "QR invertiti" banner,
  nothing is queued, press Scambia (F2) and ENTER;
- inverted pair, **Raddrizza da sola** on: the app swaps and registers,
  saying so on the banner;
- a code that fits neither pattern: red banner, the pair stays in the fields
  and you sort it out.

Empty fields check nothing: describe no code and the app holds no opinion. A
malformed expression turns red and is ignored — it never blocks the line.

### 3.2 The bottom bar (HUD) and blocks

Press **REGISTRA TUTTO** and the robot does **not** start at once: the banner,
the bottom bar and the lever itself count **PARTO TRA 3s — togli la mano dal
mouse** (take your hand off the mouse), and the lever reads **ANNULLA AVVIO**
meanwhile. It used to start on the spot, and the hand leaving the mouse
tripped the fail-safe almost every time. The same countdown runs on
**RIPRENDI** (continuo, with pairs queued) and on **PRE-VOLO E AVVIA** in Da
file. The seconds are in **Impostazioni > A scansione > Attesa avvio** (0 = at
once).

Meanwhile the window shrinks to the bar at the bottom of
the screen: during a block you do not need the fields, you need to see how far
it got (`n / total`) and to have **STOP** within reach. The cockpit comes back
by itself when the block ends, at once if you press STOP, and whenever the
robot stops for any other reason — a red banner behind a window you cannot see
is not a message.

In **continuo** the bar never takes over: you are scanning, and a window that
closes and reopens under your hands would only get in the way. You can still
open it by hand with the **HUD** button, and turn the automatic behaviour off
entirely under **Impostazioni > Finestra**.

Two practical rules:

- **Do not scan during a block**: the fields are not on screen and the reads
  would be lost. Wait for the end, or press STOP.
- Leave a single QR in the fields and the block will not start — the app says
  so: complete the pair or clear the fields. If it happens once the block is
  already running, after a few seconds the window comes back on its own so you
  can read why, and the block resumes as soon as the fields are clear.

### 3.3 The safeties of the scan mode

- **Mouse moved, robot stopped.** During a burst the robot leaves the pointer
  on the target: finding it elsewhere means you took the mouse back.
  **Before** the save, the pair returns to the head of the queue and
  everything pauses — check the form on the portal (it may be half filled)
  and press **RIPRENDI**. **After** the save the pair really is registered:
  it stays counted as sent and the robot stops anyway. It can be turned off
  under **Impostazioni > A scansione > Sicure**, but it is on for a reason.
- **Duplicates.** The same label twice in one session is a scanner that fired
  twice: the app refuses it and beeps.
- **Registered today.** If the label was already registered today (another
  session, or before the app was reopened) with **another** lot, the app asks
  first, saying when and with which lot: this one will win on the portal, so it
  is a correction. With the **same** lot it is a re-send: a warning only.
- **Questions and the scanner.** The questions that can show up while you scan
  (registered today, remove a pair, discard the queue, close) answer **No** by
  default: the ENTER or TAB of the next scan answers No, and that scan has to be
  repeated. **Sì** (yes) is clicked with the mouse only.
- **Sounds.** One short high tone = fine; two low tones = error. Volume and
  on/off in **Impostazioni > Finestra**.
- **Half-scanned pair.** Leave a single QR in the fields while the queue is
  full and after a few seconds the banner says so: **COPPIA INCOMPLETA — la
  coda aspetta**. Clear the fields (the X) or complete the pair.
- **The window in the way.** The app is always on top: if it covers the
  portal's field, the robot does not start — it steps aside or stops and
  tells you.
- **Pair not sent.** If a burst dies halfway, its table row turns
  **NON INVIATA** and the robot pauses: no pair disappears quietly.
- **One robot at a time.** While another mode works (a range, a file, a
  print run, a verification) scans are not queued: the banner says **Coppia
  NON accodata** and who is working. Likewise the other modes do not start
  while Scansione has pairs queued or is sending.
- **Verification during a block.** Pressed while a block is being released,
  the verification waits for the end of the block.
- **Nuova sessione.** With pairs queued, or sent and not verified yet, it asks
  first (default answer: No).

### 3.4 The queue is saved on disk

Every pair entering the queue is written to disk **before** it shows in the
table (`AutoFillSuite_coda.giornale` in the report folder). If you close the
app with a full queue, or the power goes, the Scansione card shows **Coda
ritrovata: N coppie non inviate** at the next start:

- **Ripristina** puts them back in the same order, IN CODA, in the mode you
  scanned them in. Nothing starts on its own: in **A blocco** press REGISTRA
  TUTTO, in **Continuo** press RIPRENDI.
- **Scarta** (asks first) starts with an empty queue. The old journal is not
  deleted: it stays, renamed with the date.

These come back as **DA VERIFICARE** (never queued again, the first
verification settles them):

- a pair whose ENTER was being pressed exactly when the PC went off;
- pairs sent that no verification covered yet (for example the app closed
  before the automatic check). Closing the app at that point asks first.

If the journal got damaged (a bad disk sector, a hard power-off), the pairs of
its intact lines still come back; the damaged file is kept beside it.

## 4. STAMPA (print mode)

On the portal, the quantity field does **not** print more labels — any value
above 1 only changes the numbering (2 = 2,4,6,8...), still one label per
click. Leave the portal's field at **1** and let this mode do the repeated
clicks for you.

Set **N° stampe** here (how many labels you want), press **STAMPA**, and the
robot presses the portal's print button that many times with the configured
pause. Same fail-safe: move the mouse to stop.

## 5. Verification, results, report

After a run (automatically, if the mode's toggle is on — each mode has its
own in its settings tab) the app clicks **Export CSV** on the portal, picks
up the fresh download, and diffs the whole export against the run:

- **OK** — registered, right lot. `OK ×2` means it was registered twice:
  the portal appends, so a re-run only raises the count. The last one wins:
  if a label was registered with several lots, the last counts, and if the
  last is not the expected one the row is **LOTTO ERRATO**.
- **MANCANTE** — sent but not in the export.
- **NON REGISTRATA** — in the export but not expected.
- **LOTTO ERRATO: ...** — the last registration carries another lot (the
  one the portal holds now).
- **NON INVIATA** — the robot stopped before delivering it: it never reached
  the portal at all.

A slow server never produces a false red: the app waits and re-clicks the
export before giving up. If a verification fails, **RIPROVA** repeats it.
Double-click the **Lotto** cell of a row to **register that label again** —
keep the lot to re-send it as-is (a second pass), or type a new lot to
correct it. Until the correction has gone out the row reads **CORREZIONE IN
CODA** and no verification colors it; if it cannot go (robot busy, mouse
moved) the row goes back to the old lot and reads **CORREZIONE NON INVIATA**.
Double-click any other cell to copy the label to the clipboard.
Rows from earlier runs (see the view toggle) are read-only history.

At the top of the results panel a switch flips between **Ultimo giro** (only
the run you just finished), **Oggi** and **Buchi** (see section 10). **Oggi**
shows **one row per label**, with the last thing that happened to it: a range
stopped halfway and started again no longer shows its labels twice. Under the
switch, the line **OGGI: n registrate** counts the same way the portal does:
the last registration wins, and deleted labels do not count. It changes only
what you see — the daily report always contains every run.

The **daily report** (`AutoFillSuite_report_yyyy-MM-dd.csv`, folder in the
settings) **writes itself**: every label you send is journaled the moment it
goes out, with its exact time — at the end of the day the file is complete
with no button pressed. A verification replaces the run's section with real
verdicts; fix the problems on the portal, press RIPROVA and the section is
rewritten in place. **Report CSV** stays as a manual re-save.
**Impostazioni > Storico** shows runs, clean rate and problems per day, read
back from the verification log.

After every verification the app comes back to the front with the cursor in
the scan field — ready for the next round.

## 6. HUD

While the robot works you do not need the fields, you need the state: the
window can drop to a slim bar at the bottom of the screen (band, counter,
STOP) and restores itself when the verification ends. Toggle it with the
**HUD** button. If a point the robot clicks lies at the bottom of the screen,
the bar goes elsewhere.

## 7. Troubleshooting

| Symptom | Cause and cure |
|---|---|
| The run stops by itself | You moved the mouse — that is the fail-safe. Restart when ready. |
| "Mouse mosso: robot fermo" in scan mode | The pair went back to the queue: check the form on the portal, clear it if half filled, then **RIPRENDI**. |
| "QR invertiti" | You scanned the lot into QR 1: press **Scambia** (F2) and ENTER. |
| "Etichetta già in sessione" | The scanner fired twice, or the piece had already gone through. |
| COPPIA INCOMPLETA — la coda aspetta | A single QR is sitting in the fields: complete the pair or clear it with the X. |
| "Memorizza ... nelle Impostazioni" | A coordinate is missing: the settings, in the tab named in the message. |
| Verification always red on the export step | Wrong **Cartella download**, or **Prefisso export** does not match the file names, or the site is slow: raise the timeout in Impostazioni > Verifica. |
| Window opens off-screen after unplugging a monitor | It re-centers itself on launch. |
| A run crashed before verifying | On the next launch the app offers **VERIFICA ORA** for the pending run. |
| «l'intervallo supera 999» | The last three digits would reach 1000: split the range, the second part starting from the new label. |
| «Lotto in Etichetta» in Intervallo | The lot went into the Etichetta field: press **Scambia** (F2). |
| "printer error" printing from the portal | The print program does not answer: start it (or **Impostazioni > Giornata > Prepara la postazione adesso**), then try again. If its log stays still, in Chrome: icon left of the address > **Site settings** > **Local network access** > **Allow**, then F5. |
| The header is red: "Copro: ..." | The window covers a point the robot clicks: press **Spostala**, or drag it elsewhere. |
| The OGGI line is red: "BUCHI" | Numbers are missing between registered labels: click the line and look at **Buchi** (section 10). |
| "Questo è un LOTTO" in Cancella | You scanned the lot: scan the label to delete. |
| "ANCORA SUL PORTALE" in Cancella | The portal did not delete it: put it back in the list and press CANCELLA again. |
| «already registered today» question | That label has a lot on the portal since this morning: confirm only to really correct it, clicking **Sì** with the mouse. |
| A scan vanished while a question was open | The scan answered **No** to the question: scan the pair again. |
| I closed the app with a full queue | Nothing is lost: reopen, **Ripristina** on the Scansione card, then REGISTRA TUTTO or RIPRENDI. |
| «Robot occupato: ...» / «Coppia NON accodata: ...» | Another mode is working (or Scansione has pairs queued): wait for it to finish, then try again. |
| «TUTTO INVIATO — manca la verifica finale» in Da file | The file stopped after its last row: press **VERIFICA E CHIUDI**. |
| «GIÀ FATTO» when opening a file | That file was registered already. If it really must be done again: **Registra comunque...** |
| «FUORI ORDINE» / «VECCHIO» | The previous export of the same day is missing, or a newer one went first: register the files in order. |
| «MODIFICATO» | The CSV was resaved outside Registro QR (Excel): ask for the original, or regenerate it in Registro QR (Storico). |
| «CONFLITTO» in the pre-flight | The portal holds another lot for that label: check which is right, correct in Registro QR or on the portal, reopen the file. |

## 8. REGISTRA · Da file (Registro QR files)

A second line without a portal scans its labels with **Registro QR** and
exports a CSV (USB stick or network folder). Here the robot registers it on
the portal, in blocks, with the usual verification.

1. **Apri CSV...** and pick `registro_<station>_<day>_<NN>.csv`.
2. Read the checklist. Any red line stops:
   - **impronta e righe** — the file carries a fingerprint. **MODIFICATO** means
     someone opened and resaved it (Excel, say): the lots may not be the ones
     scanned. **TRONCATO** means it arrived cut: get it sent again, it is not
     registered.
   - **registro dei file** — **NUOVO**, **GIÀ FATTO** (registered before: doing
     it again dirties the portal history) or **INTERROTTO** (resumes where it
     stopped).
   - **ordine** — a day's exports are registered in order: 02 only after 01,
     never 01 after 02. An old file registered after a correction would put the
     wrong lot back, silently, because on the portal **the last registration
     wins**.
   - **forma dei codici** — the same QR 1 / QR 2 rules as Scansione. Rows out
     of shape are shown and do **not** go.
   - **pre-volo sul portale** — before the first click the robot exports from
     the portal and reads the last lot of every label: it skips those already
     there (**GIÀ PRESENTE**) and stops on a **CONFLITTO** (a label registered
     another way). You decide there, never the program.
3. **MODIFICATO**, **GIÀ FATTO** and out of order are unlocked only through
   **Registra comunque...**, which explains what is at risk.
4. **PRE-VOLO E AVVIA**: pre-flight, then registration in blocks (50 pairs per
   block, Impostazioni > Da file). At the end of every block the verification
   checks every pair sent from the file. Never mid-block.

Rows of a file are **not** corrected here: they are corrected in Registro QR,
which sends them in the next file as **CORREZIONE**. A correction goes out as a
re-send with the new lot. A double click on the lot of a file row only
**re-sends it with its own lot**: another lot is refused.

**If it stops** (mouse moved, STOP, app closed mid-run): reopen the same file,
or the app — the card shows **FERMATO — alla riga n** and **Riprendi da n**.
Before resuming the pre-flight checks the portal again: that also covers the
pair sent a moment before the PC went off. If it stopped after the last row
(or the last check did not succeed), nothing is left to send: the button
becomes **VERIFICA E CHIUDI**, and a clean check closes the file.

AutoFillSuite keeps a copy of every imported file in the report folder
(`importazioni/`): the stick may be lost after the import.

## 9. CANCELLA (spoiled labels)

For spoiled or discarded labels that must leave the portal. The portal deletes
when **ENTER** is pressed in its Delete field, with no question: it empties the
field and the label disappears from its export.

1. **CANCELLA** in the top selector.
2. Scan (or type) the label and press ENTER: it joins the **list** and the
   field clears. Add as many as you need. For a whole pack use **Quantità**,
   as in Intervallo: scan the first, set for example 20 and ENTER, and the
   first and the 19 after it join the list. The quantity goes back to 1 after
   every ENTER. **Togli** removes the selected one, **Svuota** the whole list:
   the portal is not touched.
3. Press the red **CANCELLA DAL PORTALE (n)**. The robot counts down
   (**PARTO TRA 3s**: take your hand off the mouse), then for every label it
   clicks the Casella Cancella, pastes the code and presses ENTER, one after
   the other.
4. At the end of the list the app makes an **Export CSV** and checks the
   labels are gone: **CANCELLATE E CONTROLLATE**. Labels still present turn red
   (**ANCORA SUL PORTALE**): put them back in the list and try again.
   **Verifica** repeats the check by hand.

The safeties:

- **A label registered today** with a lot is a good piece: before listing it
  the app asks, saying when and with which lot.
- **A lot** scanned into the field is refused ("Questo è un LOTTO"), when the
  two QR codes are described in **Impostazioni > A scansione**.
- **Mouse moved**: before ENTER nothing is deleted and the robot stops; after
  ENTER the label is deleted and the robot stops anyway.
- **A range never starts by itself**, not even with "Cancella subito": you see
  it in the list first, then press the red lever.
- **The list is saved on disk** (`AutoFillSuite_cancella_lista.txt`): close
  the app and you find it again as it was.

A deleted label leaves the **OGGI** count and fills its place in the
numbering: it is no longer a hole. **Impostazioni > Cancella** can also start
the robot at every ENTER (single labels), without the red lever; and, should
the portal ever stop deleting on ENTER, make it click the **DELETE** button
instead (its coordinate is then needed).

## 10. The day: holes, reopening, workstation

**Holes.** Labels of one type are printed in sequence: with 510 and 520
registered and nothing in between, 511 to 519 are missing — pieces not
registered, or spoiled labels never deleted from the portal. The app looks for
them per **label type** (everything before the final number), in every mode,
over the whole day:

- the **OGGI** line turns **red** ("2 BUCHI (14 mancanti)"): click it;
- the **Buchi** switch lists type, from, to and how many are missing;
- register those labels or, if they are spoiled, select the hole and press
  **Cancella dal portale**: they go to the Cancella tab, and once deleted the
  hole is gone. A pair still waiting in the queue already closes its hole.

A jump wider than 50 numbers (**Impostazioni > Giornata > Buco più largo**) is
not a hole but another stack of labels: it shows in grey as a **salto**, never
as missing work. The day's holes are also written to
`AutoFillSuite_buchi_yyyy-mm-dd.csv`, next to the report.

**Reopening.** Close the app (even by mistake) and reopen it on the same day:
everything is back. The OGGI line and the **Oggi** view are read again from
the day's report, the scan queue is restored (3.4), the Cancella list is
saved, a half-done file resumes.

**Workstation.** With **Impostazioni > Giornata > Postazione all'avvio** on,
the first start of the day opens the portal in the browser and starts the
print program, unless it already runs. The program starts **without a
window** (its log goes to `AutoFillSuite_stampa_log.txt`): a black console
window clicked by mistake goes into "Select" mode and freezes printing.
**Prepara la postazione adesso** does the same whenever you want.
