# AutoFill Suite

Applicazione desktop Java per l'automazione della compilazione di form web, progettata per ambienti industriali/logistici che richiedono la registrazione massiva di etichette tramite barcode scanner.

> **Sviluppato da:** Sid Ahmed Riri
> **Strumenti:** Java 8+, Apache NetBeans 29
> **Metodologia:** Progettato e strutturato dall'autore, implementato con assistenza di AI (Claude by Anthropic)

---

## Funzionalità

### 📋 Tab Register — Registrazione etichette
Automatizza la compilazione seriale di form web con codici a barre:
- Acquisizione del codice tramite **barcode scanner** (campo di scan dedicato)
- Generazione automatica del **range sequenziale** (es. `ABC001 → ABC010`)
- Inserimento automatico nel browser via `java.awt.Robot`: doppio click sulla casella, incolla codice, TAB, incolla batch, ENTER
- Ritmo tra cicli configurabile: **fisso** o **range casuale** (per simulare comportamento umano)
- **Fail-safe**: rileva se il mouse viene spostato manualmente e blocca l'automazione
- Progress bar con stato in tempo reale
- Tutte le impostazioni salvate automaticamente alla chiusura

### 🖨️ Tab Print — Stampa automatica
Automatizza click ripetuti su un pulsante di stampa:
- Configura numero di click e pausa tra uno e l'altro
- Utile per stampe batch senza intervento manuale

### 📷 Tab Dual Scan — Doppia scansione
Flusso ottimizzato per la registrazione a due QR code:
1. Scanner spara **QR1** → TAB automatico → focus su QR2
2. Scanner spara **QR2** → robot parte automaticamente
3. Robot compila il form nel browser: QR1 → TAB → QR2 → TAB → ENTER
4. **Verifica salvataggio**: legge la casella via clipboard (CTRL+A+C) — se vuota il salvataggio è confermato, altrimenti segnala errore
5. Focus torna sulla casella QR1 Java → pronto per la coppia successiva
- Contatore cicli completati con successo

---

## Architettura

```
src/
└── app/
    ├── Main.java                  # Entry point, look & feel di sistema
    ├── core/
    │   ├── RobotEngine.java       # Singleton wrapper su java.awt.Robot
    │   ├── AutomationTask.java    # Template Method per task asincroni con fail-safe
    │   └── CoordMemorizer.java    # Acquisizione coordinate con countdown
    ├── ui/
    │   ├── MainWindow.java        # JFrame principale, always-on-top
    │   ├── AppTheme.java          # Factory centralizzata per componenti e colori
    │   ├── TabRegistrazione.java  # Tab 1 — registrazione etichette
    │   ├── TabStampa.java         # Tab 2 — stampa automatica
    │   └── TabDualScan.java       # Tab 3 — doppia scansione QR
    └── config/
        └── SettingsManager.java   # Persistenza impostazioni su file .properties
```

**Pattern utilizzati:**
- **Singleton** — `RobotEngine`, `SettingsManager`
- **Template Method** — `AutomationTask` (logica del ciclo asincrono con fail-safe, countdown e progress riusabili)
- **MVC** — ogni tab separa UI, logica e configurazione

---

## Requisiti

- **Java 8** o superiore
- Sistema operativo: Windows, Linux, macOS (con accesso al display)
- Permessi per `java.awt.Robot` (richiede accesso all'input del sistema)

---

## Come aprire il progetto

### Apache NetBeans
1. `File > Open Project`
2. Seleziona la cartella `AutoFillSuite`
3. Il progetto viene riconosciuto automaticamente
4. **F6** per compilare ed eseguire

### Da terminale (Ant)
```bash
ant run        # compila ed esegue
ant jar        # produce dist/AutoFillSuite.jar
ant clean      # pulisce gli artefatti
```

### JAR standalone
```bash
java -jar dist/AutoFillSuite.jar
```

---

## Utilizzo

1. **Avvia** l'applicazione — la finestra rimane sempre in primo piano
2. Scegli il tab in base all'operazione
3. Clicca **📍 Memo** e posiziona il mouse sulla casella del form nel browser entro N secondi — le coordinate vengono memorizzate
4. (Solo Register) Spara il barcode con lo scanner nel campo Etichetta, inserisci il Batch
5. Clicca **▶ AVVIA** — l'app compila automaticamente il form per ogni ciclo
6. Per interrompere: clicca **⏹ STOP** o sposta il mouse (fail-safe automatico)

Le impostazioni (coordinate, tempi, quantità) vengono salvate automaticamente e ripristinate al prossimo avvio.

---

## Licenza

MIT License — libero utilizzo, modifica e distribuzione con attribuzione.
