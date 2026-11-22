package com.mpi.feeverify.matcher;

import com.mpi.feeverify.catalog.JoinCatalogService;
import com.mpi.feeverify.catalog.OrgMapBuildService;
import com.mpi.feeverify.catalog.TemporalSemantics;
import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 有效期匹配器：为每笔结算产生可解释的机构版本归属。
 * RANGE 模式按目录版本快照中的区间做闭开匹配（目录修订立即反映新解释）；
 * MAP 模式按构建批次的展开映射做等值查找（连接接近等值，重建前存在滞后窗口）。
 * 结算日正处交接边界时按目录闭开约定选定一版并在 evidence 中留下取舍依据；
 * 合并机构的结算在生效日后按目录 SUCCESSOR 规则路由到后继机构版本。
 */
@Service
public class ValidityMatcherService {

    /** 批量匹配的单次上限（演示规模全量足够，防御性限制）。 */
    private static final int BATCH_LIMIT = 30000;

    private final JdbcTemplate jdbc;
    private final JoinCatalogService catalogService;
    private final OrgMapBuildService mapBuildService;

    public ValidityMatcherService(JdbcTemplate jdbc, JoinCatalogService catalogService,
                                  OrgMapBuildService mapBuildService) {
        this.jdbc = jdbc;
        this.catalogService = catalogService;
        this.mapBuildService = mapBuildService;
    }

    /** 单笔匹配：指定机构 + 结算日，返回可解释归属（落库为一次 match_run）。 */
    @Transactional
    public Map<String, Object> matchSingle(String orgCode, LocalDate settleDate, String mode,
                                           Long catalogId, Long mapBuildId) {
        MatchContext ctx = context(mode, catalogId, mapBuildId);
        Outcome outcome = "MAP".equals(mode)
                ? matchByMap(ctx, orgCode, settleDate)
                : matchByRange(ctx, orgCode, settleDate);

        long runId = insertRun(ctx, Map.of("orgCode", orgCode, "settleDate", settleDate.toString()),
                outcome);
        insertResult(runId, null, orgCode, settleDate, outcome);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matchRunId", runId);
        result.put("mode", ctx.mode);
        result.put("catalogId", ctx.catalogId);
        result.put("catalogVersion", ctx.catalogVersion);
        result.put("mapBuildId", ctx.mapBuildId);
        result.put("orgCode", orgCode);
        result.put("settleDate", settleDate.toString());
        result.put("status", outcome.status);
        result.put("matchedOrgCode", outcome.matchedOrg);
        result.put("versionNo", outcome.versionNo);
        result.put("evidence", outcome.evidence);
        return result;
    }

    /** 批量匹配：快照内指定日期范围（可选机构）的全部结算明细。 */
    @Transactional
    public Map<String, Object> matchBatch(LocalDate dateFrom, LocalDate dateTo, String orgCode,
                                          String mode, Long catalogId, Long mapBuildId) {
        MatchContext ctx = context(mode, catalogId, mapBuildId);
        List<Map<String, Object>> fees = jdbc.queryForList(
                "SELECT id, org_code, settle_date FROM snap_fee_detail"
                        + " WHERE settle_date BETWEEN ? AND ?"
                        + (orgCode == null ? "" : " AND org_code = ?")
                        + " ORDER BY id LIMIT " + (BATCH_LIMIT + 1),
                orgCode == null
                        ? new Object[]{dateFrom, dateTo}
                        : new Object[]{dateFrom, dateTo, orgCode});
        if (fees.size() > BATCH_LIMIT) {
            throw ApiException.unprocessable("批量匹配超过上限 " + BATCH_LIMIT + " 行，请缩小日期范围");
        }

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("dateFrom", dateFrom.toString());
        scope.put("dateTo", dateTo.toString());
        scope.put("orgCode", orgCode);

        // 先算后写：统计计数并批量落库
        List<Object[]> batchArgs = new ArrayList<>();
        int matched = 0, successor = 0, unmatched = 0, conflicts = 0;
        List<Map<String, Object>> notable = new ArrayList<>();
        for (Map<String, Object> fee : fees) {
            long feeId = ((Number) fee.get("id")).longValue();
            String org = String.valueOf(fee.get("org_code"));
            LocalDate day = ((Date) fee.get("settle_date")).toLocalDate();
            Outcome outcome = "MAP".equals(ctx.mode)
                    ? matchByMap(ctx, org, day)
                    : matchByRange(ctx, org, day);
            switch (outcome.status) {
                case "MATCHED" -> matched++;
                case "MATCHED_SUCCESSOR" -> successor++;
                case "CONFLICT" -> conflicts++;
                default -> unmatched++;
            }
            batchArgs.add(new Object[]{feeId, org, day, outcome.status, outcome.matchedOrg,
                    outcome.versionNo, Jsons.write(outcome.evidence)});
            if (notable.size() < 20 && !"MATCHED".equals(outcome.status)) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("feeId", feeId);
                n.put("orgCode", org);
                n.put("settleDate", day.toString());
                n.put("status", outcome.status);
                n.put("matchedOrgCode", outcome.matchedOrg);
                n.put("versionNo", outcome.versionNo);
                notable.add(n);
            }
        }

