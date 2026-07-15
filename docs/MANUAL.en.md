# AutoFillSuite — User Manual

Operator guide. Five minutes of setup, then the app does the typing and the
checking.

## 1. First setup (once per workstation)

1. Launch the JAR. The window stays **always on top** — that is by design:
   it must survive next to the browser.
2. Open **⚙** (top right). Every setting saves the moment you change it.
3. Memorize the portal coordinates. Each **Memo** button starts a short
   countdown: during it, park the mouse cursor on the target and wait.
   - **⚙ → A intervallo**: the portal's first form field ("Casella 1").
   - **⚙ → Stampa**: the portal's print button.
   - **⚙ → Verifica**: the portal's **Export CSV** button.
4. **⚙ → Verifica → Cartella download**: the folder where the browser saves
   the portal's CSV export. **Cartella report**: where the daily CSV report
   for the quality office is written. **Prefisso export**: the beginning of
   the export file name (leave the default unless the portal changes).
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
   results table. The state banner tracks the phase: REGISTRAZIONE →
   VERIFICA → TUTTO OK / PROBLEMI.
4. **To stop at any time: just move the mouse.** That is the fail-safe.
   **Stop** works too.
5. **Nuova sessione** clears the table, counters and banner for a fresh
   start. Coordinates and timings are kept.

## 3. REGISTRA · Scansione (dual-QR queue mode)

For mixed items: two QR codes per piece (label, then lot).

1. Scan QR 1 and QR 2. The pair enters the **queue** and the fields clear
   at once — keep scanning at your own pace, nothing is lost.
2. Two tempos:
   - **Continuo**: the robot fires each pair as soon as the scanner has
     been quiet for a moment. **PAUSA** holds it whenever you need.
   - **A blocco**: pairs pile up (`IN CODA` counts them); press
     **REGISTRA TUTTO (n)** to release the whole block.
3. The session is verified automatically every N pieces (see §5), or on
   demand with **Verifica**. **Nuova sessione** starts a clean one.

## 4. STAMPA (print mode)

On the portal, the quantity field does **not** print more labels — any value
above 1 only changes the numbering (2 → 2,4,6,8…), still one label per click.
Leave the portal's field at **1** and let this mode do the repeated clicks
for you.

Set **N° stampe** here (how many labels you want), press **STAMPA**, and the
robot presses the portal's print button that many times with the configured
pause. Same fail-safe: move the mouse to stop.

## 5. Verification, results, report

After a run (automatically, if the mode's toggle is on — each mode has its
own in its ⚙ tab) the app clicks **Export CSV** on the portal, picks up the
fresh download, and diffs the whole export against the run:

- **OK** — registered, right lot. `OK ×2` means it was registered twice:
  the portal appends, so a re-run only raises the count.
- **MANCANTE** — sent but not in the export.
- **NON REGISTRATA** — in the export but not expected.
- **LOTTO ERRATO: …** — registered under another lot.

A slow server never produces a false red: the app waits and re-clicks the
export before giving up. If a verification fails, **RIPROVA** repeats it.
Double-click the **Lotto** cell of a row to **register that label again** — keep the lot to re-send it as-is (a second pass), or type a new lot to correct it. Double-click any other cell to copy the label to the clipboard. Rows from earlier runs (see the view toggle) are read-only history.

At the top of the results panel a switch flips between **Ultimo giro** (only the run you just finished) and **Oggi** (every label registered today, numbered continuously). It changes only what you see — the daily report always contains every run.

The **daily report** (`AutoFillSuite_report_yyyy-MM-dd.csv`, folder in ⚙)
**writes itself**: every label you send is journaled the moment it goes out,
with its exact time — at the end of the day the file is complete with no
button pressed. A verification replaces the run's section with real
verdicts; fix the problems on the portal, press RIPROVA and the section is
rewritten in place. **Report CSV** stays as a manual re-save. **⚙ → Storico** shows runs, clean rate and problems
per day, read back from the verification log.

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
| "Memorizza … nelle Impostazioni" | A coordinate is missing: ⚙, the tab named in the message. |
| Verification always red on the export step | Wrong **Cartella download**, or **Prefisso export** does not match the file names, or the site is slow: raise the timeout in ⚙ → Verifica. |
| Typed a quantity, the old one ran | Fixed — make sure you have the current build. |
| Window opens off-screen after unplugging a monitor | It re-centers itself on launch. |
| A run crashed before verifying | On the next launch the app offers **VERIFICA ORA** for the pending run. |
