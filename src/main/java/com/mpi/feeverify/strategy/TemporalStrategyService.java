package com.mpi.feeverify.strategy;

import com.mpi.feeverify.cardinality.CardinalityService;
import com.mpi.feeverify.catalog.JoinCatalogService;
import com.mpi.feeverify.catalog.TemporalSemantics;
import com.mpi.feeverify.support.Jsons;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时态连接策略选择与治理视图。
 * 方案A（RANGE）：查询时按结算日做时态范围匹配——目录修订立即反映新解释，
 * 但热点范围过滤成本更高；方案B（MAP）：按日期展开的有效映射——连接接近等值查找，
 * 但机构合并、回溯更正时需维护映射并管理重验窗口（重建滞后期间归属可能过时）。
 * 按维表变更频率、查询日期跨度、允许的重建时延选择，并向治理人员展示
 * 该选择如何影响灰度覆盖与历史重算。
 */
@Service
public class TemporalStrategyService {

    private final JdbcTemplate jdbc;
    private final JoinCatalogService catalogService;
    private final CardinalityService cardinalityService;

    public TemporalStrategyService(JdbcTemplate jdbc, JoinCatalogService catalogService,
                                   CardinalityService cardinalityService) {
        this.jdbc = jdbc;
        this.catalogService = catalogService;
        this.cardinalityService = cardinalityService;
    }

