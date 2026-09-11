package com.cobbleraids.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Reporting rules for the consistency sweep.
 *
 * <p>Worth testing rather than eyeballing, because this is the output an operator reads at three in
 * the morning when something is wrong, and because a detector that floods the log is as useless as
 * one that stays silent. Both failure modes are pinned here.
 */
class RaidAuditReportTest {

    @Test
    @DisplayName("a sweep that finds nothing says so, and counts what it checked")
    void cleanReport() {
        RaidAuditReport report = new RaidAuditReport();
        report.checked();
        report.checked();

        assertTrue(report.isClean());
        assertFalse(report.hasErrors());
        assertEquals(0, report.violationCount());
        assertEquals(2, report.checksRun());
        assertEquals("raid state consistent (2 checks)", report.summary());
        assertTrue(report.lines().isEmpty(), "a clean sweep must print nothing at all");
    }

    @Test
    @DisplayName("errors are reported before warnings")
    void errorsSortFirst() {
        RaidAuditReport report = new RaidAuditReport();
        report.warn("lobby-without-boss", "lobby A");
        report.error("stranded-session", "raid B");
        report.warn("lobby-without-boss", "lobby C");

        List<RaidAuditReport.Finding> findings = report.findings();
        assertEquals(RaidAuditReport.Severity.ERROR, findings.get(0).severity());
        assertEquals("stranded-session", findings.get(0).category());
        assertTrue(report.hasErrors());
    }

    @Test
    @DisplayName("findings within a severity keep the order they were found in")
    void insertionOrderPreserved() {
        RaidAuditReport report = new RaidAuditReport();
        report.warn("a", "first");
        report.warn("b", "second");
        report.warn("c", "third");

        assertEquals(List.of("first", "second", "third"),
                report.findings().stream().map(RaidAuditReport.Finding::detail).toList());
    }

    @Test
    @DisplayName("a category past the cap is counted, not listed")
    void floodIsCapped() {
        RaidAuditReport report = new RaidAuditReport();
        int found = RaidAuditReport.MAX_LISTED_PER_CATEGORY + 20;
        for (int i = 0; i < found; i++) report.error("leaked-raid-slot", "slot " + i);

        assertEquals(RaidAuditReport.MAX_LISTED_PER_CATEGORY, report.findings().size(),
                "a broken server can violate one invariant for every raid it holds");
        assertEquals(found, report.violationCount(), "but the true total must survive");
        assertEquals(found, report.violationCount("leaked-raid-slot"));
    }

    @Test
    @DisplayName("the elided remainder is still mentioned, so nothing looks smaller than it is")
    void elidedRemainderIsReported() {
        RaidAuditReport report = new RaidAuditReport();
        for (int i = 0; i < RaidAuditReport.MAX_LISTED_PER_CATEGORY + 3; i++) {
            report.warn("orphaned-glow-team-member", "member " + i);
        }

        List<String> lines = report.lines();
        assertEquals(RaidAuditReport.MAX_LISTED_PER_CATEGORY + 1, lines.size());
        assertTrue(lines.getLast().contains("3 more orphaned-glow-team-member"), lines.getLast());
    }

    @Test
    @DisplayName("the cap is per category, so one noisy invariant cannot hide another")
    void capIsPerCategory() {
        RaidAuditReport report = new RaidAuditReport();
        for (int i = 0; i < 50; i++) report.warn("orphaned-glow-team-member", "member " + i);
        report.error("leaked-finalization-claim", "1 raid still claimed");

        assertTrue(report.findings().stream()
                        .anyMatch(finding -> finding.category().equals("leaked-finalization-claim")),
                "a single serious finding must not be crowded out by a flood of a different kind");
    }

    @Test
    @DisplayName("the summary counts every violation and every category")
    void summaryCountsEverything() {
        RaidAuditReport report = new RaidAuditReport();
        report.checked();
        report.error("stranded-session", "raid A");
        report.warn("lobby-without-boss", "lobby B");
        report.warn("lobby-without-boss", "lobby C");

        assertEquals("3 inconsistencies across 2 categories (1 checks)", report.summary());
    }

    @Test
    @DisplayName("a single violation reads as singular")
    void singularSummary() {
        RaidAuditReport report = new RaidAuditReport();
        report.error("stranded-session", "raid A");

        assertEquals("1 inconsistency across 1 category (0 checks)", report.summary());
    }

    @Test
    @DisplayName("the findings list cannot be written back into")
    void findingsAreReadOnly() {
        RaidAuditReport report = new RaidAuditReport();
        report.warn("a", "one");

        assertThrows(UnsupportedOperationException.class,
                () -> report.findings().add(new RaidAuditReport.Finding(
                        RaidAuditReport.Severity.ERROR, "b", "two")));
        assertThrows(UnsupportedOperationException.class, () -> report.countsByCategory().put("c", 1));
    }

    @Test
    @DisplayName("every line carries its severity, category and the id needed to chase it")
    void linesAreSelfContained() {
        RaidAuditReport report = new RaidAuditReport();
        report.error("leaked-raid-slot", "natural raid slot still held for destroyed boss abc-123");

        String line = report.lines().getFirst();
        assertTrue(line.startsWith("ERROR leaked-raid-slot: "), line);
        assertTrue(line.contains("abc-123"), "a finding with no id in it cannot be acted on");
    }
}