        Counts counts = new Counts(matched, successor, unmatched, conflicts);
        long runId = insertRun(ctx, scope, counts);
        jdbc.batchUpdate(
                "INSERT INTO match_result (match_run_id, fee_id, org_code, settle_date, status,"
                        + " matched_org_code, version_no, evidence)"
                        + " VALUES (" + runId + ", ?, ?, ?, ?, ?, ?, ?::jsonb)",
                batchArgs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matchRunId", runId);
        result.put("mode", ctx.mode);
        result.put("catalogId", ctx.catalogId);
        result.put("catalogVersion", ctx.catalogVersion);
        result.put("mapBuildId", ctx.mapBuildId);
        result.put("snapshotId", ctx.snapshotId);
        result.put("scope", scope);
        result.put("total", fees.size());
        result.put("matched", matched);
        result.put("matchedSuccessor", successor);
        result.put("unmatched", unmatched);
        result.put("conflicts", conflicts);
        result.put("notableSamples", notable);
        return result;
    }

    // ---------- RANGE 模式：目录区间快照 + 闭开约定 + 合并后继路由 ----------

    private Outcome matchByRange(MatchContext ctx, String org, LocalDate day) {
        List<TemporalSemantics.Interval> intervals = ctx.intervals.getOrDefault(org, List.of());
        TemporalSemantics.Selection selection = TemporalSemantics.select(intervals, day);
        if ("MATCHED".equals(selection.status())) {
            return matched(ctx, org, selection.selected().versionNo(),
                    rangeEvidence(ctx, selection, null));
        }
        if ("CONFLICT".equals(selection.status())) {
            return new Outcome("CONFLICT", null, null,
                    rangeEvidence(ctx, selection, "维表区间重叠，需先修复目录数据"));
        }
        // UNMATCHED：尝试合并后继路由
        Map<String, Object> merge = ctx.merges.get(org);
        if (merge != null) {
            LocalDate effective = LocalDate.parse(String.valueOf(merge.get("effective")));
            String successorOrg = String.valueOf(merge.get("mergedInto"));
            if (!day.isBefore(effective)) {
                List<TemporalSemantics.Interval> succIntervals =
                        ctx.intervals.getOrDefault(successorOrg, List.of());
                TemporalSemantics.Selection succSel = TemporalSemantics.select(succIntervals, day);
                if ("MATCHED".equals(succSel.status())) {
                    Map<String, Object> evidence = rangeEvidence(ctx, succSel, null);
                    evidence.put("mergeRule", "SUCCESSOR");
                    evidence.put("merge", merge);
                    evidence.put("basis", org + " 自 " + effective + " 并入 " + successorOrg
                            + "（目录 merge_rule=SUCCESSOR）；结算日 " + day + " ≥ 合并生效日，"
                            + "路由后继机构 " + successorOrg + " 并按闭开约定命中 v"
                            + succSel.selected().versionNo());
                    return matched(ctx, successorOrg, succSel.selected().versionNo(), evidence)
                            .withStatus("MATCHED_SUCCESSOR").withOriginalOrg(org);
                }
            }
        }
        return new Outcome("UNMATCHED", null, null,
                rangeEvidence(ctx, selection, "无有效版本覆盖该结算日，且不满足合并后继路由条件"));
    }

