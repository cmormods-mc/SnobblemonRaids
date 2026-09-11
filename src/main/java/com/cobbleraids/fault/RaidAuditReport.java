package com.cobbleraids.fault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a consistency sweep found.
 *
 * <p>Kept free of Minecraft types so the reporting rules -- severity, ordering, capping, the summary
 * line -- can be tested without a server. The sweep that produces one lives in
 * {@link RaidConsistencyAudit}.
 */
public final class RaidAuditReport {

    /** How bad a finding is, worst first when reported. */
    public enum Severity { ERROR, WARN }

    /**
     * One violated invariant.
     *
     * @param category short stable key, so repeated findings can be counted and grepped
     * @param detail   what specifically was wrong, including the id needed to chase it
     */
    public record Finding(Severity severity, String category, String detail) {}

    /**
     * Past this many findings of one category the rest are counted rather than listed. A genuinely
     * broken server can violate the same invariant for every raid it holds, and a log flood helps
     * nobody -- the count is the useful part once the first few examples are in hand.
     */
    static final int MAX_LISTED_PER_CATEGORY = 5;

    private final List<Finding> findings = new ArrayList<>();
    private final Map<String, Integer> countsByCategory = new LinkedHashMap<>();
    private int checksRun;

    /** Records that an invariant was evaluated, whether or not it held. */
    public void checked() { checksRun++; }

    public void error(String category, String detail) { add(Severity.ERROR, category, detail); }

    public void warn(String category, String detail) { add(Severity.WARN, category, detail); }

    private void add(Severity severity, String category, String detail) {
        int seen = countsByCategory.merge(category, 1, Integer::sum);
        if (seen <= MAX_LISTED_PER_CATEGORY) findings.add(new Finding(severity, category, detail));
    }

    public boolean isClean() { return countsByCategory.isEmpty(); }

    public int checksRun() { return checksRun; }

    /** Total violations, including any elided by the per-category cap. */
    public int violationCount() {
        int total = 0;
        for (int count : countsByCategory.values()) total += count;
        return total;
    }

    public int violationCount(String category) { return countsByCategory.getOrDefault(category, 0); }

    /** Listed findings, errors before warnings; insertion order is preserved within a severity. */
    public List<Finding> findings() {
        List<Finding> ordered = new ArrayList<>(findings);
        ordered.sort((a, b) -> a.severity().compareTo(b.severity()));
        return Collections.unmodifiableList(ordered);
    }

    /** Categories that were violated, with their full counts. */
    public Map<String, Integer> countsByCategory() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(countsByCategory));
    }

    public boolean hasErrors() {
        return findings.stream().anyMatch(finding -> finding.severity() == Severity.ERROR);
    }

    /** One line summarising the sweep, suitable for a log or a command response. */
    public String summary() {
        if (isClean()) return "raid state consistent (" + checksRun + " checks)";
        StringBuilder builder = new StringBuilder();
        builder.append(violationCount()).append(" inconsistenc")
                .append(violationCount() == 1 ? "y" : "ies")
                .append(" across ").append(countsByCategory.size())
                .append(countsByCategory.size() == 1 ? " category" : " categories")
                .append(" (").append(checksRun).append(" checks)");
        return builder.toString();
    }

    /** Every listed finding as a log-ready line, plus a tail line for anything the cap elided. */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        for (Finding finding : findings()) {
            lines.add(finding.severity() + " " + finding.category() + ": " + finding.detail());
        }
        for (Map.Entry<String, Integer> entry : countsByCategory.entrySet()) {
            int elided = entry.getValue() - MAX_LISTED_PER_CATEGORY;
            if (elided > 0) {
                lines.add("... and " + elided + " more " + entry.getKey());
            }
        }
        return lines;
    }
}
