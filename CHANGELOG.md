# Changelog

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
