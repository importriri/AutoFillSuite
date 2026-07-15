# AutoFillSuite — Architecture, explained

This document explains *how* the program is built and *why* it is built that
way. The how without the why teaches nothing; here, almost every why is a
real bug that was paid for.

---

## The map in 30 seconds

An operator must register hundreds of cable labels on a web portal with no
API. The app drives mouse and keyboard the way a hand would
(`java.awt.Robot`), then **refuses to trust itself**: it clicks the portal's
own Export CSV, waits for the file, and diffs the entire export against what
it just sent. Green only with: 0 missing, 0 unregistered, 0 wrong lots.

## The three layers and the dependency rule

```
app/
├── config/   SettingsManager                 (leaf: imports nobody)
├── core/     Robot, Task, Watcher, Verifier  (imports config only)
└── ui/       panels, theme, table            (imports core and config)
```

The rule: **core does not know the UI exists**. `VerificationTask` never
touches a component: it talks to a `Listener`, and delivers *every* callback
already on the EDT (`SwingUtilities.invokeLater` inside the task, once, in
the right place). The result: panels write handlers as if threading did not
exist. This single decision is what keeps the UI code readable.

## One range run, step by step

1. **Code scan** → `RangeModePanel.updatePreview()` derives the range
   (prefix + sequence) and shows `rangePreview(first, last)` in the status
   bar: the second code collapses to `...tail`, because the final digits are
   the only ones that discriminate — and the bar has finite width.
