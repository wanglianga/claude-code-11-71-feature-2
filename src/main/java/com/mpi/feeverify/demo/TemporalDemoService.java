package com.mpi.feeverify.demo;

import com.mpi.feeverify.attribution.DupAttributionService;
import com.mpi.feeverify.candidate.CandidateService;
import com.mpi.feeverify.catalog.JoinCatalogService;
import com.mpi.feeverify.catalog.OrgMapBuildService;
import com.mpi.feeverify.gate.GateService;
import com.mpi.feeverify.matcher.ValidityMatcherService;
import com.mpi.feeverify.registration.RegistrationService;
import com.mpi.feeverify.sandbox.RewriteSandboxService;
import com.mpi.feeverify.sandbox.TemporalJoinSql;
import com.mpi.feeverify.snapshot.SnapshotService;
import com.mpi.feeverify.strategy.TemporalStrategyService;
import com.mpi.feeverify.support.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时态关联演示编排：一次调用真实落库跑通
 * ① 边界日期闭开取舍 ② 机构合并后继路由 ③ RANGE/MAP 等价沙箱
 * ④ 局部金额膨胀阻断（总行数相同但机构金额翻倍）⑤ 灰度期间目录修订独立验证
 * ⑥ 映射重建滞后。所有对象真实落库，可逐条用 API 复查。
 */
@Service
public class TemporalDemoService {

    private static final String WHERE = """
            to_char(d.settle_date, 'YYYY-MM') BETWEEN :monthFrom AND :monthTo
              AND (:orgCode IS NULL OR d.org_code = :orgCode)
              AND (:settleStatus IS NULL OR d.settle_status = :settleStatus)""";

    /** 原语句（RANGE）：查询时按结算日做时态范围匹配 + 合并后继路由。 */
    public static final String RANGE_DETAIL_SQL = """
            SELECT d.id, d.org_code, v.version_no, v.org_name, d.amount, d.settle_date
            FROM snap_fee_detail d
            %s
            WHERE %s
            ORDER BY d.id""".formatted(TemporalJoinSql.RANGE_JOIN, WHERE);

    /** 候选（MAP）：按日期展开映射做等值查找。 */
    public static final String MAP_DETAIL_SQL = """
            SELECT d.id, d.org_code, m.version_no, v.org_name, d.amount, d.settle_date
            FROM snap_fee_detail d
            %s
            WHERE %s
            ORDER BY d.id""".formatted(TemporalJoinSql.MAP_JOIN, WHERE);

    /** 原语句（RANGE 聚合）：机构级金额汇总。 */
    public static final String RANGE_AGG_SQL = """
            SELECT d.org_code, SUM(d.amount) AS amount, COUNT(*) AS cnt
            FROM snap_fee_detail d
            %s
            WHERE %s
            GROUP BY d.org_code
            ORDER BY d.org_code""".formatted(TemporalJoinSql.RANGE_JOIN, WHERE);

    /** 缺陷候选（ORG_ONLY 聚合）：丢掉时态谓词，一条结算命中两个机构版本。 */
    public static final String ORG_ONLY_AGG_SQL = """
            SELECT d.org_code, SUM(d.amount) AS amount, COUNT(*) AS cnt
            FROM snap_fee_detail d
            %s
            WHERE %s
            GROUP BY d.org_code
            ORDER BY d.org_code""".formatted(TemporalJoinSql.ORG_ONLY_JOIN, WHERE);

    /** 演示参数：跨 2026-01-01 机构交接边界的 4 个月窗口。 */
    public static final Map<String, Object> WINDOW_PARAMS =
            Map.of("monthFrom", "2025-11", "monthTo", "2026-02");

    private final JdbcTemplate jdbc;
    private final SnapshotService snapshotService;
    private final JoinCatalogService catalogService;
    private final OrgMapBuildService mapBuildService;
    private final ValidityMatcherService matcherService;
    private final RewriteSandboxService sandboxService;
    private final DupAttributionService attributionService;
    private final RegistrationService registrationService;
    private final CandidateService candidateService;
    private final GateService gateService;
    private final TemporalStrategyService strategyService;

