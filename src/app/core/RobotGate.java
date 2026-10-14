package app.core;

/**
 * One mouse, one robot. Every piece of work that moves the pointer or types
 * (a scan burst, a range run, a file run, a print run, a lot correction, the
 * Export click of a verification, the focus click back on the app) holds the
 * gate while it does it.
 *
 * Each mode used to check its own flags only: a scan verification could click
 * Export in the middle of the next scan burst, and a range correction could
 * type while the scan worker was draining. The gate is not re-entrant on
 * purpose: two pieces of work of the same mode are two robots as well.
 */
public final class RobotGate {

    /** Proof of holding the gate. Releasing twice is harmless. */
    public static final class Lease {
        private final String owner;
        private boolean released;

        private Lease(String owner) {
            this.owner = owner;
        }

        public String owner() {
            return owner;
        }

        public void release() {
            synchronized (RobotGate.class) {
                if (released) return;
                released = true;
                if (holder == this) {
                    holder = null;
                    RobotGate.class.notifyAll();
                }
            }
        }
    }

    private static Lease holder;

    private RobotGate() {}

    /** The gate now, or null (and nothing changes) when someone holds it. */
    public static synchronized Lease tryAcquire(String owner) {
        if (holder != null) return null;
        holder = new Lease(owner);
        return holder;
    }

    /** Waits up to {@code timeoutMs} for the gate; null when it stayed taken. */
    public static synchronized Lease acquire(String owner, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (holder != null) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return null;
            RobotGate.class.wait(left);
        }
        holder = new Lease(owner);
        return holder;
    }

    /** Waits as long as it takes. */
    public static synchronized Lease acquire(String owner) throws InterruptedException {
        while (holder != null) RobotGate.class.wait();
        holder = new Lease(owner);
        return holder;
    }

    /** Who holds the gate, for the operator's message; null when free. */
    public static synchronized String owner() {
        return holder == null ? null : holder.owner;
    }
}
