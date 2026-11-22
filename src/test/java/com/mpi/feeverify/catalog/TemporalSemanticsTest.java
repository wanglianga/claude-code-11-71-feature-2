package com.mpi.feeverify.catalog;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 时态语义纯函数测试：闭开区间选择、冲突/未命中、目录区间差异（重算窗口）。 */
class TemporalSemanticsTest {

    private static final List<TemporalSemantics.Interval> ORG001 = List.of(
            new TemporalSemantics.Interval(1, LocalDate.parse("2025-01-01"),
                    LocalDate.parse("2026-01-01"), "杭州市第一人民医院"),
            new TemporalSemantics.Interval(2, LocalDate.parse("2026-01-01"),
                    null, "杭州市第一人民医院（合并中医院）"));

    @Test
    void boundaryDateGoesToNewVersionUnderClosedOpen() {
        TemporalSemantics.Selection before = TemporalSemantics.select(ORG001, LocalDate.parse("2025-12-31"));
        assertEquals("MATCHED", before.status());
        assertEquals(1, before.selected().versionNo());

        // 结算日正处交接边界：闭开约定归属新版本 v2，且每个候选都有取舍依据
        TemporalSemantics.Selection on = TemporalSemantics.select(ORG001, LocalDate.parse("2026-01-01"));
        assertEquals("MATCHED", on.status());
        assertEquals(2, on.selected().versionNo());
        assertEquals(2, on.candidates().size());
        TemporalSemantics.Candidate excluded = on.candidates().get(0);
        assertFalse(excluded.selected());
        assertTrue(excluded.reason().contains("闭开区间上界排除"), "被排除候选须说明闭开依据");
        TemporalSemantics.Candidate selected = on.candidates().get(1);
        assertTrue(selected.selected());
        assertTrue(selected.reason().contains("命中"));
    }

    @Test
    void unmatchedWhenNoVersionCovers() {
        TemporalSemantics.Selection s = TemporalSemantics.select(ORG001, LocalDate.parse("2024-12-31"));
        assertEquals("UNMATCHED", s.status());
        assertNull(s.selected());
    }

    @Test
    void conflictWhenIntervalsOverlap() {
        List<TemporalSemantics.Interval> overlap = List.of(
                new TemporalSemantics.Interval(1, LocalDate.parse("2025-01-01"),
                        LocalDate.parse("2026-02-01"), "v1"),
                new TemporalSemantics.Interval(2, LocalDate.parse("2026-01-01"),
                        null, "v2"));
        TemporalSemantics.Selection s = TemporalSemantics.select(overlap, LocalDate.parse("2026-01-15"));
        assertEquals("CONFLICT", s.status());
    }

    @Test
    void diffIntervalsComputesTrueRecalcWindow() {
        Map<String, List<TemporalSemantics.Interval>> oldMap = Map.of("ORG001", ORG001);
        Map<String, List<TemporalSemantics.Interval>> newMap = Map.of("ORG001", List.of(
                new TemporalSemantics.Interval(1, LocalDate.parse("2025-01-01"),
                        LocalDate.parse("2025-12-15"), "v1"),
                new TemporalSemantics.Interval(2, LocalDate.parse("2025-12-15"),
                        null, "v2")));
        List<Map<String, Object>> diffs = TemporalSemantics.diffIntervals(oldMap, newMap);
        assertEquals(2, diffs.size(), "v1/v2 两个版本均被修订");
        for (Map<String, Object> d : diffs) {
            assertEquals("ORG001", d.get("org"));
            assertEquals("REVISED", d.get("change"));
            // 重算窗口 = 归属真正分歧的区间 [2025-12-15, 2026-01-01)
            assertEquals("2025-12-15", d.get("affectedFrom"));
            assertEquals("2026-01-01", d.get("affectedTo"));
        }
    }

    @Test
    void diffIntervalsDetectsAddedAndRemoved() {
        Map<String, List<TemporalSemantics.Interval>> oldMap = Map.of("ORG005", List.of(
                new TemporalSemantics.Interval(1, LocalDate.parse("2025-01-01"), null, "v1")));
        Map<String, List<TemporalSemantics.Interval>> newMap = Map.of("ORG005", List.of(
                new TemporalSemantics.Interval(1, LocalDate.parse("2025-01-01"),
                        LocalDate.parse("2026-03-01"), "v1"),
                new TemporalSemantics.Interval(2, LocalDate.parse("2026-03-01"), null, "v2")));
        List<Map<String, Object>> diffs = TemporalSemantics.diffIntervals(oldMap, newMap);
        assertTrue(diffs.stream().anyMatch(d -> "ADDED".equals(d.get("change"))));
        assertTrue(diffs.stream().anyMatch(d -> "REVISED".equals(d.get("change"))));
    }
}
