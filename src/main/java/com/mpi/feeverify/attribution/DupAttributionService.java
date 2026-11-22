package com.mpi.feeverify.attribution;

import com.mpi.feeverify.cardinality.CardinalityService;
import com.mpi.feeverify.catalog.JoinCatalogService;
import com.mpi.feeverify.sandbox.RewriteSandboxService;
import com.mpi.feeverify.sandbox.TemporalJoinSql;
import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 重复行归因器：比较沙箱两侧执行工件，把金额或行数膨胀定位至具体连接边。
 * 总行数相同但某机构金额翻倍（聚合查询下连接扇出被 GROUP BY 掩盖）时，
 * 必须阻断发布并列出膨胀路径（连接边、期望/观察扇出、命中多版本的机构与版本对）。
 * 版本归属漂移（如映射重建滞后）同样阻断，并标注滞后的映射构建。
 */
@Service
public class DupAttributionService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate np;
    private final CardinalityService cardinalityService;

    public DupAttributionService(JdbcTemplate jdbc, NamedParameterJdbcTemplate np,
                                 CardinalityService cardinalityService) {
        this.jdbc = jdbc;
        this.np = np;
        this.cardinalityService = cardinalityService;
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public Map<String, Object> attribute(long sandboxRunId) {
        Map<String, Object> run = Jsons.normalizeRow(jdbc.queryForMap(
                "SELECT * FROM sandbox_run WHERE id = ?", sandboxRunId));
        Map<String, Object> original = execution(sandboxRunId, "ORIGINAL");
        Map<String, Object> candidate = execution(sandboxRunId, "CANDIDATE");
        if (!"SUCCESS".equals(original.get("status")) || !"SUCCESS".equals(candidate.get("status"))) {
            throw ApiException.conflict("沙箱 " + sandboxRunId + " 两侧执行未全部成功，不能归因");
        }
        long catalogId = ((Number) run.get("catalog_id")).longValue();
        Map<String, Object> params = Jsons.asMap(run.get("params"));
        Map<String, Object> edgeSemantics = Jsons.asMap(run.get("edge_semantics"));
        String candidateSemantics = temporalSemantics(edgeSemantics, "candidate");

        long origRows = ((Number) original.get("row_count")).longValue();
        long candRows = ((Number) candidate.get("row_count")).longValue();
        BigDecimal origTotal = new BigDecimal(String.valueOf(original.get("total_amount")));
        BigDecimal candTotal = new BigDecimal(String.valueOf(candidate.get("total_amount")));
        Map<String, BigDecimal> origGroup = toAmountMap(Jsons.asMap(original.get("org_group")));
        Map<String, BigDecimal> candGroup = toAmountMap(Jsons.asMap(candidate.get("org_group")));
        Map<String, Object> origVersions = Jsons.asMap(original.get("version_map"));
        Map<String, Object> candVersions = Jsons.asMap(candidate.get("version_map"));

        boolean rowCountEqual = origRows == candRows;
        boolean amountEqual = origTotal.compareTo(candTotal) == 0;
        List<Map<String, Object>> orgDiff = diffGroups(origGroup, candGroup);
        boolean orgGroupEqual = orgDiff.isEmpty();
        List<Map<String, Object>> versionDiff = diffVersions(origVersions, candVersions);
        Boolean versionEqual = (origVersions == null || candVersions == null
                || (origVersions.isEmpty() && candVersions.isEmpty())) ? null : versionDiff.isEmpty();

        boolean pass = rowCountEqual && amountEqual && orgGroupEqual
                && (versionEqual == null || versionEqual);

        // ---------- 膨胀路径：定位至具体连接边 ----------
        List<Map<String, Object>> inflationPath = new ArrayList<>();
        if (!amountEqual || !orgGroupEqual || !rowCountEqual) {
            inflationPath.add(buildInflationPath(sandboxRunId, catalogId, params,
                    candidateSemantics, orgDiff, origRows, candRows));
        }

        // ---------- 版本归属漂移（金额未变但归属不同，如映射重建滞后） ----------
        Map<String, Object> staleness = stalenessEvidence(run, catalogId);

        StringBuilder detail = new StringBuilder();
        if (pass) {
            detail.append("两侧行数/金额/机构分组")
                    .append(versionEqual != null ? "/版本归属" : "")
                    .append("一致");
        } else {
            if (rowCountEqual && !orgGroupEqual) {
                detail.append("总行数相同但机构金额膨胀（聚合掩盖了连接扇出）");
            } else if (!rowCountEqual) {
                detail.append("行数不一致（原 ").append(origRows).append(" vs 候选 ").append(candRows).append("）");
            }
            if (!amountEqual) {
                detail.append("；总金额 原 ").append(origTotal).append(" vs 候选 ").append(candTotal);
            }
            if (Boolean.FALSE.equals(versionEqual)) {
                detail.append("；版本归属漂移 ").append(versionDiff.size()).append(" 例");
                if (staleness != null) {
                    detail.append("（").append(staleness.get("reason")).append("）");
                }
            }
        }

        String verdict = pass ? "PASS" : "BLOCK";
        String releaseDecision = pass ? "ELIGIBLE" : "BLOCKED";

        Long attributionId = jdbc.queryForObject(
                "INSERT INTO dup_attribution (sandbox_run_id, catalog_id, verdict, row_count_equal,"
                        + " amount_equal, org_group_equal, version_equal, org_amount_diff, version_diff,"
                        + " inflation_path, release_decision, detail)"
                        + " VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?) RETURNING id",
                Long.class, sandboxRunId, catalogId, verdict, rowCountEqual, amountEqual,
                orgGroupEqual, versionEqual, Jsons.write(orgDiff), Jsons.write(versionDiff),
                Jsons.write(inflationPath), releaseDecision, detail.toString());

        // 阻断发布：关联候选置 BLOCKED，灰度/已发布门禁阻断并写账本
        Map<String, Object> releaseImpact = null;
        if (!pass && run.get("candidate_id") != null) {
            releaseImpact = blockRelease(((Number) run.get("candidate_id")).longValue(), attributionId);
        }

        Map<String, Object> result = get(attributionId);
        result.put("releaseImpact", releaseImpact);
        result.put("staleness", staleness);
        return result;
    }

    /** 膨胀路径：连接边 + 期望/观察扇出 + 命中多版本的机构与版本对 + 机构金额膨胀倍率。 */
    private Map<String, Object> buildInflationPath(long sandboxRunId, long catalogId,
                                                   Map<String, Object> params,
                                                   String candidateSemantics,
                                                   List<Map<String, Object>> orgDiff,
                                                   long origRows, long candRows) {
        String bucket = CardinalityService.bucketKey(params);
        List<Map<String, Object>> stats = cardinalityService.list(catalogId,
                JoinCatalogService.TEMPORAL_EDGE, bucket);
        Long expected = null, obsOriginal = null, obsCandidate = null;
        for (Map<String, Object> s : stats) {
            if (!String.valueOf(s.get("sandbox_run_id")).equals(String.valueOf(sandboxRunId))) {
                continue;
            }
            expected = ((Number) s.get("expected_rows")).longValue();
            if ("ORIGINAL".equals(s.get("side"))) {
                obsOriginal = ((Number) s.get("observed_rows")).longValue();
            } else {
                obsCandidate = ((Number) s.get("observed_rows")).longValue();
            }
        }

        // 重复定位：候选语义下命中多个版本的机构与版本对
        List<Map<String, Object>> duplicated = new ArrayList<>();
        if (!"TEMPORAL_RANGE".equals(candidateSemantics)) {
            for (Map<String, Object> row : np.queryForList(
                    TemporalJoinSql.duplicationProbe(candidateSemantics, params),
                    RewriteSandboxService.sqlParams(params))) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("org", row.get("org_code"));
                d.put("factRows", row.get("fact_rows"));
                d.put("joinRows", row.get("join_rows"));
                d.put("versions", String.valueOf(row.get("versions")).split(","));
                duplicated.add(d);
            }
        }

        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("edge", JoinCatalogService.TEMPORAL_EDGE);
        edge.put("candidateSemantics", candidateSemantics);
        edge.put("expectedRows", expected);
        edge.put("observedRowsOriginal", obsOriginal);
        edge.put("observedRowsCandidate", obsCandidate);
        edge.put("expectedFanout", 1.0);
        edge.put("observedFanoutCandidate",
                expected != null && expected > 0 && obsCandidate != null
                        ? BigDecimal.valueOf(obsCandidate)
                                .divide(BigDecimal.valueOf(expected), 4, RoundingMode.HALF_UP)
                        : null);
        edge.put("duplicatedOrgs", duplicated);
        edge.put("amountInflation", orgDiff);
        edge.put("outputRowsOriginal", origRows);
        edge.put("outputRowsCandidate", candRows);
        return edge;
    }

    /** 滞后证据：沙箱固定的映射构建是否已被目录修订取代。 */
    private Map<String, Object> stalenessEvidence(Map<String, Object> run, long catalogId) {
        Object buildId = run.get("map_build_id");
        if (buildId == null) {
            return null;
        }
        Map<String, Object> build = Jsons.normalizeRow(jdbc.queryForMap(
                "SELECT b.*, c.version_no AS catalog_version FROM org_map_build b"
                        + " JOIN join_catalog c ON c.id = b.catalog_id WHERE b.id = ?",
                ((Number) buildId).longValue()));
        long buildCatalog = ((Number) build.get("catalog_id")).longValue();
        if (buildCatalog == catalogId && "CURRENT".equals(build.get("status"))) {
            return null;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("mapBuildId", ((Number) buildId).longValue());
        evidence.put("buildCatalogId", buildCatalog);
        evidence.put("runCatalogId", catalogId);
        evidence.put("buildStatus", build.get("status"));
        evidence.put("reason", "映射构建 #" + buildId + " 基于目录 #" + buildCatalog
                + "，当前目录 #" + catalogId + "（映射重建滞后，归属可能过时）");
        return evidence;
    }

    /** 阻断发布：候选置 BLOCKED；灰度/已发布门禁阻断并写回退账本。 */
    private Map<String, Object> blockRelease(long candidateId, long attributionId) {
        jdbc.update("UPDATE rewrite_candidate SET status = 'BLOCKED' WHERE id = ?", candidateId);
        List<Map<String, Object>> gates = jdbc.queryForList(
                "SELECT id, status FROM release_gate WHERE candidate_id = ? AND status IN ('GRAY','PUBLISHED')",
                candidateId);
        List<Long> blockedGates = new ArrayList<>();
        for (Map<String, Object> gate : gates) {
            long gateId = ((Number) gate.get("id")).longValue();
            jdbc.update("UPDATE release_gate SET status = 'BLOCKED' WHERE id = ?", gateId);
            jdbc.update(
                    "INSERT INTO rollback_ledger (gate_id, action, from_status, to_status,"
                            + " affected_requests, recovery_basis, detail) VALUES (?,?,?,?,0,?::jsonb,?)",
                    gateId, "DUP_ATTRIBUTION_BLOCK", String.valueOf(gate.get("status")), "BLOCKED",
                    Jsons.write(Map.of("attributionId", attributionId,
                            "reason", "重复行归因器判定金额/行数膨胀，阻断发布")),
                    "归因器阻断发布");
            blockedGates.add(gateId);
        }
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("candidateId", candidateId);
        impact.put("candidateStatus", "BLOCKED");
        impact.put("gatesBlocked", blockedGates);
        return impact;
    }

    private Map<String, Object> execution(long runId, String side) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM sandbox_execution WHERE sandbox_run_id = ? AND side = ? ORDER BY id DESC LIMIT 1",
                runId, side);
        if (rows.isEmpty()) {
            throw ApiException.conflict("沙箱 " + runId + " 缺少 " + side + " 侧执行记录");
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    private String temporalSemantics(Map<String, Object> edgeSemantics, String side) {
        if (edgeSemantics == null) {
            return "TEMPORAL_RANGE";
        }
        Object edge = edgeSemantics.get(JoinCatalogService.TEMPORAL_EDGE);
        if (edge instanceof Map<?, ?> m && m.get(side) != null) {
            return String.valueOf(m.get(side));
        }
        return "TEMPORAL_RANGE";
    }

    private Map<String, BigDecimal> toAmountMap(Map<String, Object> json) {
        Map<String, BigDecimal> out = new TreeMap<>();
        if (json != null) {
            json.forEach((k, v) -> out.put(k, new BigDecimal(String.valueOf(v))));
        }
        return out;
    }

    private List<Map<String, Object>> diffGroups(Map<String, BigDecimal> original,
                                                 Map<String, BigDecimal> candidate) {
        List<Map<String, Object>> diff = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(original.keySet());
        keys.addAll(candidate.keySet());
        for (String k : keys) {
            BigDecimal va = original.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal vb = candidate.getOrDefault(k, BigDecimal.ZERO);
            if (va.compareTo(vb) != 0) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("org", k);
                d.put("original", va);
                d.put("candidate", vb);
                d.put("delta", vb.subtract(va));
                d.put("ratio", va.compareTo(BigDecimal.ZERO) == 0 ? null
                        : vb.divide(va, 4, RoundingMode.HALF_UP));
                diff.add(d);
            }
        }
        return diff;
    }

    private List<Map<String, Object>> diffVersions(Map<String, Object> original,
                                                   Map<String, Object> candidate) {
        List<Map<String, Object>> diff = new ArrayList<>();
        if (original == null || candidate == null) {
            return diff;
        }
        for (Map.Entry<String, Object> e : original.entrySet()) {
            Object cv = candidate.get(e.getKey());
            if (cv != null && !String.valueOf(cv).equals(String.valueOf(e.getValue()))) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", e.getKey());
                d.put("original", e.getValue());
                d.put("candidate", cv);
                diff.add(d);
                if (diff.size() >= 50) {
                    break;
                }
            }
        }
        return diff;
    }

    public Map<String, Object> get(long attributionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM dup_attribution WHERE id = ?", attributionId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("归因报告不存在: " + attributionId);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> list() {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, sandbox_run_id, catalog_id, verdict, release_decision, row_count_equal,"
                        + " amount_equal, org_group_equal, version_equal, created_at"
                        + " FROM dup_attribution ORDER BY id DESC LIMIT 50"));
    }
}
