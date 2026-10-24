package app.core;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

// the day counted the way the portal counts it: one entry per label, the last
// thing that happened to it. a range stopped halfway and started again used to
// count its first labels twice, and a closed app reopened on an empty day.
//
//   java -cp out app.core.DayLedgerTest
public final class DayLedgerTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        report_rowsNeverSent_areNotEvents();
        lastWins_byTime_notByFileOrder();
        deletion_afterRegistration_removesTheLabel();
        registration_afterDeletion_bringsItBack();
        deletion_foundStillThere_didNotHappen();
        run_pastMidnight_keepsCounting();
        counts_everyLabelOnce();
        sentToday_skipsDeletedLabels();
        seconds_readsTimes();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static final String HEAD = "Etichetta;Lotto;OraInvio;Esito;Registrazioni;Dettaglio";

    private static void report_rowsNeverSent_areNotEvents() {
        List<String> r = Arrays.asList(
            "RUN;2026-10-24;08:00:00;lotto=L1;modalita=intervallo",
            HEAD,
            "A001;L1;08:00:05;OK;1;",
            "A002;L1;-;NON INVIATA;0;mai inviata al portale",
            "A003;L1;-;IN CODA;0;",
            "TUTTO OK;1/3",
            "Verifica;08:01:00;file=x.csv;tentativi=1;export=fresco",
            "Aggiornato;2026-10-24 08:01:00");
        List<DayLedger.Event> ev = DayLedger.fromReport(r);
        check("only sent rows are events", ev.size() == 1 && "A001".equals(ev.get(0).code));
        check("the event keeps lot, time and verdict",
              "L1".equals(ev.get(0).lot) && "08:00:05".equals(ev.get(0).time) && "OK".equals(ev.get(0).verdict));
    }

    private static void lastWins_byTime_notByFileOrder() {
        // a scan session started at 08:00 and still open at 11:00 sits in the
        // file BEFORE a range started at 09:00: the file order is not the order
        List<String> r = Arrays.asList(
            "RUN;2026-10-24;08:00:00;lotto=-;modalita=scansione",
            HEAD,
            "A001;LOT-LATE;11:00:00;INVIATA;0;",
            "",
            "RUN;2026-10-24;09:00:00;lotto=LOT-EARLY;modalita=intervallo",
            HEAD,
            "A001;LOT-EARLY;09:00:05;OK;1;");
        Map<String, DayLedger.Event> day = DayLedger.resolve(DayLedger.fromReport(r));
        check("one entry per label", day.size() == 1);
        check("the later time wins, not the later line", "LOT-LATE".equals(day.get("A001").lot));
    }

    private static void deletion_afterRegistration_removesTheLabel() {
        List<String> r = Arrays.asList(
            "RUN;2026-10-24;08:00:00;lotto=L1;modalita=intervallo",
            HEAD,
            "A001;L1;08:00:05;OK;1;",
            "A002;L1;08:00:07;OK;1;",
            "",
            "RUN;2026-10-24;10:00:00;lotto=-;modalita=cancella",
            HEAD,
            "A002;;10:00:03;CANCELLATA;0;");
        Map<String, DayLedger.Event> day = DayLedger.resolve(DayLedger.fromReport(r));
        check("the deleted label's last event is the deletion", day.get("A002").deleted);
        check("the other label stays registered", !day.get("A001").deleted);
        Map<String, String[]> reg = DayLedger.registered(day);
        check("a deleted label is not registered any more", reg.size() == 1 && reg.containsKey("A001"));
    }

    private static void registration_afterDeletion_bringsItBack() {
        List<String> r = Arrays.asList(
            "RUN;2026-10-24;08:00:00;lotto=-;modalita=cancella",
            HEAD,
            "A005;;08:10:00;CANCELLATA OK;0;",
            "",
            "RUN;2026-10-24;09:00:00;lotto=L9;modalita=intervallo",
            HEAD,
            "A005;L9;09:00:05;OK;1;");
        Map<String, DayLedger.Event> day = DayLedger.resolve(DayLedger.fromReport(r));
        check("registered after the deletion: registered", !day.get("A005").deleted && "L9".equals(day.get("A005").lot));
    }

    private static void deletion_foundStillThere_didNotHappen() {
        List<String> r = Arrays.asList(
            "RUN;2026-10-24;08:00:00;lotto=L1;modalita=intervallo",
            HEAD,
            "A001;L1;08:00:05;OK;1;",
            "",
            "RUN;2026-10-24;10:00:00;lotto=-;modalita=cancella",
            HEAD,
            "A001;;10:00:03;ANCORA PRESENTE;0;l'export la contiene ancora");
        Map<String, DayLedger.Event> day = DayLedger.resolve(DayLedger.fromReport(r));
        check("a deletion the export contradicts is not a deletion", !day.get("A001").deleted);
    }

    private static void run_pastMidnight_keepsCounting() {
        List<String> r = Arrays.asList(
            "RUN;2026-10-24;23:58:00;lotto=L1;modalita=intervallo",
            HEAD,
            "A001;L1;23:59:00;OK;1;",
            "A001;L2;00:03:00;OK;1;");
        List<DayLedger.Event> ev = DayLedger.fromReport(r);
        check("a send after midnight counts after the one before it",
              ev.size() == 2 && ev.get(1).when > ev.get(0).when);
        check("and wins", "L2".equals(DayLedger.resolve(ev).get("A001").lot));
    }

    private static void counts_everyLabelOnce() {
        // a range of 5 stopped after 3, then started again from the first: 8 sends, 5 labels
        List<String> r = Arrays.asList(
            "RUN;2026-10-24;08:00:00;lotto=L1;modalita=intervallo",
            HEAD,
            "A001;L1;08:00:05;INVIATA;0;",
            "A002;L1;08:00:07;INVIATA;0;",
            "A003;L1;08:00:09;INVIATA;0;",
            "",
            "RUN;2026-10-24;08:05:00;lotto=L1;modalita=intervallo",
            HEAD,
            "A001;L1;08:05:05;OK;1;",
            "A002;L1;08:05:07;OK;1;",
            "A003;L1;08:05:09;OK;1;",
            "A004;L1;08:05:11;OK;1;",
            "A005;L1;08:05:13;OK;1;",
            "",
            "RUN;2026-10-24;09:00:00;lotto=-;modalita=cancella",
            HEAD,
            "A004;;09:00:02;CANCELLATA;0;");
        int[] c = DayLedger.counts(DayLedger.resolve(DayLedger.fromReport(r)));
        check("8 sends of 5 labels count 5, one deleted: 4 + 1", c[0] == 4 && c[1] == 1);
    }

    private static void sentToday_skipsDeletedLabels() {
        List<String> r = Arrays.asList(
            "RUN;2026-10-24;08:00:00;lotto=L1;modalita=intervallo",
            HEAD,
            "A001;L1;08:00:05;OK;1;",
            "",
            "RUN;2026-10-24;10:00:00;lotto=-;modalita=cancella",
            HEAD,
            "A001;;10:00:03;CANCELLATA;0;");
        check("the report's sentToday forgets a deleted label", RunReport.sentToday(r).isEmpty());
    }

    private static void seconds_readsTimes() {
        check("HH:mm:ss", DayLedger.seconds("01:02:03") == 3723);
        check("HH:mm", DayLedger.seconds("10:00") == 36000);
        check("not a time", DayLedger.seconds("-") == -1 && DayLedger.seconds("25:00:00") == -1);
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
