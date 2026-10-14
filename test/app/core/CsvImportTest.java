package app.core;

// Adapted from ScanLedger test/app/core/CsvReaderTest.java — keep behaviour in sync.

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

// the import says what a file IS, before the robot registers a single row of
// it. the five contract samples come first, with the outcome the contract
// table promises; then the shapes the samples do not cover. ScanLedger runs the
// same samples through the class this one is copied from. last, the rows whose
// codes do not fit the station's QR patterns: marked, never dropped.
//
//   java -cp out app.core.CsvImportTest
public final class CsvImportTest {

    static final String FREE =
        "CA HVCO 50 T 048 3-2; M-CA HVCO 50 T 048 3-2  Rev.04; Serial No.: 000066076 ; 2023/12/11;A";
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        export1_isValid_withItsRows();
        export2_isValid_withTheCorrection();
        handEdited_isModified();
        truncated_isTruncated();
        excelResaved_isModified_notBecauseOfTheBom();
        bomAlone_isAccepted();
        lfOnly_isAccepted_sameFingerprint();
        format2_isUnknown();
        wrongColumns_areRefused();
        duplicateN_isRefused();
        countMismatch_isTruncated();
        badFields_areNamed();
        outOfShapeRows_areMarked_notDropped();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static byte[] fixture(String name) throws Exception {
        return Files.readAllBytes(new File("test/fixtures/" + name).toPath());
    }

    static String text(String name) throws Exception {
        return new String(fixture(name), StandardCharsets.UTF_8);
    }