    private Map<String, Object> rangeEvidence(MatchContext ctx, TemporalSemantics.Selection selection,
                                              String extraBasis) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("mode", "RANGE");
        evidence.put("rule", "CLOSED_OPEN");
        evidence.put("catalogId", ctx.catalogId);
        evidence.put("catalogVersion", ctx.catalogVersion);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (TemporalSemantics.Candidate c : selection.candidates()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("versionNo", c.interval().versionNo());
            item.put("validFrom", c.interval().validFrom().toString());
            item.put("validTo", c.interval().validTo() == null ? null : c.interval().validTo().toString());
            item.put("selected", c.selected());
            item.put("reason", c.reason());
            candidates.add(item);
        }
        evidence.put("candidates", candidates);
        if (selection.selected() != null) {
            evidence.put("basis", "目录闭开约定 [valid_from, valid_to)：结算日归属版本 v"
                    + selection.selected().versionNo() + "（" + selection.selected().name() + "）");
        }
        if (extraBasis != null) {
            evidence.put("basis", extraBasis);
        }
        return evidence;
    }

    // ---------- MAP 模式：展开映射等值查找 ----------

    private Outcome matchByMap(MatchContext ctx, String org, LocalDate day) {
        String key = org + "|" + day;
        MapRow row = ctx.mapRows.get(key);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("mode", "MAP");
        evidence.put("buildId", ctx.mapBuildId);
        evidence.put("buildCatalogId", ctx.mapBuildCatalogId);
        evidence.put("buildStatus", ctx.mapBuildStatus);
        evidence.put("catalogId", ctx.catalogId);
        evidence.put("catalogVersion", ctx.catalogVersion);
        if (row == null) {
            evidence.put("basis", "构建 #" + ctx.mapBuildId + " 中无 (" + org + ", " + day + ") 的映射行（映射空洞）");
            return new Outcome("UNMATCHED", null, null, evidence);
        }
        evidence.put("mapRow", Map.of("matchedOrg", row.matchedOrg, "versionNo", row.versionNo));
        String basis = "按构建 #" + ctx.mapBuildId + " 的展开映射等值查找命中 ("
                + row.matchedOrg + ", v" + row.versionNo + ")";
        if (ctx.mapBuildStale) {
            basis += "；注意：该构建所属目录已被取代（映射重建滞后，归属可能过时）";
            evidence.put("staleBuild", true);
        }
        evidence.put("basis", basis);
        boolean successor = !row.matchedOrg.equals(org);
        return new Outcome(successor ? "MATCHED_SUCCESSOR" : "MATCHED",
                row.matchedOrg, row.versionNo, evidence);
    }

    // ---------- 运行上下文与落库 ----------

    private record MapRow(String matchedOrg, int versionNo) {
    }

    private record MatchContext(String mode, long catalogId, int catalogVersion, Long snapshotId,
                                Long mapBuildId, Long mapBuildCatalogId, String mapBuildStatus,
                                boolean mapBuildStale,
                                Map<String, List<TemporalSemantics.Interval>> intervals,
                                Map<String, Map<String, Object>> merges,
                                Map<String, MapRow> mapRows) {
    }

    private MatchContext context(String mode, Long catalogId, Long mapBuildId) {
        Map<String, Object> catalog = catalogId == null
                ? catalogService.active()
                : catalogService.get(catalogId);
        long catId = ((Number) catalog.get("id")).longValue();
        int version = ((Number) catalog.get("version_no")).intValue();
        Long snapshotId = jdbc.queryForObject(
                "SELECT id FROM data_snapshot WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1", Long.class);

        Map<String, List<TemporalSemantics.Interval>> intervals = Map.of();
        Map<String, Map<String, Object>> merges = Map.of();
        Map<String, MapRow> mapRows = Map.of();
        Long buildId = null, buildCatalogId = null;
        String buildStatus = null;
        boolean buildStale = false;

        if ("MAP".equals(mode)) {
            Map<String, Object> build = mapBuildId == null ? mapBuildService.latestCurrent() : null;
            if (build == null && mapBuildId != null) {
                build = mapBuildService.get(mapBuildId);
            }
            if (build == null) {
                // 无 CURRENT 构建时回退到最新构建并标记滞后
                List<Map<String, Object>> latest = jdbc.queryForList(
                        "SELECT * FROM org_map_build ORDER BY id DESC LIMIT 1");
                if (latest.isEmpty()) {
                    throw ApiException.conflict("MAP 模式需要至少一次映射构建，请先调用 POST /api/temporal/map-builds");
                }
                build = Jsons.normalizeRow(latest.get(0));
            }
            buildId = ((Number) build.get("id")).longValue();
            buildCatalogId = ((Number) build.get("catalog_id")).longValue();
            buildStatus = String.valueOf(build.get("status"));
            buildStale = buildCatalogId != catId || !"CURRENT".equals(buildStatus);
            mapRows = new HashMap<>();
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT org_code, day, matched_org_code, version_no FROM snap_org_date_version_map"
                            + " WHERE build_id = ?", buildId)) {
                mapRows.put(String.valueOf(r.get("org_code")) + "|" + r.get("day"),
                        new MapRow(String.valueOf(r.get("matched_org_code")),
                                ((Number) r.get("version_no")).intValue()));
            }
        } else {
            Map<String, Object> edge = catalogService.edge(catId, JoinCatalogService.TEMPORAL_EDGE);
            intervals = catalogService.intervalsOf(edge.get("version_intervals"));
            merges = catalogService.mergesOf(edge.get("version_intervals"));
        }
        return new MatchContext(mode, catId, version, snapshotId, buildId, buildCatalogId,
                buildStatus, buildStale, intervals, merges, mapRows);
    }

    private record Counts(int matched, int successor, int unmatched, int conflicts) {
    }

    private long insertRun(MatchContext ctx, Map<String, Object> scope, Outcome single) {
        Counts c = switch (single.status) {
            case "MATCHED" -> new Counts(1, 0, 0, 0);
            case "MATCHED_SUCCESSOR" -> new Counts(0, 1, 0, 0);
            case "CONFLICT" -> new Counts(0, 0, 0, 1);
            default -> new Counts(0, 0, 1, 0);
        };
        return insertRun(ctx, scope, c);
    }

    private long insertRun(MatchContext ctx, Map<String, Object> scope, Counts c) {
        return jdbc.queryForObject(
                "INSERT INTO match_run (catalog_id, snapshot_id, mode, map_build_id, scope,"
                        + " matched, successor, unmatched, conflicts)"
                        + " VALUES (?,?,?,?,?::jsonb,?,?,?,?) RETURNING id",
                Long.class, ctx.catalogId, ctx.snapshotId, ctx.mode, ctx.mapBuildId,
                Jsons.write(scope), c.matched(), c.successor(), c.unmatched(), c.conflicts());
    }

    private void insertResult(long runId, Long feeId, String org, LocalDate day, Outcome outcome) {
        jdbc.update(
                "INSERT INTO match_result (match_run_id, fee_id, org_code, settle_date, status,"
                        + " matched_org_code, version_no, evidence) VALUES (?,?,?,?,?,?,?,?::jsonb)",
                runId, feeId, org, day, outcome.status, outcome.matchedOrg, outcome.versionNo,
                Jsons.write(outcome.evidence));
    }

    /** 单次匹配结论。 */
    private static final class Outcome {
        final String status;
        final String matchedOrg;
        final Integer versionNo;
        final Map<String, Object> evidence;

        Outcome(String status, String matchedOrg, Integer versionNo, Map<String, Object> evidence) {
            this.status = status;
            this.matchedOrg = matchedOrg;
            this.versionNo = versionNo;
            this.evidence = evidence;
        }

        Outcome withStatus(String s) {
            return new Outcome(s, matchedOrg, versionNo, evidence);
        }

        Outcome withOriginalOrg(String org) {
            evidence.put("originalOrg", org);
            return this;
        }
    }

    private Outcome matched(MatchContext ctx, String org, int versionNo, Map<String, Object> evidence) {
        return new Outcome("MATCHED", org, versionNo, evidence);
    }

    public Map<String, Object> runDetail(long runId) {
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT * FROM match_run WHERE id = ?", runId);
        if (runs.isEmpty()) {
            throw ApiException.notFound("匹配运行不存在: " + runId);
        }
        Map<String, Object> run = Jsons.normalizeRow(runs.get(0));
        run.put("results", Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, fee_id, org_code, settle_date, status, matched_org_code, version_no, evidence"
                        + " FROM match_result WHERE match_run_id = ? ORDER BY id LIMIT 200", runId)));
        return run;
    }

    public List<Map<String, Object>> listRuns() {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, catalog_id, snapshot_id, mode, map_build_id, matched, successor,"
                        + " unmatched, conflicts, created_at FROM match_run ORDER BY id DESC LIMIT 50"));
    }
}
