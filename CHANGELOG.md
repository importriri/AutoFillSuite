# Changelog

## 1.3.0 — 2026-10-24

- Counted down before the robot's first move after REGISTRA TUTTO, RIPRENDI and the Da file lever: the hand leaving the mouse tripped the fail-safe at once. The lever turns into ANNULLA AVVIO meanwhile; the seconds are in A scansione > Attesa avvio.
- Showed the day with one row per label and counted it the way the portal does: the last registration wins, so a range stopped and started again is no longer counted twice. The OGGI line under the view switch says how many labels the day holds.
- Reopened the day as it was left: the day view and the count are read back from the day's report at start, so a closed app no longer starts from zero.
- Found the holes in the day's numbering per label type, in every mode: a red OGGI line, a Buchi view with type, from, to and how many, and the day's holes in AutoFillSuite_buchi_<date>.csv. A jump wider than Giornata > Buco più largo is a grey jump, never missing work.
- Added the CANCELLA mode: spoiled labels scanned into a list (saved on disk), deleted from the portal after a countdown with the mouse checked before DELETE, then checked on the export; a deleted label leaves the day's count and fills its hole. A hole can be sent to the list in one click; a label registered today asks before it is listed.
- Watched the window while the app is open: over a coordinate the robot clicks, the header turns red with Spostala while the robot is still, and the window steps aside by itself while the robot works, only between two bursts.
- Opened the portal and started the print program at the first start of the day (Giornata > Postazione all'avvio), the program without a console window and with its output in a log file, never started twice.
- Opened the settings on the right tab when a coordinate is missing.
- Fixed a range in the Oggi view marking the wrong row as sent: the visual index was used instead of the run's.
- Published a Windows app with its own runtime on every release tag (AutoFillSuite-windows.zip): no Java and no installation needed.

## 1.2.0 — 2026-10-14

- Added the Da file mode: registers CSV files exported by ScanLedger (Registro QR) in verified blocks, after checking fingerprint, row count, export order and the portal's current state.
- Added an import register and a copy of every imported file, so a stopped file resumes where it stopped, also from the copy.
- Verified the last registration of each label: a label whose last lot is wrong now reads LOTTO ERRATO even if the right lot came before.
- Kept the scan queue on disk, written before a pair shows in the table, and offered it back at the next start.
- Brought back sent pairs that no verification covered, and pairs cut at ENTER, as pairs to verify.
- Wrote the daily report atomically and flushed it before the app exits.
- Asked before closing, or starting a new scan session, when queued or unchecked pairs would be left behind.
- Let one robot work at a time across every mode, so a verification can no longer click Export in the middle of a scan burst.
- Checked the mouse at every step of every range cycle and of every lot correction, the first cycle included.
- Applied the QR patterns to the range mode, recognised a lot scanned into Etichetta, and refused a range past 999.
- Asked before registering again a label already sent today with another lot; scanner ENTER and TAB answer such questions with No.
- Painted a row green only when the verification looked for the lot the row shows, so a queued correction is never green early.
- Kept the results panel's retry, cancel and corrections with the mode that owns the rows on screen.
- Replaced the system beep with two distinct tones for success and error.
- Accepted portal coordinates on a monitor left of or above the main one, and kept the HUD off them.
- Wrapped long banners instead of cutting them, and made room for four-digit counters in the HUD.

## 1.1.0 — 2026-08-25

- Added edit and delete controls for scan pairs waiting in block-mode queues.
- Locked queue mutation as soon as block release starts and kept sent rows immutable.
- Added QR 2 scanner-suffix TAB support through the same acceptance path as ENTER.
- Preserved normal TAB traversal on QR 1, Shift+TAB and fields outside scan mode.
- Hardened the saved-settings HUD smoke test against asynchronous X11 geometry updates.
- Expanded regression coverage for queued-row edits, deletion, row compaction and send immutability.

## 1.0.0 — 2026-07-31

- First public release.