2. **AVVIA** → `start()`: first thing, `spCount.commitEdit()`. Every button
   is `focusable(false)` (never in the barcode scanner's TAB chain), so a
   click never blurs the spinner editor and a typed value would stay in the
   text without reaching the model ("chose 20, printed 30"). Then
   `WindowGuard`: the app is always-on-top, so it checks whether it is
   *covering* its own click targets, and steps aside or refuses to start.
   Finally it writes `RUN_PENDING=true` plus the run data: if the app dies
   mid-run, the next launch offers to verify the orphan run instead of
   losing its trail.
3. **`AutomationTask`** (Template Method): a worker thread runs
   countdown → N cycles → outcome. The hooks (`showCountdown`,
   `onCompleted`, `onFailSafe`, `showError`, `onFinally`) arrive marshalled
   on the EDT. The **fail-safe**: before every cycle it compares the real
   mouse position with the expected one — if the operator touched the mouse,
   stop. Iron rule: a task never opens a modal dialog.
4. **`RobotEngine`** (singleton): click, double click, type, TAB, ENTER,
   paste via clipboard. Each cycle registers one label and its row appears
   in the table *immediately* (`addLiveRow` + `markSentNow`: the send time
   is data only the app possesses — the portal's export has no timestamps).
5. **Verification** (`VerificationTask`, own thread): click on the
   memorized Export CSV coordinate → `DownloadWatcher` →
   `RegistrationVerifier` → retry if needed (a slow server must never
   produce a false red) → outcome to the listener.
6. **Outcome** → `RunTableModel.applyResult` colors the rows. A rule
   learned the hard way: touch **only** the codes the result covers — a
   pair still sitting in the queue must never turn green without having
   been sent.
7. **Filing**: `VerificationLog.append` (one stamped entry, in a format the
   `VerificationHistory` parser reads back — the contract is under a
   round-trip test because writer and parser drifted apart once),
   `RunReport` into the daily file, and `focusHome`: toFront plus a **real
   OS click** on the scan field, because on Windows the foreground lock
   ignores `toFront()` alone.

## Scan mode (the queue)

Two QR codes per item (label + lot). The architectural point is the
**`LinkedBlockingDeque`**: a scanned pair goes into the queue and the
fields clear at once — the operator scans at their pace, the worker drains
at its own:

```java
for (;;) {
    if (!mayRun()) { Thread.sleep(50); continue; }
    Pair p = queue.pollFirst(50, TimeUnit.MILLISECONDS);
    if (p == null) continue;
    if (!mayRun()) { queue.addFirst(p); continue; }   // conditions flipped
    burst(p);
}
```

`mayRun()` is a pure predicate: not paused, no verification running,
scanner quiet for QUIET_MS, fields empty — and in batch mode, only after ▶.
The **guarded-poll-with-putback** shape is not pedantry: the previous
version did `takeFirst()` *before* waiting its turn, and the first pair
ended up "in the hand" of the parked worker — queue reading 0 with one item
scanned, the release button showing (0) disabled, the count starting from
the second scan, and `newSession()` unable to empty the hand. Nothing
leaves a queue until it can actually be processed.

Every N sends (if the per-mode toggle allows it) a session verification
runs against a **snapshot** of the pairs sent so far.

## The design system (`AppTheme`)

Hand-rolled because the Windows LAF sabotages the easy paths: with
`contentAreaFilled(true)` it paints its own skin and ignores
`setBackground` (the segments would come out as stock gray buttons), and
`JProgressBar` ignores the foreground. Therefore: a hand-painted
`RoundButton` (with the lesson: never mutate properties inside
`paintComponent` — every `setForeground` in there schedules a useless
repaint; hover state lives in the mouse listener, paint only reads),
custom-UI `thinLine()` and `progressStatus()`, `Segmented` at
`contentAreaFilled(false)` + opaque.

Typography: `firstInstalled("Segoe UI", SANS_SERIF)` and
`firstInstalled("Consolas", MONOSPACED)` — a family Java cannot find maps
*silently* to Dialog (proportional) and mono columns fall apart off-Windows.

`fitTail()`: any text overflowing a pixel budget becomes `...tail`, never
`head...` — the final digits (of serials and lots) are the ones the
operator actually reads. The status bar centers its string, and centering a
too-long string pushes x negative, cutting *both* ends: the paint now fits
before drawing, and every `setString` mirrors into the tooltip.

Icons: **Graphics2D, never typed glyphs**. An explicit `Font` kills Java's
glyph fallback and emoji render as empty boxes on Windows
(`GlyphSafetyTest` keeps them from coming back). The gear's hub is
*subtracted* with `Area.subtract`, never "erased" with
`AlphaComposite.Clear`: Clear writes transparent black, which on Swing's
opaque backbuffer is a black disc (`IconRenderTest` renders every icon and
reads the pixels back).

Sizes: **measured, never hard-coded**. Three incarnations of the same bug
(window height 312, HUD 108, settings tabs 370): an outer size tuned on one
machine gets eaten by another machine's title bar and font metrics. The
cure is always the same: honest preferred size (fixed width, true height)
plus `pack()`.

## Persistence

`SettingsManager`: a `.properties` file in `user.home`, write-through
(every change saves at once), keys as constants grouped by prefix
(`reg.*`, `scan.*`, `print.*`, `ui.*`, `run.*`). Spinners *clamp* their
initial value: `SpinnerNumberModel` throws on an out-of-range value, and
one corrupt line in the file must not kill the startup.

## Report and log

- **Verification log** (`AutoFillSuite_verifiche.txt`): one entry per
  verification, readable by humans AND by the `VerificationHistory` parser
  (statistics in ⚙ → Storico). The round-trip test writes with the real
  writer and reads with the real parser: what `append()` writes TODAY,
  `parse()` must count TODAY.
- **Daily report** (`AutoFillSuite_report_yyyy-MM-dd.csv`): one section per
  run (`RUN;date;time;lot=..;mode=..`), rows
  `Label;Lot;SendTime;Verdict;Registrations;Detail`, a `Verifica` line
  (export file used, attempts, freshness) and an `Aggiornato` update stamp.
  It is an **automatic journal**: every send touches a coalescing timer
  (scan bursts are many sends in a blink), the live section — verdicts
  `INVIATA`/`IN CODA` from the row itself — is built on the EDT and written
  off it, and all writes funnel through one synchronized read-merge-write so
  the journal thread and a verification can never lose each other's
  sections. Run boundaries flush any pending write under the OLD identity,
  so a run reset inside the coalescing window still lands its last row.
  A verification then replaces the live section with real verdicts;
  `mergeDaily` keys on the section identity and leaves every other run of
  the day intact.

## Results view vs report

The results panel offers a **Ultimo giro / Oggi** toggle. The table model keeps a read-only day archive: `beginRun` moves the finished run into it, and in day view the archive is rendered above the current run with continuous numbering. Two invariants, both under test: mutators (`setLot`, `updateOutcome`, `markSentNow`) speak **run-space** indexes and translate to visual-space only when firing table events, so a correction never lands on history; and `reportEntries` reads the **current run only** — the daily report files every run through `mergeDaily`, so it must never also see the archive, or rows would double.

A double-click re-registers a label: same lot re-sends it (the portal appends, so a second pass is legitimate), a new lot corrects it. Archived rows reject the edit.

## The test pyramid (zero frameworks)

Plain-JDK harnesses: `main` + `check(name, condition)` + exit code. Ten suites, each born from a real failure:

| Suite | What it pins down | The bug that created it |
|---|---|---|
| RegistrationVerifierTest | the export↔run diff | — (the core, tested from day one) |
| DownloadWatcherTest | fresh-file pickup, charsets | an ANSI export with accents |
| VerificationTaskTest | click→wait→diff→retry | false reds from a slow site |
| CoreExtrasTest | guard, report, history, log round-trip | writer and parser drifting apart |
| GlyphSafetyTest | no emoji in UI strings | empty boxes on Windows |
| IconRenderTest | icons as pixels, hub = hole | a black disc from AlphaComposite.Clear |
| RunTableModelTest | uncovered rows never green; day-view index translation | a queued pair painted OK; a correction landing on history |
| SpinnerCommitTest | on-screen number = executed number | chose 20, printed 30 |
| TextFitTest | overflow keeps the tail | unreadable final digits in the bar |
| StartupSmokeTest (×2) | the real app under Xvfb: clipping on every card and tab, queue, banners, HUD, geometry | Windows-only clipping, lopsided stack |

The anti-clipping walker compares preferred vs actual for every visible
`JPanel`, with one motivated exception: a `CardLayout` host asks for its
tallest card even while showing the small one — that is its contract, not
clipping.

CI: two workflows (`tests.yml` with Xvfb for the smokes, `lint.yml` with
`-Xlint:all -Werror --release 8`).

## The test site

`test-site/lifecycle-test.html`: a single-file, offline mock portal that
reproduces the *behaviour* the automation must handle, deliberately unlike the
real portal in look and data: its own neutral styling, invented item codes and
batches, generic column names. What it keeps is the **interaction contract** —
the same field/TAB order, the same SAVE semantics (the button is
`type="button"`, so ENTER in the batch field does *not* save; the robot must
TAB onto SAVE and press ENTER there), an append-only store, and a
pipe-separated CSV export. Fault-injection knobs — lost registrations,
corrupted batch, server lag, print-before-register row — reproduce every
failure class the verifier must catch, so the whole loop
(robot → export → diff → retry → results) can be tested end to end without
touching production. No asset, code, identifier or data from the real portal
is included.

## Decisions and declared limits

- **Where it started — the print mode**: the portal's print form ignores its
  own quantity field. Any value above 1 only shifts the numbering step
  (2 → 2,4,6,8…) while still printing one label per click, so producing N
  labels meant N manual clicks. The first version of AutoFillSuite did exactly
  one thing: keep the field at 1 and press the print button N times. The range
  and scan modes, the verification pipeline and everything else grew around
  that original repetitive-click problem — which is why a mode that "just
  clicks a button" is the point, not a triviality.
- **Why Robot and not a browser driver**: a locked-down floor PC, no
  installable drivers, a portal with no API. The cost is coordinate
  fragility; the countermeasure is downstream verification — the robot may
  err, the diff may not.
- **Why Java 8**: runs on any corporate machine without discussion.
- **Why zero dependencies**: one JAR, no installer, nothing to explain to IT.
- **Known limits**: the focus-return fires a real OS click at the field's
  location, so it misses if the window is dragged between reading that
  location and the click landing. Two former limits are now closed under
  test: the settings save is atomic (sibling temp file + rename, so a crash
  mid-write can never corrupt it) and `restorePosition` checks every screen —
  a window remembered on a second monitor is restored there, one remembered
  on an unplugged monitor is re-placed instead of opening off-screen.

A note on a case that is *not* a limit, since it looks like one: a label
that landed under both the right lot and a foreign one is not silent — the
right lot marks it matched, and the extra row flags it in the **duplicates**
list, which is exactly the "give it a second look" bucket the operator
scans. Covered by `rightAndWrongLot_isFlaggedAsDuplicate`.
