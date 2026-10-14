# AutoFillSuite — User Manual

Operator guide. Five minutes of setup, then the app does the typing and the
checking.

The same text is readable inside the app: **Impostazioni > Manuale**.

## 1. First setup (once per workstation)

1. Launch the JAR. The window stays **always on top** — that is by design:
   it must survive next to the browser.
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
4. **Impostazioni > Verifica > Cartella download**: the folder where the
   browser saves the portal's CSV export. **Cartella report**: where the
   daily CSV report for the quality office is written. **Prefisso export**:
   the beginning of the export file name (leave the default unless the portal
   changes).
5. Theme (Mocha dark / Latte light) is in the settings header. It applies
   at the next launch.

If the app window would cover one of its own click targets, it steps aside
on its own before starting — or refuses to start and tells you.

## 2. REGISTRA · Intervallo (range mode)

For N consecutive labels of the same batch.

1. Scan one label into **Etichetta** — the app derives prefix and sequence.
   Scan the batch into **Lotto**. Set **Quantità**.
2. The status bar previews the range (`Da <first> -> ...<last digits>`).
3. Press **AVVIA**. A short countdown lets you focus the browser; then the
   robot registers every serial, one row per label appearing live in the
   results table. The state banner tracks the phase: REGISTRAZIONE,
   VERIFICA, TUTTO OK / PROBLEMI.
4. **To stop at any time: just move the mouse.** That is the fail-safe.
   **Stop** works too.
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

Press **REGISTRA TUTTO** and the window shrinks to the bar at the bottom of
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
- **Half-scanned pair.** Leave a single QR in the fields while the queue is
  full and after a few seconds the banner says so: **COPPIA INCOMPLETA — la
  coda aspetta**. Clear the fields (the X) or complete the pair.
- **The window in the way.** The app is always on top: if it covers the
  portal's field, the robot does not start — it steps aside or stops and
  tells you.
- **Pair not sent.** If a burst dies halfway, its table row turns
  **NON INVIATA** and the robot pauses: no pair disappears quietly.

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
correct it. Double-click any other cell to copy the label to the clipboard.
Rows from earlier runs (see the view toggle) are read-only history.

At the top of the results panel a switch flips between **Ultimo giro** (only
the run you just finished) and **Oggi** (every label registered today,
numbered continuously). It changes only what you see — the daily report
always contains every run.

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
**HUD** button.

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
re-send with the new lot, like a double click on the lot.

**If it stops** (mouse moved, STOP, app closed mid-run): reopen the same file,
or the app — the card shows **FERMATO — alla riga n** and **Riprendi da n**.
Before resuming the pre-flight checks the portal again: that also covers the
pair sent a moment before the PC went off.

AutoFillSuite keeps a copy of every imported file in the report folder
(`importazioni/`): the stick may be lost after the import.
