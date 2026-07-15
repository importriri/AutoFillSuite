package app.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

// outcome of one verification pass. the two "blocking" lists and the wrong-lot
// map keep the light red; registrations counts how many rows carry the expected
// lot per label — re-running the same batch by mistake just raises the count.
public final class VerificationResult {

    /** How a wrong lot reads to a human. ONE definition: this used to be a magic
     *  string re-parsed in three places, and a stray edit silently broke it. */
    public static final String ARROW = " -> ";

    private final List<String> missing;        // expected, never showed up in the export
    private final List<String> notRegistered;  // printed (rows exist) but never registered
    private final Map<String, String> wrongLots;  // label -> the lot actually found
    private final List<String> duplicates;     // registered more than once (kept for the log)
    private final Map<String, Integer> registrations;  // label -> rows with the expected lot
    private final int matched;                 // lined up cleanly

    public VerificationResult(List<String> missing, List<String> notRegistered,
                              Map<String, String> wrongLots, List<String> duplicates,
                              Map<String, Integer> registrations, int matched) {
        this.missing       = Collections.unmodifiableList(missing);
        this.notRegistered = Collections.unmodifiableList(notRegistered);
        this.wrongLots     = Collections.unmodifiableMap(wrongLots);
        this.duplicates    = Collections.unmodifiableList(duplicates);
        this.registrations = Collections.unmodifiableMap(registrations);
        this.matched       = matched;
    }

    public List<String> getMissing()       { return missing; }
    public List<String> getNotRegistered() { return notRegistered; }
    public List<String> getDuplicates()    { return duplicates; }
    public Map<String, Integer> getRegistrationCounts() { return registrations; }
    public int getMatched()                { return matched; }

    /** label -> lot actually found. Structured: nobody has to parse anything. */
    public Map<String, String> getWrongLots() { return wrongLots; }

    /** The same, spelled out for the log and for humans. */
    public List<String> getWrongLot() {
        List<String> out = new ArrayList<>(wrongLots.size());
        for (Map.Entry<String, String> e : wrongLots.entrySet()) {
            out.add(e.getKey() + ARROW + e.getValue());
        }
        return out;
    }

    // green only when nothing is missing, every label carries a lot, and every
    // lot is the one we expected. anything else and the operator has to look.
    public boolean isClean() {
        return missing.isEmpty() && notRegistered.isEmpty() && wrongLots.isEmpty();
    }

    public int totalProblems() {
        return missing.size() + notRegistered.size() + wrongLots.size();
    }

    // total registration rows of the expected labels: a run launched twice by
    // mistake shows up here as ~2x the matched count
    public int totalRegistrations() {
        int sum = 0;
        for (int n : registrations.values()) sum += n;
        return sum;
    }
}