    public TemporalDemoService(JdbcTemplate jdbc, SnapshotService snapshotService,
                               JoinCatalogService catalogService, OrgMapBuildService mapBuildService,
                               ValidityMatcherService matcherService, RewriteSandboxService sandboxService,
                               DupAttributionService attributionService,
                               RegistrationService registrationService, CandidateService candidateService,
                               GateService gateService, TemporalStrategyService strategyService) {
        this.jdbc = jdbc;
        this.snapshotService = snapshotService;
        this.catalogService = catalogService;
        this.mapBuildService = mapBuildService;
        this.matcherService = matcherService;
        this.sandboxService = sandboxService;
        this.attributionService = attributionService;
        this.registrationService = registrationService;
        this.candidateService = candidateService;
        this.gateService = gateService;
        this.strategyService = strategyService;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> run() {
        Map<String, Object> report = new LinkedHashMap<>();

        // ---------- 0. 重置维表基线（保证演示可重跑）→ 冻结快照 → 目录 v1 → 映射构建 #1 ----------
        jdbc.update("UPDATE dim_org_version SET valid_to = DATE '2026-01-01',"
                + " revision_note = '初始版本' WHERE org_code = 'ORG001' AND version_no = 1");
        jdbc.update("UPDATE dim_org_version SET valid_from = DATE '2026-01-01',"
                + " revision_note = '机构合并：吸收 ORG002' WHERE org_code = 'ORG001' AND version_no = 2");
        Map<String, Object> snapshot = snapshotService.freeze("temporal-demo-snapshot", "时态演示：冻结");
        long snapshotId = ((Number) snapshot.get("snapshotId")).longValue();
        Map<String, Object> catalog = catalogService.createVersion("初始目录（机构维表版本基线）", "MEDIUM");
        long catalogId = ((Number) catalog.get("id")).longValue();
        Map<String, Object> build = mapBuildService.build(
                LocalDate.parse("2025-07-01"), LocalDate.parse("2026-06-30"));
        long buildId = ((Number) build.get("id")).longValue();
        report.put("setup", Map.of(
                "snapshotId", snapshotId, "catalogId", catalogId,
                "catalogVersion", catalog.get("version_no"), "mapBuildId", buildId,
                "mapRows", build.get("row_count")));

        // ---------- 1. 边界日期：闭开约定选定一版并留下依据 ----------
        Map<String, Object> beforeBoundary = matcherService.matchSingle(
                "ORG001", LocalDate.parse("2025-12-31"), "RANGE", catalogId, null);
        Map<String, Object> onBoundary = matcherService.matchSingle(
                "ORG001", LocalDate.parse("2026-01-01"), "RANGE", catalogId, null);
        Map<String, Object> org002Before = matcherService.matchSingle(
                "ORG002", LocalDate.parse("2025-12-31"), "RANGE", catalogId, null);
        Map<String, Object> batch = matcherService.matchBatch(
                LocalDate.parse("2025-12-28"), LocalDate.parse("2026-01-05"), null, "RANGE",
                catalogId, null);
        report.put("scenario1_boundaryDates", Map.of(
                "org001_2025_12_31", Map.of("versionNo", beforeBoundary.get("versionNo"),
                        "status", beforeBoundary.get("status")),
                "org001_2026_01_01", Map.of("versionNo", onBoundary.get("versionNo"),
                        "status", onBoundary.get("status"),
                        "evidence", onBoundary.get("evidence")),
                "org002_2025_12_31", Map.of("versionNo", org002Before.get("versionNo"),
                        "matchedOrg", org002Before.get("matchedOrgCode")),
                "boundaryWeekBatch", Map.of("matchRunId", batch.get("matchRunId"),
                        "total", batch.get("total"), "matched", batch.get("matched"),
                        "matchedSuccessor", batch.get("matchedSuccessor"),
                        "unmatched", batch.get("unmatched"))));

        // ---------- 2. 机构合并：生效日后路由后继机构版本 ----------
        Map<String, Object> merged = matcherService.matchSingle(
                "ORG002", LocalDate.parse("2026-02-10"), "RANGE", catalogId, null);
        report.put("scenario2_orgMerge", Map.of(
                "org002_2026_02_10", Map.of("status", merged.get("status"),
                        "matchedOrg", merged.get("matchedOrgCode"),
                        "versionNo", merged.get("versionNo")),
                "evidence", merged.get("evidence")));

        // ---------- 3. RANGE vs MAP 等价沙箱（PASS）+ 基数模型 ----------
        Map<String, Object> passRun = sandboxService.run("range-vs-map", RANGE_DETAIL_SQL,
                MAP_DETAIL_SQL, "MAP展开映射（等价）", WINDOW_PARAMS,
                Map.of(TemporalJoinSql.TEMPORAL_EDGE,
                        Map.of("original", "TEMPORAL_RANGE", "candidate", "TEMPORAL_MAP")),
                null);
        long passRunId = ((Number) passRun.get("id")).longValue();
        Map<String, Object> passAttr = attributionService.attribute(passRunId);
        report.put("scenario3_rangeVsMapPass", Map.of(
                "sandboxRunId", passRunId,
                "attributionId", passAttr.get("id"),
                "verdict", passAttr.get("verdict"),
                "releaseDecision", passAttr.get("release_decision"),
                "versionEqual", passAttr.get("version_equal"),
                "cardinalityOk", jdbc.queryForObject(
                        "SELECT COUNT(*) FROM join_cardinality_stat WHERE sandbox_run_id = ? AND status = 'OK'",
                        Long.class, passRunId)));

        // ---------- 4. 局部金额膨胀：总行数相同但 ORG001 金额翻倍 → 阻断发布 ----------
        Map<String, Object> registration = registrationService.register(
                RANGE_AGG_SQL, "病种付费机构级金额汇总（时态关联）",
                Map.of("roles", List.of("DRG", "BUDGET"), "orgCodes", List.of("*")),
                RegistrationService.defaultParamShape(), snapshotId, "temporal-demo");
        long regId = ((Number) registration.get("id")).longValue();
        Map<String, Object> defective = candidateService.createCustom(regId, "TEMPORAL_DROP",
                "defective", ORG_ONLY_AGG_SQL, "AGGREGATE", "缺陷候选：丢掉时态谓词仅按机构代码连接版本表");
        long defectiveId = ((Number) defective.get("id")).longValue();
        Map<String, Object> inflationRun = sandboxService.run("drop-temporal-predicate",
                RANGE_AGG_SQL, ORG_ONLY_AGG_SQL, "丢时态谓词（缺陷）", WINDOW_PARAMS,
                Map.of(TemporalJoinSql.TEMPORAL_EDGE,
                        Map.of("original", "TEMPORAL_RANGE", "candidate", "ORG_ONLY")),
                defectiveId);
        long inflationRunId = ((Number) inflationRun.get("id")).longValue();
        Map<String, Object> inflationAttr = attributionService.attribute(inflationRunId);
        List<Map<String, Object>> inflationPath =
                (List<Map<String, Object>>) inflationAttr.get("inflation_path");
        boolean gateRejected;
        String gateRejectReason;
        try {
            gateService.create(defectiveId,
                    new LinkedHashMap<>(Map.of("monthFrom", "2025-11", "monthTo", "2026-02",
                            "orgCode", "ANY", "settleStatus", "ANY", "diagPrefix", "ANY")), 1L);
            gateRejected = false;
            gateRejectReason = null;
        } catch (ApiException e) {
            gateRejected = true;
            gateRejectReason = e.getMessage();
        }
        Map<String, Object> defectiveCandidate = candidateService.get(defectiveId);
        Map<String, Object> scenario4 = new LinkedHashMap<>();
        scenario4.put("sandboxRunId", inflationRunId);
        scenario4.put("attributionId", inflationAttr.get("id"));
        scenario4.put("verdict", inflationAttr.get("verdict"));
        scenario4.put("rowCountEqual", inflationAttr.get("row_count_equal"));
        scenario4.put("amountEqual", inflationAttr.get("amount_equal"));
        scenario4.put("orgGroupEqual", inflationAttr.get("org_group_equal"));
        scenario4.put("orgAmountDiff", inflationAttr.get("org_amount_diff"));
        scenario4.put("inflationPath", inflationPath);
        scenario4.put("releaseDecision", inflationAttr.get("release_decision"));
        scenario4.put("candidateStatus", defectiveCandidate.get("status"));
        scenario4.put("gateRejected", gateRejected);
        scenario4.put("gateRejectReason", String.valueOf(gateRejectReason));
        report.put("scenario4_partialInflationBlocked", scenario4);

        // ---------- 5. 灰度期间目录修订：旧沙箱保留原目录快照，新目录独立验证 ----------
        jdbc.update("UPDATE dim_org_version SET valid_to = DATE '2025-12-15'"
                + " WHERE org_code = 'ORG001' AND version_no = 1");
        jdbc.update("UPDATE dim_org_version SET valid_from = DATE '2025-12-15',"
                + " revision_note = '回溯更正：合并生效日提前至 2025-12-15'"
                + " WHERE org_code = 'ORG001' AND version_no = 2");
        Map<String, Object> catalogV2 = catalogService.createVersion("回溯更正：ORG001 合并生效日提前至 2025-12-15", null);
        long catalogV2Id = ((Number) catalogV2.get("id")).longValue();
        Map<String, Object> impact = (Map<String, Object>) catalogV2.get("impact");
        Map<String, Object> snapshotV2 = snapshotService.freeze("temporal-demo-snapshot-v2",
                "时态演示：维表修订后重冻结");
        // 同一结算日在新旧目录下的解释对比（RANGE 随目录修订立即反映新解释）
        Map<String, Object> underV1 = matcherService.matchSingle(
                "ORG001", LocalDate.parse("2025-12-20"), "RANGE", catalogId, null);
        Map<String, Object> underV2 = matcherService.matchSingle(
                "ORG001", LocalDate.parse("2025-12-20"), "RANGE", catalogV2Id, null);
        // 新目录独立验证：全新沙箱运行（不继承旧目录结论）
        Map<String, Object> independentRun = sandboxService.run("catalog-v2-independent",
                RANGE_DETAIL_SQL, RANGE_DETAIL_SQL, "新目录独立验证（RANGE 自检）", WINDOW_PARAMS,
                Map.of(TemporalJoinSql.TEMPORAL_EDGE,
                        Map.of("original", "TEMPORAL_RANGE", "candidate", "TEMPORAL_RANGE")),
                null);
        long independentRunId = ((Number) independentRun.get("id")).longValue();
        Map<String, Object> independentAttr = attributionService.attribute(independentRunId);
        report.put("scenario5_catalogRevisionDuringGray", Map.of(
                "catalogV2Id", catalogV2Id,
                "revisionImpact", impact,
                "newSnapshotId", snapshotV2.get("snapshotId"),
                "org001_2025_12_20_underV1", Map.of("versionNo", underV1.get("versionNo"),
                        "catalogId", underV1.get("catalogId")),
                "org001_2025_12_20_underV2", Map.of("versionNo", underV2.get("versionNo"),
                        "catalogId", underV2.get("catalogId")),
                "independentValidation", Map.of("sandboxRunId", independentRunId,
                        "verdict", independentAttr.get("verdict"))));

        // ---------- 6. 映射重建滞后：旧构建归属漂移被阻断，重建后通过 ----------
        Map<String, Object> staleMapRun = sandboxService.run("stale-map-after-revision",
                RANGE_DETAIL_SQL, MAP_DETAIL_SQL, "MAP（滞后构建 #1）",
                new LinkedHashMap<>(Map.of("monthFrom", "2025-11", "monthTo", "2026-02",
                        "mapBuildId", buildId)),
                Map.of(TemporalJoinSql.TEMPORAL_EDGE,
                        Map.of("original", "TEMPORAL_RANGE", "candidate", "TEMPORAL_MAP")),
                null);
        long staleMapRunId = ((Number) staleMapRun.get("id")).longValue();
        Map<String, Object> staleAttr = attributionService.attribute(staleMapRunId);
        Map<String, Object> rebuild = mapBuildService.build(
                LocalDate.parse("2025-07-01"), LocalDate.parse("2026-06-30"));
        long rebuildId = ((Number) rebuild.get("id")).longValue();
        Map<String, Object> fixedRun = sandboxService.run("map-rebuilt",
                RANGE_DETAIL_SQL, MAP_DETAIL_SQL, "MAP（重建构建 #" + rebuildId + "）",
                new LinkedHashMap<>(Map.of("monthFrom", "2025-11", "monthTo", "2026-02",
                        "mapBuildId", rebuildId)),
                Map.of(TemporalJoinSql.TEMPORAL_EDGE,
                        Map.of("original", "TEMPORAL_RANGE", "candidate", "TEMPORAL_MAP")),
                null);
        long fixedRunId = ((Number) fixedRun.get("id")).longValue();
        Map<String, Object> fixedAttr = attributionService.attribute(fixedRunId);
        report.put("scenario6_mapRebuildLag", Map.of(
                "staleBuildId", buildId,
                "staleRunVerdict", staleAttr.get("verdict"),
                "versionEqual", staleAttr.get("version_equal"),
                "versionDiffSamples", staleAttr.get("version_diff"),
                "staleness", staleAttr.get("staleness"),
                "rebuildId", rebuildId,
                "fixedRunId", fixedRunId,
                "fixedVerdict", fixedAttr.get("verdict")));

        // ---------- 策略推荐与治理视图样例 ----------
        Map<String, Object> recommend = strategyService.recommend(120, 60L);
        report.put("strategySample", Map.of(
                "recommended", recommend.get("recommended"),
                "reasons", recommend.get("reasons")));
        report.put("summary", Map.of(
                "catalogIds", List.of(catalogId, catalogV2Id),
                "snapshotIds", List.of(snapshotId, ((Number) snapshotV2.get("snapshotId")).longValue()),
                "mapBuildIds", List.of(buildId, rebuildId),
                "allScenariosCovered", List.of("boundaryDates", "orgMerge", "rangeVsMapPass",
                        "partialInflationBlocked", "catalogRevisionDuringGray", "mapRebuildLag")));
        return report;
    }
}
