# Changelog

## 1.1.0 — 2026-08-25

- Added edit and delete controls for scan pairs waiting in block-mode queues.
- Locked queue mutation as soon as block release starts and kept sent rows immutable.
- Added QR 2 scanner-suffix TAB support through the same acceptance path as ENTER.
- Preserved normal TAB traversal on QR 1, Shift+TAB and fields outside scan mode.
- Hardened the saved-settings HUD smoke test against asynchronous X11 geometry updates.
- Expanded regression coverage for queued-row edits, deletion, row compaction and send immutability.

## 1.0.0 — 2026-07-31

- First public release.
