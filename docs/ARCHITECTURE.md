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
├── config/   SettingsManager, Manuals         (leaf: imports nobody)
├── core/     Robot, Task, Watcher, Verifier,
│             ScanGuard                        (imports config only)
├── docs/     MANUAL.it.md, MANUAL.en.md       (resources, not code)
└── ui/       panels, theme, table, Markdown   (imports core and config)
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

The session verification is **owed, then taken**: every N sends it sets a
`verifyDue` flag, and `maybeAutoVerify()` only fires it when the queue is
empty, the fields are empty and no burst is in flight — retried by a 1s
heartbeat until that lull arrives. Firing it on the count alone tore a
released block in half: the robot walked off to click Export with pairs
still queued. The check runs against a **snapshot** of the pairs sent so far.

### Who owns the HUD

`jobStarted` / `jobFinished` are the whole contract: the window collapses to
the bar while a job runs and comes back the way the operator left it. The scan
tab never called either — the HUD simply never appeared while the robot typed —
and it now calls them for **released blocks only**. Continuo is deliberately
excluded: the operator is still scanning, and a window that collapses and
reopens under his hands every couple of seconds is worse than no HUD at all.

The hard part is the way back, because a bar has no room to explain itself.
`endBlock()` is therefore called from every exit, not just the happy one: the
block draining, STOP from the bar, the mouse fail-safe, a held pair, a burst
that died, an explicit pause, a new session, and the end of the verification
that a finished block may hand over to. A watchdog covers the case the exits
cannot: a single QR scanned mid-block leaves a half pair in the fields, the
queue stops draining, and after a few seconds the cockpit comes back on its own
so the banner can do the talking — without cancelling the block, which resumes
as soon as the fields are clear. Symmetrically, the lever refuses to release a
block while the fields are busy: the worker would not run anyway, and
collapsing onto a block that cannot move parks the window on a bar reading
0 / n forever.

Range mode had the mirror bug: `onFinally` gave the window back only when
auto-verification was OFF, on the assumption that otherwise a verification
would take over. A fail-safe or a STOP skips `onCompleted`, so no verification
ever started — and the window stayed collapsed until the operator expanded it
by hand to find out why the robot had gone quiet. The condition is now "is a
verification actually running", not "is one configured".

### The three safeties of the burst

A burst types into a browser nobody is watching, so it is guarded at three
levels, each with a different failure semantics:

- **Mouse fail-safe.** The robot leaves the pointer on the target, so
  `isMouseMoved(target)` between steps means the operator took the mouse
  back. The interesting line is ENTER: **before** it, nothing is registered —
  the pair returns to the head of the queue and everything pauses; **after**
  it, the pair IS registered — it counts as sent, the return click is
  skipped, and the robot stops anyway. Putting a sent pair back would
  double-register it.
- **`ScanGuard`** (pure, in `core`): the operator describes the two codes
  with regexes and the guard separates an **inversion** (both codes known,
  wrong holes — one keystroke away from correct, so it can be swapped, by
  hand with **F2** or automatically) from a **mismatch** (a code that fits
  neither — nobody can guess the intent, so nothing is queued). An empty or
  invalid pattern is an opinion not given: it never blocks the line.
- **Duplicate guard**: the same label already in the session or in the queue
  is a scanner that fired twice, not a second piece.

A burst that dies halfway marks its row `NON INVIATA` and pauses the worker:
no pair may disappear between the queue and the portal in silence.

`RobotEngine` retries its `new Robot()` instead of remembering the failure —
a display not ready at startup used to leave the app silently unable to type
for the rest of the day.

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

## The manual inside the app

The two operator manuals live in `src/app/docs/` and are read from the
**classpath** (`Manuals`), so they travel inside the JAR: the shop-floor PC
has one browser tab open and it is the portal. `Markdown` turns the subset
the manuals use (headings, bold, code, lists, tables, rules) into an HTML
fragment — escaping first, marking up second, so the document's own text can
never become a tag — and `ManualPane` renders it with the theme's colors at a
fixed size, so the settings dialog cannot grow to the length of the document.
`javac` does not copy resources: the Ant `compile` target and the CI step both
copy `src/**` non-Java files next to the classes, or the JAR would ship
without the manual. The glyph rule covers them too — they are rendered in the
app, so an arrow or a gear character would be a box on the operator's screen.

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
  sections. Journal writes are queued on a **single writer** in submission
  order: two of them at once are two read-modify-writes of the same file, and
  one section is simply lost — the coalesced write of a run and the boundary
  flush of the next overlap exactly when a session ends. The section **key
  travels with the section**, never read from the writer thread, or a run
  boundary crossed in between would file the old rows under the new run's
  identity. Run identities are also strictly increasing (`nextRunStamp`):
  the key is second-resolution, so two runs started inside one second would
  share it and the second would overwrite the first — a whole run gone from
  the day's report. Run boundaries flush any pending write under the OLD
  identity, so a run reset inside the coalescing window still lands its last
  row.
  A verification then replaces the live section with real verdicts;
  `mergeDaily` keys on the section identity and leaves every other run of
  the day intact.

## Results view vs report

The results panel offers a **Ultimo giro / Oggi** toggle. The table model keeps a read-only day archive: `beginRun` moves the finished run into it, and in day view the archive is rendered above the current run with continuous numbering. Two invariants, both under test: mutators (`setLot`, `updateOutcome`, `markSentNow`) speak **run-space** indexes and translate to visual-space only when firing table events, so a correction never lands on history; and `reportEntries` reads the **current run only** — the daily report files every run through `mergeDaily`, so it must never also see the archive, or rows would double.

A double-click re-registers a label: same lot re-sends it (the portal appends, so a second pass is legitimate), a new lot corrects it. Archived rows reject the edit.

## The test pyramid (zero frameworks)

Plain-JDK harnesses: `main` + `check(name, condition)` + exit code. Eleven suites, each born from a real failure:

| Suite | What it pins down | The bug that created it |
|---|---|---|
| ScanGuardTest | inversion vs mismatch; fail-safe geometry | the lot scanned into QR 1; an unreadable pointer read as a movement |
| ManualRenderTest | the bundled manual loads and renders | — (new: the manual ships inside the JAR) |
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
