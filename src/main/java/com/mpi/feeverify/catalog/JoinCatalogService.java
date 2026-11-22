package com.mpi.feeverify.catalog;

import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 关联语义目录：维护事实表与维表连接的主外键、有效区间、边界规则（闭开）、
 * 维表修订频率与期望扇出。目录按版本管理，创建时把维表区间与合并信息快照进
 * version_intervals —— 目录即语义快照：已运行的匹配/沙箱继续引用旧目录版本，
 * 新目录触发独立验证。修订目录时计算区间差异作为历史重算窗口。
 */
@Service
public class JoinCatalogService {

    /** 时态机构边（本目录的核心边）。 */
    public static final String TEMPORAL_EDGE = "fee_detail__dim_org_version";

    private final JdbcTemplate jdbc;

    public JoinCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 从当前维表创建新目录版本；旧版本的沙箱/映射标记滞后，返回修订影响。 */
    @Transactional
    public Map<String, Object> createVersion(String revisionReason, String dimRevisionFreq) {
        Map<String, List<TemporalSemantics.Interval>> intervals = loadIntervals();
        Map<String, Map<String, Object>> merges = loadMerges();
        Map<String, Object> intervalsSnapshot = Map.of("intervals", intervalsToJson(intervals),
                "merges", merges);

        Map<String, Object> previous = activeOrNull();
        int nextVersion = previous == null ? 1
                : ((Number) previous.get("version_no")).intValue() + 1;
        String freq = dimRevisionFreq == null ? "MEDIUM" : dimRevisionFreq;

        jdbc.update("UPDATE join_catalog SET status = 'SUPERSEDED' WHERE status = 'ACTIVE'");
        Long catalogId = jdbc.queryForObject(
                "INSERT INTO join_catalog (version_no, status, revision_reason) VALUES (?,?,?) RETURNING id",
                Long.class, nextVersion, "ACTIVE",
                revisionReason == null ? "初始目录" : revisionReason);

        insertEdge(catalogId, "fee_detail__dim_org", "fee_detail", "org_code", "dim_org", "org_code",
                "EQUI", null, null, null, null, null, "LOW", null,
                "机构当前视图等值关联（主键 org_code）");
        insertEdge(catalogId, TEMPORAL_EDGE, "fee_detail", "org_code", "dim_org_version",
                "(org_code,version_no)", "TEMPORAL_RANGE", "valid_from", "valid_to", "settle_date",
                "CLOSED_OPEN", "SUCCESSOR", freq, intervalsSnapshot,
                "机构版本时态关联：闭开区间 + 合并后继路由");
        insertEdge(catalogId, "fee_detail__dim_diagnosis", "fee_detail", "diagnosis_code",
                "dim_diagnosis", "diagnosis_code", "EQUI", null, null, null, null, null, "LOW",
                null, "诊断维表等值关联");
        insertEdge(catalogId, "fee_detail__dim_drug", "fee_detail", "drug_code", "dim_drug",
                "drug_code", "EQUI", null, null, null, null, null, "LOW", null, "药品维表等值关联");

        // 目录修订的连锁影响：旧目录的沙箱保留原目录快照（标记 CATALOG_STALE），
        // 旧映射构建标记 STALE（重建前滞后），区间差异即历史重算窗口
        Map<String, Object> impact = new LinkedHashMap<>();
        if (previous != null) {
            long prevId = ((Number) previous.get("id")).longValue();
            int staleRuns = jdbc.update(
                    "UPDATE sandbox_run SET status = 'CATALOG_STALE' WHERE catalog_id = ? AND status = 'RUN'",
                    prevId);
            int staleBuilds = jdbc.update(
                    "UPDATE org_map_build SET status = 'STALE' WHERE catalog_id = ? AND status = 'CURRENT'",
                    prevId);
            List<Map<String, Object>> recalcWindows = recalcWindows(prevId, intervals);
            impact.put("previousCatalogId", prevId);
            impact.put("previousVersionNo", previous.get("version_no"));
            impact.put("sandboxRunsMarkedStale", staleRuns);
            impact.put("mapBuildsMarkedStale", staleBuilds);
            impact.put("recalcWindows", recalcWindows);
            impact.put("note", "已运行沙箱保留原目录快照（CATALOG_STALE），新目录需独立验证；"
                    + "重算窗口内的历史归属需按新目录重算");
        } else {
            impact.put("previousCatalogId", null);
            impact.put("recalcWindows", List.of());
        }

        Map<String, Object> result = get(catalogId);
        result.put("impact", impact);
        return result;
    }

    private void insertEdge(long catalogId, String edgeName, String factTable, String factFk,
                            String dimTable, String dimKey, String semantics,
                            String validFromCol, String validToCol, String factDateCol,
                            String boundaryRule, String mergeRule, String freq,
                            Map<String, Object> versionIntervals, String note) {
        jdbc.update(
                "INSERT INTO join_catalog_edge (catalog_id, edge_name, fact_table, fact_fk, dim_table,"
                        + " dim_key, join_semantics, valid_from_col, valid_to_col, fact_date_col,"
                        + " boundary_rule, merge_rule, dim_revision_freq, expected_fanout,"
                        + " version_intervals, note)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,1.0,?::jsonb,?)",
                catalogId, edgeName, factTable, factFk, dimTable, dimKey, semantics,
                validFromCol, validToCol, factDateCol, boundaryRule, mergeRule, freq,
                versionIntervals == null ? null : Jsons.write(versionIntervals), note);
    }

