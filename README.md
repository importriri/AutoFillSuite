# AutoFill Suite

A Java desktop tool that automates repetitive data-entry workflows involving barcode/QR scanners and web-based forms, built for industrial and logistics environments.

**Author:** Sid Ahmed Riri — Java 8+, Apache NetBeans

---

## Features

### 📋 Tab Register — Batch label registration
Automates serial form filling with barcodes:
- Barcode input via **dedicated scan field**
- Automatic **sequential range** generation (e.g. `ABC001 → ABC010`)
- Browser automation via `java.awt.Robot`: double-click field, paste code, TAB, paste batch, ENTER
- Configurable cycle timing: **fixed** or **random range** (to simulate human behaviour)
- **Fail-safe**: stops automation immediately if the mouse is moved manually
- Real-time progress bar
- All settings saved automatically on close

### 🖨️ Tab Print — Auto print
Automates repeated clicks on a print button:
- Configurable click count and pause between each
- Useful for batch printing without manual intervention

### 📷 Tab Dual Scan — Dual QR scan
Optimised flow for registering two QR codes as a pair:
1. Scan **QR1** → automatic TAB → focus moves to QR2
2. Scan **QR2** → robot starts automatically
3. Robot fills the browser form: QR1 → TAB → QR2 → TAB → ENTER
4. **Save verification**: reads the field via clipboard (CTRL+A+C) — if empty, save is confirmed; otherwise signals an error
5. Focus returns to QR1 field → ready for the next pair
- Counter tracks successfully completed cycles

---

## Architecture

```
src/
└── app/
    ├── Main.java                  # Entry point, system look & feel
    ├── core/
    │   ├── RobotEngine.java       # Singleton wrapper around java.awt.Robot
    │   ├── AutomationTask.java    # Template Method for async tasks with fail-safe
    │   └── CoordMemorizer.java    # Coordinate capture with countdown
    ├── ui/
    │   ├── MainWindow.java        # Main JFrame, always-on-top
    │   ├── AppTheme.java          # Centralised Factory for components and colours
    │   ├── TabRegistrazione.java  # Tab 1 — label registration
    │   ├── TabStampa.java         # Tab 2 — auto print
    │   └── TabDualScan.java       # Tab 3 — dual QR scan
    └── config/
        └── SettingsManager.java   # Settings persistence via .properties file
```

**Design patterns applied:**
- **Singleton** — `RobotEngine`, `SettingsManager`
- **Template Method** — `AutomationTask` (reusable async cycle logic with fail-safe, countdown and progress bar)
- **Factory** — `AppTheme` (centralised UI component creation)
- **MVC** — each tab separates UI, logic and configuration

---

## Requirements

- **Java 8** or higher
- OS: Windows, Linux, macOS (display access required)
- `java.awt.Robot` permissions (requires system input access)

---

## Build & Run

### Apache NetBeans
1. `File > Open Project`
2. Select the `AutoFillSuite` folder
3. Project is recognised automatically
4. Press **F6** to build and run

### Terminal (Ant)
```bash
ant run        # build and run
ant jar        # produce dist/AutoFillSuite.jar
ant clean      # clean build artifacts
```

### Standalone JAR
```bash
java -jar dist/AutoFillSuite.jar
```

---

## Usage

1. **Launch** the app — the window stays always on top
2. Select the tab for your operation
3. Click **📍 Memo** and move the mouse to the target field in the browser within N seconds — coordinates are saved
4. (Register only) Scan the barcode into the Label field, enter the Batch
5. Click **▶ START** — the app fills the form automatically for each cycle
6. To stop: click **⏹ STOP** or move the mouse (automatic fail-safe)

Settings (coordinates, timing, quantities) are saved automatically and restored on next launch.

---

## License

MIT License — free to use, modify and distribute with attribution.
