package com.mpi.feeverify.catalog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 时态关联语义纯函数：闭开区间 [valid_from, valid_to) 的版本选择、
 * 目录版本间的区间差异（历史重算窗口）计算。不依赖数据库，便于单测。
 */
public final class TemporalSemantics {

    private TemporalSemantics() {
    }

    /** 维表版本区间；validTo 为 null 表示当前有效（上界无穷）。 */
    public record Interval(int versionNo, LocalDate validFrom, LocalDate validTo, String name) {

        public boolean covers(LocalDate day) {
            return !day.isBefore(validFrom) && (validTo == null || day.isBefore(validTo));
        }

        /** 闭开约定下未命中的人类可读原因。 */
        public String exclusionReason(LocalDate day) {
            if (day.isBefore(validFrom)) {
                return "结算日 " + day + " 早于 valid_from " + validFrom + "，未进入区间";
            }
            return "结算日 " + day + " ≥ valid_to " + validTo + "，闭开区间上界排除";
        }
    }

    /** 单个候选版本的取舍说明。 */
    public record Candidate(Interval interval, boolean selected, String reason) {
    }

    /** 选择结果：MATCHED（唯一命中）/ CONFLICT（区间重叠多命中）/ UNMATCHED（无命中）。 */
    public record Selection(String status, Interval selected, List<Candidate> candidates) {
    }

    /** 按闭开约定 [valid_from, valid_to) 为结算日选择版本，保留每个候选的取舍依据。 */
    public static Selection select(List<Interval> intervals, LocalDate day) {
        List<Candidate> candidates = new ArrayList<>();
        List<Interval> hits = new ArrayList<>();
        for (Interval iv : intervals) {
            if (iv.covers(day)) {
                hits.add(iv);
            }
        }
        Interval selected = hits.size() == 1 ? hits.get(0) : null;
        for (Interval iv : intervals) {
            boolean hit = iv.covers(day);
            String reason;
            if (hit) {
                reason = "valid_from " + iv.validFrom() + " ≤ 结算日 " + day + " < valid_to "
                        + (iv.validTo() == null ? "∞（当前有效）" : iv.validTo()) + "，命中";
            } else {
                reason = iv.exclusionReason(day);
            }
            candidates.add(new Candidate(iv, hit && hits.size() == 1, reason));
        }
        String status = hits.size() == 1 ? "MATCHED" : hits.isEmpty() ? "UNMATCHED" : "CONFLICT";
        return new Selection(status, selected, candidates);
    }

    /**
     * 计算两版目录的区间差异，给出历史重算窗口。
     *
     * @return 每项 {org, versionNo, change, oldFrom, oldTo, newFrom, newTo, affectedFrom, affectedTo}
     */
    public static List<Map<String, Object>> diffIntervals(Map<String, List<Interval>> oldMap,
                                                          Map<String, List<Interval>> newMap) {
        List<Map<String, Object>> diffs = new ArrayList<>();
        Map<String, List<Interval>> union = new TreeMap<>();
        union.putAll(oldMap);
        newMap.forEach((org, ivs) -> union.merge(org, ivs, (a, b) -> b));
        for (String org : union.keySet()) {
            List<Interval> olds = oldMap.getOrDefault(org, List.of());
            List<Interval> news = newMap.getOrDefault(org, List.of());
            Map<Integer, Interval> oldByV = new LinkedHashMap<>();
            olds.forEach(iv -> oldByV.put(iv.versionNo(), iv));
            Map<Integer, Interval> newByV = new LinkedHashMap<>();
            news.forEach(iv -> newByV.put(iv.versionNo(), iv));
            for (int v : newByV.keySet()) {
                Interval o = oldByV.get(v);
                Interval n = newByV.get(v);
                if (o == null) {
                    diffs.add(entry(org, n, "ADDED", null, null, n.validFrom, n.validTo,
                            n.validFrom, n.validTo));
                } else if (!o.validFrom.equals(n.validFrom) || !eq(o.validTo, n.validTo)) {
                    // 重算窗口 = 两版归属真正分歧的区间（边界变化的并集），而非整个版本区间
                    LocalDate affFrom = null;
                    LocalDate affTo = null;
                    if (!o.validFrom.equals(n.validFrom)) {
                        affFrom = o.validFrom.isBefore(n.validFrom) ? o.validFrom : n.validFrom;
                        affTo = o.validFrom.isAfter(n.validFrom) ? o.validFrom : n.validFrom;
                    }
                    if (!eq(o.validTo, n.validTo)) {
                        LocalDate s = minTo(o.validTo, n.validTo);
                        LocalDate e = maxTo(o.validTo, n.validTo);
                        if (s != null && (affFrom == null || s.isBefore(affFrom))) {
                            affFrom = s;
                        }
                        affTo = affTo == null ? e : maxTo(affTo, e);
                    }
                    diffs.add(entry(org, n, "REVISED", o.validFrom, o.validTo, n.validFrom, n.validTo,
                            affFrom, affTo));
                }
            }
            for (int v : oldByV.keySet()) {
                if (!newByV.containsKey(v)) {
                    Interval o = oldByV.get(v);
                    diffs.add(entry(org, o, "REMOVED", o.validFrom, o.validTo, null, null,
                            o.validFrom, o.validTo));
                }
            }
        }
        return diffs;
    }

    private static boolean eq(LocalDate a, LocalDate b) {
        return a == null ? b == null : a.equals(b);
    }

    private static LocalDate maxTo(LocalDate a, LocalDate b) {
        if (a == null || b == null) {
            return null; // 任一侧开放，影响窗口上界开放
        }
        return a.isAfter(b) ? a : b;
    }

    /** 两个上界中较早者；null（开放）视为无穷大，故返回另一侧。 */
    private static LocalDate minTo(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    private static Map<String, Object> entry(String org, Interval iv, String change,
                                             LocalDate oldFrom, LocalDate oldTo,
                                             LocalDate newFrom, LocalDate newTo,
                                             LocalDate affectedFrom, LocalDate affectedTo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("org", org);
        m.put("versionNo", iv.versionNo());
        m.put("change", change);
        m.put("oldFrom", oldFrom == null ? null : oldFrom.toString());
        m.put("oldTo", oldTo == null ? null : oldTo.toString());
        m.put("newFrom", newFrom == null ? null : newFrom.toString());
        m.put("newTo", newTo == null ? null : newTo.toString());
        m.put("affectedFrom", affectedFrom == null ? null : affectedFrom.toString());
        m.put("affectedTo", affectedTo == null ? null : affectedTo.toString());
        return m;
    }
}
