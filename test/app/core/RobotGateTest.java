package app.core;

// one mouse, one robot: the gate every mode takes before it clicks or types.
//
//   java -cp out app.core.RobotGateTest
public final class RobotGateTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        RobotGate.Lease a = RobotGate.tryAcquire("Scansione");
        check("a free gate is taken", a != null && "Scansione".equals(RobotGate.owner()));
        check("a taken gate refuses another mode", RobotGate.tryAcquire("Intervallo") == null);
        check("...and the same mode too: two jobs of one mode are two robots",
              RobotGate.tryAcquire("Scansione") == null);
        long t0 = System.currentTimeMillis();
        check("waiting with a limit gives up", RobotGate.acquire("Da file", 150) == null
              && System.currentTimeMillis() - t0 >= 140);

        final RobotGate.Lease[] got = new RobotGate.Lease[1];
        Thread waiter = new Thread(() -> {
            try { got[0] = RobotGate.acquire("Da file"); } catch (InterruptedException ignored) { }
        });
        waiter.start();
        Thread.sleep(100);
        check("a waiter waits while the gate is held", got[0] == null && waiter.isAlive());
        a.release();
        waiter.join(2000);
        check("released, the waiter gets it", got[0] != null && "Da file".equals(RobotGate.owner()));
        a.release();
        check("releasing an old lease twice does not free someone else's", "Da file".equals(RobotGate.owner()));
        got[0].release();
        check("released, the gate is free", RobotGate.owner() == null);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else      { failed++; System.out.println("  FAIL " + name); }
    }
}