    /**
     * 策略推荐。
     *
     * @param dateSpanDays          典型查询的日期跨度（天）
     * @param allowedRebuildMinutes 允许的映射重建时延（分钟）；null 表示不敏感
     */
    public Map<String, Object> recommend(long dateSpanDays, Long allowedRebuildMinutes) {
        Map<String, Object> catalog = catalogService.active();
        long catalogId = ((Number) catalog.get("id")).longValue();
        Map<String, Object> edge = Jsons.normalizeRow(jdbc.queryForMap(
                "SELECT * FROM join_catalog_edge WHERE catalog_id = ? AND edge_name = ?",
                catalogId, JoinCatalogService.TEMPORAL_EDGE));
        String freq = String.valueOf(edge.get("dim_revision_freq"));
        Integer measuredRevisions = jdbc.queryForObject("SELECT COUNT(*) - 1 FROM join_catalog", Integer.class);

        List<String> reasons = new ArrayList<>();
        String recommended;
        if ("HIGH".equals(freq)) {
            recommended = "RANGE";
            reasons.add("维表修订频率 HIGH：映射需频繁重建，滞后窗口风险高，RANGE 让目录修订立即反映新解释");
        } else if (allowedRebuildMinutes != null && allowedRebuildMinutes < 30
                && measuredRevisions != null && measuredRevisions > 0) {
            recommended = "RANGE";
            reasons.add("允许重建时延 " + allowedRebuildMinutes + " 分钟 < 30 且已发生 "
                    + measuredRevisions + " 次修订：重建滞后不可接受，选 RANGE");
        } else if (dateSpanDays >= 90 && !"HIGH".equals(freq)) {
            recommended = "MAP";
            reasons.add("查询日期跨度 " + dateSpanDays + " 天 ≥ 90 且修订频率 " + freq
                    + "：展开映射让连接接近等值查找，热点范围过滤成本最低");
        } else {
            recommended = "RANGE";
            reasons.add("跨度 " + dateSpanDays + " 天且修订频率 " + freq
                    + "：RANGE 免去映射维护与重验窗口管理，范围过滤成本可接受");
        }

        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("dimRevisionFreq", freq);
        factors.put("measuredCatalogRevisions", measuredRevisions);
        factors.put("dateSpanDays", dateSpanDays);
        factors.put("allowedRebuildMinutes", allowedRebuildMinutes);

        List<Map<String, Object>> alternatives = List.of(
                Map.of("mode", "RANGE",
                        "pros", "目录修订立即反映新解释；无需维护映射；无重建滞后窗口",
                        "cons", "热点范围过滤成本更高（每次查询执行区间匹配）"),
                Map.of("mode", "MAP",
                        "pros", "连接接近等值查找，大跨度查询成本最低；合并后继路由已物化",
                        "cons", "机构合并/回溯更正需重建映射并管理重验窗口；重建滞后期间归属可能过时"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recommended", recommended);
        result.put("reasons", reasons);
        result.put("factors", factors);
        result.put("alternatives", alternatives);
        result.put("grayCoverageImpact", grayCoverage());
        result.put("historicalRecalcImpact", historicalRecalc(catalogId));
        return result;
    }

    /** 治理视图：目录版本、映射构建、沙箱状态、灰度覆盖与历史重算窗口。 */
    public Map<String, Object> governance() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("catalogs", catalogService.list());
        Map<String, Object> active = catalogService.active();
        long activeId = ((Number) active.get("id")).longValue();
        view.put("activeCatalogId", activeId);
        view.put("temporalEdge", Jsons.normalizeRow(jdbc.queryForMap(
                "SELECT edge_name, join_semantics, boundary_rule, merge_rule, dim_revision_freq,"
                        + " expected_fanout FROM join_catalog_edge WHERE catalog_id = ? AND edge_name = ?",
                activeId, JoinCatalogService.TEMPORAL_EDGE)));
        view.put("mapBuilds", Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, catalog_id, from_date, to_date, row_count, status, built_at"
                        + " FROM org_map_build ORDER BY id DESC")));
        view.put("sandboxRuns", Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, name, snapshot_id, catalog_id, map_build_id, status, created_at"
                        + " FROM sandbox_run ORDER BY id DESC LIMIT 20")));
        view.put("matchRuns", Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, catalog_id, mode, map_build_id, matched, successor, unmatched, conflicts,"
                        + " created_at FROM match_run ORDER BY id DESC LIMIT 20")));
        view.put("cardinalityAnomalies", cardinalityService.anomalies(activeId));
        view.put("grayCoverage", grayCoverage());
        view.put("historicalRecalc", historicalRecalc(activeId));
        return view;
    }

    /** 灰度覆盖：发布门禁状态分布 + 各模式沙箱验证覆盖。 */
    private Map<String, Object> grayCoverage() {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("gatesByStatus", Jsons.normalizeRows(jdbc.queryForList(
                "SELECT status, COUNT(*) AS cnt FROM release_gate GROUP BY status ORDER BY status")));
        coverage.put("sandboxByStatus", Jsons.normalizeRows(jdbc.queryForList(
                "SELECT status, COUNT(*) AS cnt FROM sandbox_run GROUP BY status ORDER BY status")));
        coverage.put("sandboxByMode", Jsons.normalizeRows(jdbc.queryForList(
                "SELECT CASE WHEN map_build_id IS NULL THEN 'RANGE' ELSE 'MAP' END AS mode,"
                        + " COUNT(*) AS cnt FROM sandbox_run GROUP BY 1 ORDER BY 1")));
        coverage.put("note", "RANGE 验证覆盖随目录修订立即失效（CATALOG_STALE）需独立重验；"
                + "MAP 覆盖还受映射构建滞后窗口影响");
        return coverage;
    }

    /** 历史重算：最近两版目录的区间差异（重算窗口）+ 滞后映射构建。 */
    private Map<String, Object> historicalRecalc(long activeCatalogId) {
        Map<String, Object> recalc = new LinkedHashMap<>();
        List<Map<String, Object>> catalogs = jdbc.queryForList(
                "SELECT id FROM join_catalog ORDER BY id DESC LIMIT 2");
        List<Map<String, Object>> windows = new ArrayList<>();
        if (catalogs.size() == 2) {
            long newId = ((Number) catalogs.get(0).get("id")).longValue();
            long oldId = ((Number) catalogs.get(1).get("id")).longValue();
            Map<String, Object> newEdge = jdbc.queryForMap(
                    "SELECT version_intervals FROM join_catalog_edge WHERE catalog_id = ? AND edge_name = ?",
                    newId, JoinCatalogService.TEMPORAL_EDGE);
            Map<String, Object> oldEdge = jdbc.queryForMap(
                    "SELECT version_intervals FROM join_catalog_edge WHERE catalog_id = ? AND edge_name = ?",
                    oldId, JoinCatalogService.TEMPORAL_EDGE);
            windows = TemporalSemantics.diffIntervals(
                    catalogService.intervalsOf(oldEdge.get("version_intervals")),
                    catalogService.intervalsOf(newEdge.get("version_intervals")));
        }
        recalc.put("recalcWindows", windows);
        recalc.put("staleMapBuilds", Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, catalog_id, from_date, to_date, status FROM org_map_build"
                        + " WHERE status = 'STALE' ORDER BY id DESC")));
        recalc.put("note", windows.isEmpty()
                ? "当前无未重算的区间差异"
                : "重算窗口内的历史归属需按新目录重算；窗口内已发布的聚合结果应标记待复核");
        return recalc;
    }
}