    /** Rewrites a file with a fresh, correct #fine: tests one rule at a time. */
    static byte[] sealed(String header, String columns, String... rows) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(header);
        lines.add(columns);
        lines.addAll(java.util.Arrays.asList(rows));
        String sha = CsvImport.fingerprint(lines);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\r\n");
        sb.append("#fine;righe=").append(rows.length).append(";sha256=").append(sha).append("\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static final String H1 = "#registro-qr;formato=1;postazione=L2;giornata=2026-10-13;export=1;creato=2026-10-13T12:00:41";
    static final String ROW1 = "1;209531.001_01-01_2612000231;900612202601310007;2026-10-13T06:58:02;SCAN;NUOVA;0;";

    private static void export1_isValid_withItsRows() throws Exception {
        CsvImport.Result r = CsvImport.read(fixture("contratto-v1-export1.csv"));
        check("export 1 is valid", r.ok());
        check("export 1 has five rows, n 1,2,3,4,6", r.rows.size() == 5 && r.rows.get(4).n == 6);
        check("export 1 header is read", "L2".equals(r.station()) && "2026-10-13".equals(r.date())
              && r.exportNumber() == 1 && !r.reexport());
        check("the free-text lot comes back unquoted and intact", FREE.equals(r.rows.get(4).lot));
        check("row 4 is marked as touched by hand", r.rows.get(3).modified);
        check("the fingerprint is the contract's",
              "d570b815243882baae5e2a86912bb220f2440fb74c91e472f001b696f8a788db".equals(r.computedSha));
    }

    private static void export2_isValid_withTheCorrection() throws Exception {
        CsvImport.Result r = CsvImport.read(fixture("contratto-v1-export2.csv"));
        check("export 2 is valid", r.ok());
        check("its first row is the correction of row 2, with the lot before",
              r.rows.get(0).n == 2 && r.rows.get(0).isCorrection()
              && "900612202601310007".equals(r.rows.get(0).lotBefore));
        check("the quoted lot loses its doubled quotes", "LOTTO \"B\" 7".equals(r.rows.get(2).lot));
    }

    private static void handEdited_isModified() throws Exception {
        CsvImport.Result r = CsvImport.read(fixture("contratto-v1-manomesso.csv"));
        check("a lot changed by hand makes the file MODIFIED", r.status == CsvImport.Status.MODIFIED);
        check("a modified file still lists its rows (for an explicit confirmation)", r.rows.size() == 5);
    }

    private static void truncated_isTruncated() throws Exception {
        CsvImport.Result r = CsvImport.read(fixture("contratto-v1-troncato.csv"));
        check("a file without #fine is TRUNCATED", r.status == CsvImport.Status.TRUNCATED);
        check("a truncated file gives no rows", r.rows.isEmpty());
    }

    private static void excelResaved_isModified_notBecauseOfTheBom() throws Exception {
        CsvImport.Result r = CsvImport.read(fixture("contratto-v1-risalvato-excel.csv"));
        check("a file resaved by Excel is MODIFIED", r.status == CsvImport.Status.MODIFIED);
        check("its quoted label still reads as the label",
              "209531.001_01-01_2612000231".equals(r.rows.get(0).label));
    }

    private static void bomAlone_isAccepted() throws Exception {
        byte[] f = fixture("contratto-v1-export1.csv");
        byte[] withBom = new byte[f.length + 3];
        withBom[0] = (byte) 0xEF; withBom[1] = (byte) 0xBB; withBom[2] = (byte) 0xBF;
        System.arraycopy(f, 0, withBom, 3, f.length);
        check("a BOM alone does not make a file modified", CsvImport.read(withBom).ok());
    }

    private static void lfOnly_isAccepted_sameFingerprint() throws Exception {
        byte[] lf = text("contratto-v1-export1.csv").replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        CsvImport.Result r = CsvImport.read(lf);
        check("LF line ends are accepted", r.ok());
        check("the fingerprint does not depend on the line ends",
              "d570b815243882baae5e2a86912bb220f2440fb74c91e472f001b696f8a788db".equals(r.computedSha));
    }

    private static void format2_isUnknown() {
        CsvImport.Result r = CsvImport.read(sealed(H1.replace("formato=1", "formato=2"), CsvImport.COLUMNS, ROW1));
        check("formato=2 is refused as unknown", r.status == CsvImport.Status.UNKNOWN_FORMAT);
        check("the refusal says which format it found", r.detail.contains("formato 2"));
        check("an unknown header key is ignored",
              CsvImport.read(sealed(H1 + ";turno=B", CsvImport.COLUMNS, ROW1)).ok());
    }

    private static void wrongColumns_areRefused() {
        CsvImport.Result r = CsvImport.read(sealed(H1, "n;etichetta;lotto;ora;modo;tipo;modificata", ROW1));
        check("a different column line is refused", r.status == CsvImport.Status.BAD_COLUMNS);
    }

    private static void duplicateN_isRefused() {
        CsvImport.Result r = CsvImport.read(sealed(H1, CsvImport.COLUMNS, ROW1,
            "1;209531.001_01-01_2612000232;900612202601310007;2026-10-13T06:58:19;SCAN;NUOVA;0;"));
        check("the same n twice in a file is refused", r.status == CsvImport.Status.DUPLICATE_N);
    }

    private static void countMismatch_isTruncated() {
        String t = new String(sealed(H1, CsvImport.COLUMNS, ROW1), StandardCharsets.UTF_8)
            .replace("righe=1", "righe=2");
        check("#fine with the wrong count is TRUNCATED",
              CsvImport.read(t.getBytes(StandardCharsets.UTF_8)).status == CsvImport.Status.TRUNCATED);
    }

    private static void badFields_areNamed() {
        check("a correction without lotto_prima is a bad field",
              CsvImport.read(sealed(H1, CsvImport.COLUMNS,
                  "2;X001;L;2026-10-13T06:58:19;SCAN;CORREZIONE;1;")).status == CsvImport.Status.BAD_FIELD);
        check("an unknown mode is a bad field",
              CsvImport.read(sealed(H1, CsvImport.COLUMNS,
                  "2;X001;L;2026-10-13T06:58:19;MANO;NUOVA;0;")).status == CsvImport.Status.BAD_FIELD);
        check("a missing field is a bad field",
              CsvImport.read(sealed(H1, CsvImport.COLUMNS,
                  "2;X001;L;2026-10-13T06:58:19;SCAN;NUOVA;0")).status == CsvImport.Status.BAD_FIELD);
        check("an empty lot is a bad field",
              CsvImport.read(sealed(H1, CsvImport.COLUMNS,
                  "2;X001;;2026-10-13T06:58:19;SCAN;NUOVA;0;")).status == CsvImport.Status.BAD_FIELD);
        check("a missing header key is a bad header",
              CsvImport.read(sealed("#registro-qr;formato=1;postazione=L2;export=1;creato=x",
                  CsvImport.COLUMNS, ROW1)).status == CsvImport.Status.BAD_HEADER);
        check("a file that is not ours is a bad header",
              CsvImport.read("SerialNumber|ProductionLot\nA|B\n".getBytes(StandardCharsets.UTF_8)).status
              == CsvImport.Status.BAD_HEADER);
        check("an empty file is empty", CsvImport.read(new byte[0]).status == CsvImport.Status.EMPTY);
    }

    private static void outOfShapeRows_areMarked_notDropped() throws Exception {
        CsvImport.Result r = CsvImport.read(fixture("contratto-v1-export1.csv"));
        CsvImport.Result s = CsvImport.inspectShapes(r, "[0-9]{6}\\.[0-9]{3}_.*", "[0-9]{18}");
        check("shape inspection keeps every row", s.rows.size() == 5 && s.ok());
        check("the free-text lot does not fit an 18-digit pattern: marked",
              !s.rows.get(4).inShape && CsvImport.outOfShape(s) == 1);
        check("rows that fit stay in shape", s.rows.get(0).inShape);
        check("no patterns, no opinion",
              CsvImport.outOfShape(CsvImport.inspectShapes(r, "", "")) == 0);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