    /** 两版目录时态边的区间差异 → 历史重算窗口。 */
    private List<Map<String, Object>> recalcWindows(long previousCatalogId,
                                                    Map<String, List<TemporalSemantics.Interval>> newIntervals) {
        Map<String, Object> prevEdge = edge(previousCatalogId, TEMPORAL_EDGE);
        Map<String, List<TemporalSemantics.Interval>> oldIntervals =
                intervalsOf(prevEdge.get("version_intervals"));
        return TemporalSemantics.diffIntervals(oldIntervals, newIntervals);
    }

    /** 当前维表区间（从 dim_org_version 读取）。 */
    public Map<String, List<TemporalSemantics.Interval>> loadIntervals() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT org_code, version_no, org_name, valid_from, valid_to FROM dim_org_version"
                        + " ORDER BY org_code, version_no");
        Map<String, List<TemporalSemantics.Interval>> map = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            String org = String.valueOf(r.get("org_code"));
            java.sql.Date to = (java.sql.Date) r.get("valid_to");
            map.computeIfAbsent(org, k -> new ArrayList<>()).add(new TemporalSemantics.Interval(
                    ((Number) r.get("version_no")).intValue(),
                    ((java.sql.Date) r.get("valid_from")).toLocalDate(),
                    to == null ? null : to.toLocalDate(),
                    String.valueOf(r.get("org_name"))));
        }
        return map;
    }

    /** 机构合并信息（来自 dim_org.merged_into / merge_effective）。 */
    public Map<String, Map<String, Object>> loadMerges() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT org_code, merged_into, merge_effective FROM dim_org WHERE merged_into IS NOT NULL");
        Map<String, Map<String, Object>> merges = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            merges.put(String.valueOf(r.get("org_code")), Map.of(
                    "mergedInto", String.valueOf(r.get("merged_into")),
                    "effective", String.valueOf(r.get("merge_effective"))));
        }
        return merges;
    }

    /** 从目录边的 version_intervals JSON 解析区间。 */
    @SuppressWarnings("unchecked")
    public Map<String, List<TemporalSemantics.Interval>> intervalsOf(Object versionIntervals) {
        Map<String, Object> root = Jsons.asMap(versionIntervals);
        Map<String, List<TemporalSemantics.Interval>> out = new TreeMap<>();
        if (root == null || !root.containsKey("intervals")) {
            return out;
        }
        Map<String, Object> intervals = (Map<String, Object>) root.get("intervals");
        for (Map.Entry<String, Object> e : intervals.entrySet()) {
            List<TemporalSemantics.Interval> list = new ArrayList<>();
            for (Map<String, Object> iv : (List<Map<String, Object>>) e.getValue()) {
                list.add(new TemporalSemantics.Interval(
                        ((Number) iv.get("v")).intValue(),
                        LocalDate.parse(String.valueOf(iv.get("from"))),
                        iv.get("to") == null ? null : LocalDate.parse(String.valueOf(iv.get("to"))),
                        String.valueOf(iv.get("name"))));
            }
            out.put(e.getKey(), list);
        }
        return out;
    }

    /** 从目录边的 version_intervals JSON 解析合并信息。 */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> mergesOf(Object versionIntervals) {
        Map<String, Object> root = Jsons.asMap(versionIntervals);
        if (root == null || !root.containsKey("merges")) {
            return Map.of();
        }
        return (Map<String, Map<String, Object>>) root.get("merges");
    }

    private Map<String, List<Map<String, Object>>> intervalsToJson(
            Map<String, List<TemporalSemantics.Interval>> intervals) {
        Map<String, List<Map<String, Object>>> json = new TreeMap<>();
        intervals.forEach((org, list) -> {
            List<Map<String, Object>> items = new ArrayList<>();
            for (TemporalSemantics.Interval iv : list) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("v", iv.versionNo());
                item.put("from", iv.validFrom().toString());
                item.put("to", iv.validTo() == null ? null : iv.validTo().toString());
                item.put("name", iv.name());
                items.add(item);
            }
            json.put(org, items);
        });
        return json;
    }

    public Map<String, Object> active() {
        Map<String, Object> row = activeOrNull();
        if (row == null) {
            throw ApiException.conflict("当前没有 ACTIVE 关联语义目录，请先调用 POST /api/temporal/catalogs");
        }
        return row;
    }

    private Map<String, Object> activeOrNull() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM join_catalog WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1");
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM join_catalog WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("关联语义目录不存在: " + id);
        }
        Map<String, Object> catalog = Jsons.normalizeRow(rows.get(0));
        catalog.put("edges", Jsons.normalizeRows(jdbc.queryForList(
                "SELECT edge_name, fact_table, fact_fk, dim_table, dim_key, join_semantics,"
                        + " valid_from_col, valid_to_col, fact_date_col, boundary_rule, merge_rule,"
                        + " dim_revision_freq, expected_fanout, version_intervals, note"
                        + " FROM join_catalog_edge WHERE catalog_id = ? ORDER BY id", id)));
        return catalog;
    }

    public Map<String, Object> edge(long catalogId, String edgeName) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM join_catalog_edge WHERE catalog_id = ? AND edge_name = ?",
                catalogId, edgeName);
        if (rows.isEmpty()) {
            throw ApiException.notFound("目录 " + catalogId + " 中不存在连接边 " + edgeName);
        }
        return rows.get(0);
    }

    public List<Map<String, Object>> list() {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, version_no, status, revision_reason, created_at FROM join_catalog ORDER BY id DESC"));
    }
}
