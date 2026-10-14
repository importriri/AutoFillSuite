package app.core;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

// before registering a file, ask the portal what it holds NOW: skip what is
// already there, send what is expected, stop on anything else. the portal's
// view comes from the verifier's own lastLots (no second export parser).
//
//   java -cp out app.core.PreflightTest
public final class PreflightTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        CsvImport.Result f2 = CsvImport.read(Files.readAllBytes(
            new File("test/fixtures/contratto-v1-export2.csv").toPath()));
        // row 2: CORREZIONE 007 -> 009; row 7: NUOVA 009; row 8: NUOVA "LOTTO ""B"" 7"
        List<CsvImport.CsvRow> rows = f2.rows;

        Map<String, String> portal = new RegistrationVerifier().lastLots(Arrays.asList(
            "SerialNumber|ProductionLot",
            "209531.001_01-01_2612000232|900612202601310007"));
        List<Preflight.Item> p = Preflight.check(rows, portal);
        check("a correction whose lotto_prima is on the portal is sent",
              p.get(0).verdict == Preflight.Verdict.DA_INVIARE);
        check("a new row with the label absent is sent",
              p.get(1).verdict == Preflight.Verdict.DA_INVIARE && p.get(2).verdict == Preflight.Verdict.DA_INVIARE);

        portal = new RegistrationVerifier().lastLots(Arrays.asList(
            "SerialNumber|ProductionLot",
            "209531.001_01-01_2612000232|900612202601310007",
            "209531.001_01-01_2612000232|900612202601310009",   // corrected by hand already
            "209531.001_01-01_2612000237|900612202601310009",
            "209531.001_01-01_2612000237|"));                    // reprinted after
        p = Preflight.check(rows, portal);
        check("a correction already made by hand on the portal is GIA PRESENTE",
              p.get(0).verdict == Preflight.Verdict.GIA_PRESENTE);
        check("a reprint after the registration does not hide it",
              p.get(1).verdict == Preflight.Verdict.GIA_PRESENTE);

        portal = new RegistrationVerifier().lastLots(Arrays.asList(
            "SerialNumber|ProductionLot",
            "209531.001_01-01_2612000232|900612202601310555",
            "209531.001_01-01_2612000238|ALTRO"));
        p = Preflight.check(rows, portal);
        check("a correction finding a lot that is neither old nor new is a CONFLITTO",
              p.get(0).verdict == Preflight.Verdict.CONFLITTO && "900612202601310555".equals(p.get(0).found));
        check("a new row already registered with another lot is a CONFLITTO",
              p.get(2).verdict == Preflight.Verdict.CONFLITTO && "ALTRO".equals(p.get(2).found));
        check("the counts add up", Preflight.count(p, Preflight.Verdict.CONFLITTO) == 2
              && Preflight.count(p, Preflight.Verdict.DA_INVIARE) == 1);

        CsvImport.Result f1 = CsvImport.read(Files.readAllBytes(
            new File("test/fixtures/contratto-v1-export1.csv").toPath()));
        portal = new RegistrationVerifier().lastLots(Arrays.asList(
            "209531.001_01-01_2612000236|CA HVCO 50 T 048 3-2; M-CA HVCO 50 T 048 3-2  Rev.04; "
            + "Serial No.: 000066076 ; 2023/12/11;A"));
        p = Preflight.check(f1.rows, portal);
        check("a free-text lot matches strictly, double spaces included",
              p.get(4).verdict == Preflight.Verdict.GIA_PRESENTE);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
