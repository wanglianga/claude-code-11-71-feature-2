package com.mpi.feeverify.sandbox;

import com.mpi.feeverify.cardinality.CardinalityService;
import com.mpi.feeverify.catalog.JoinCatalogService;
import com.mpi.feeverify.catalog.OrgMapBuildService;
import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.RestrictedExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 改写沙箱：在固定参数、冻结快照与目录版本下运行原语句与候选语句，
 * 保存每侧的行数、总金额、机构分组、版本归属与每条连接边的观察行数，
 * 并把期望/观察行数写入连接基数模型。沙箱运行固定 (snapshot, catalog, mapBuild)
 * 三元组——目录修订后已运行沙箱保留原目录快照（CATALOG_STALE），新目录独立验证。
 */
@Service
public class RewriteSandboxService {

    private final JdbcTemplate jdbc;
    private final JoinCatalogService catalogService;
    private final OrgMapBuildService mapBuildService;
    private final CardinalityService cardinalityService;
    private final RestrictedExecutor executor;

    @Value("${verify.shadow.statement-timeout-ms:15000}")
    private int statementTimeoutMs;
    @Value("${verify.shadow.lock-timeout-ms:5000}")
    private int lockTimeoutMs;
    @Value("${verify.shadow.work-mem:16MB}")
    private String workMem;

    public RewriteSandboxService(JdbcTemplate jdbc, JoinCatalogService catalogService,
                                 OrgMapBuildService mapBuildService,
                                 CardinalityService cardinalityService, RestrictedExecutor executor) {
        this.jdbc = jdbc;
        this.catalogService = catalogService;
        this.mapBuildService = mapBuildService;
        this.cardinalityService = cardinalityService;
        this.executor = executor;
    }

    /**
     * 运行一次沙箱。
     *
     * @param edgeSemantics 每条边的每侧语义，如 {"fee_detail__dim_org_version":{"original":"TEMPORAL_RANGE","candidate":"ORG_ONLY"}}；
     *                      缺省时两侧均为 TEMPORAL_RANGE
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(String name, String originalSql, String candidateSql,
                                   String candidateLabel, Map<String, Object> rawParams,
                                   Map<String, Map<String, String>> edgeSemantics, Long candidateId) {
        Map<String, Object> params = normalizeParams(rawParams);
        Map<String, Object> snapshot = activeSnapshot();
        long snapshotId = ((Number) snapshot.get("id")).longValue();
        Map<String, Object> catalog = catalogService.active();
        long catalogId = ((Number) catalog.get("id")).longValue();

        Map<String, Map<String, String>> semantics = edgeSemantics == null ? Map.of() : edgeSemantics;
        Map<String, String> temporal = semantics.getOrDefault(TemporalJoinSql.TEMPORAL_EDGE,
                Map.of("original", "TEMPORAL_RANGE", "candidate", "TEMPORAL_RANGE"));

        // MAP 语义需要固定映射构建版本
        Long mapBuildId = params.get("mapBuildId") == null ? null
                : Long.parseLong(String.valueOf(params.get("mapBuildId")));
        boolean usesMap = "TEMPORAL_MAP".equals(temporal.get("original"))
                || "TEMPORAL_MAP".equals(temporal.get("candidate"));
        if (usesMap && mapBuildId == null) {
            Map<String, Object> latest = mapBuildService.latestCurrent();
            if (latest == null) {
                throw ApiException.conflict("候选使用 TEMPORAL_MAP 语义但无 CURRENT 映射构建，"
                        + "请先调用 POST /api/temporal/map-builds");
            }
            mapBuildId = ((Number) latest.get("id")).longValue();
            params.put("mapBuildId", mapBuildId);
        }

        Long runId = jdbc.queryForObject(
                "INSERT INTO sandbox_run (name, snapshot_id, catalog_id, map_build_id, candidate_id,"
                        + " params, original_sql, candidate_sql, candidate_label, edge_semantics)"
                        + " VALUES (?,?,?,?,?,?::jsonb,?,?,?,?::jsonb) RETURNING id",
                Long.class, name, snapshotId, catalogId, mapBuildId, candidateId,
                Jsons.write(params), originalSql, candidateSql, candidateLabel,
                Jsons.write(semantics.isEmpty()
                        ? Map.of(TemporalJoinSql.TEMPORAL_EDGE, temporal) : semantics));

        // 执行两侧
        Map<String, Object> original = runSide(runId, "ORIGINAL", originalSql, params);
        Map<String, Object> candidate = runSide(runId, "CANDIDATE", candidateSql, params);

        // 连接基数探测：事实行数为期望基准，逐边按该侧语义探测观察行数
        String bucket = CardinalityService.bucketKey(params);
        long expected = countInTx(TemporalJoinSql.factCount(params), params);
        Map<String, Object> edgeRowsOriginal = new LinkedHashMap<>();
        Map<String, Object> edgeRowsCandidate = new LinkedHashMap<>();
        List<Map<String, Object>> edges = jdbc.queryForList(
                "SELECT edge_name, dim_table, dim_key, fact_fk, join_semantics FROM join_catalog_edge"
                        + " WHERE catalog_id = ? ORDER BY id", catalogId);
        for (Map<String, Object> edge : edges) {
            String edgeName = String.valueOf(edge.get("edge_name"));
            if (TemporalJoinSql.TEMPORAL_EDGE.equals(edgeName)) {
                long obsO = countInTx(TemporalJoinSql.countProbe(temporal.get("original"), params), params);
                long obsC = countInTx(TemporalJoinSql.countProbe(temporal.get("candidate"), params), params);
                cardinalityService.record(catalogId, edgeName, bucket, "ORIGINAL",
                        temporal.get("original"), expected, obsO, runId);
                cardinalityService.record(catalogId, edgeName, bucket, "CANDIDATE",
                        temporal.get("candidate"), expected, obsC, runId);
                edgeRowsOriginal.put(edgeName, obsO);
                edgeRowsCandidate.put(edgeName, obsC);
            } else {
                String probe = equiProbe(edge, params);
                long obs = countInTx(probe, params);
                cardinalityService.record(catalogId, edgeName, bucket, "ORIGINAL", "EQUI",
                        expected, obs, runId);
                cardinalityService.record(catalogId, edgeName, bucket, "CANDIDATE", "EQUI",
                        expected, obs, runId);
                edgeRowsOriginal.put(edgeName, obs);
                edgeRowsCandidate.put(edgeName, obs);
            }
        }
        updateEdgeRows((Long) original.get("executionId"), edgeRowsOriginal);
        updateEdgeRows((Long) candidate.get("executionId"), edgeRowsCandidate);

        return get(runId);
    }

    private Map<String, Object> runSide(long runId, String side, String sql, Map<String, Object> params) {
        record Outcome(long elapsedMs, Artifacts artifacts) {
        }
        try {
            Outcome outcome = executor.inRestrictedTx(statementTimeoutMs, lockTimeoutMs, workMem,
                    session -> {
                        long start = System.currentTimeMillis();
                        List<Map<String, Object>> rows = session.np().queryForList(sql, sqlParams(params));
                        return new Outcome(System.currentTimeMillis() - start, Artifacts.of(rows));
                    });
            Artifacts a = outcome.artifacts();
            Long execId = jdbc.queryForObject(
                    "INSERT INTO sandbox_execution (sandbox_run_id, side, status, row_count, total_amount,"
                            + " org_group, pk_list, version_map, elapsed_ms)"
                            + " VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?) RETURNING id",
                    Long.class, runId, side, "SUCCESS", a.rowCount(), a.totalAmount(),
                    Jsons.write(a.orgGroup()), Jsons.write(a.pkList()), Jsons.write(a.versionMap()),
                    outcome.elapsedMs());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("executionId", execId);
            out.put("status", "SUCCESS");
            return out;
        } catch (DataAccessException e) {
            String msg = String.valueOf(e.getMostSpecificCause().getMessage());
            String status = msg != null && msg.contains("statement timeout") ? "TIMEOUT" : "FAILED";
            Long execId = jdbc.queryForObject(
                    "INSERT INTO sandbox_execution (sandbox_run_id, side, status, error)"
                            + " VALUES (?,?,?,?) RETURNING id",
                    Long.class, runId, side, status, msg);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("executionId", execId);
            out.put("status", status);
            out.put("error", msg);
            return out;
        }
    }

    private long countInTx(String sql, Map<String, Object> params) {
        return executor.inRestrictedTx(statementTimeoutMs, lockTimeoutMs, workMem,
                session -> {
                    Long n = session.np().queryForObject(sql, sqlParams(params), Long.class);
                    return n == null ? 0L : n;
                });
    }

    private void updateEdgeRows(long executionId, Map<String, Object> edgeRows) {
        jdbc.update("UPDATE sandbox_execution SET edge_rows = ?::jsonb WHERE id = ?",
                Jsons.write(edgeRows), executionId);
    }

    /** EQUI 边探测：维表等值连接（快照表）。 */
    private String equiProbe(Map<String, Object> edge, Map<String, Object> params) {
        return "SELECT COUNT(*) FROM snap_fee_detail d\nJOIN snap_"
                + edge.get("dim_table") + " x ON x." + edge.get("dim_key") + " = d." + edge.get("fact_fk")
                + "\nWHERE " + TemporalJoinSql.whereClause(params);
    }

    private Map<String, Object> activeSnapshot() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM data_snapshot WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1");
        if (rows.isEmpty()) {
            throw ApiException.conflict("当前没有 ACTIVE 快照，请先调用 POST /api/snapshots/freeze");
        }
        return rows.get(0);
    }

    /** 沙箱参数：月份区间必填，机构/状态可选，mapBuildId 由 MAP 语义解析。 */
    public static Map<String, Object> normalizeParams(Map<String, Object> raw) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (String key : List.of("monthFrom", "monthTo", "orgCode", "settleStatus", "mapBuildId")) {
            Object v = raw == null ? null : raw.get(key);
            if (v instanceof String s && s.isBlank()) {
                v = null;
            }
            p.put(key, v);
        }
        if (p.get("monthFrom") == null || p.get("monthTo") == null) {
            throw ApiException.badRequest("参数 monthFrom/monthTo 必填（格式 YYYY-MM）");
        }
        return p;
    }

    /** 类型化参数绑定。 */
    public static MapSqlParameterSource sqlParams(Map<String, Object> p) {
        MapSqlParameterSource s = new MapSqlParameterSource();
        s.addValue("monthFrom", p.get("monthFrom"), Types.VARCHAR);
        s.addValue("monthTo", p.get("monthTo"), Types.VARCHAR);
        s.addValue("orgCode", p.get("orgCode"), Types.VARCHAR);
        s.addValue("settleStatus", p.get("settleStatus"), Types.VARCHAR);
        s.addValue("mapBuildId", p.get("mapBuildId") == null ? null
                : Long.parseLong(String.valueOf(p.get("mapBuildId"))), Types.BIGINT);
        return s;
    }

    public Map<String, Object> get(long runId) {
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT * FROM sandbox_run WHERE id = ?", runId);
        if (runs.isEmpty()) {
            throw ApiException.notFound("沙箱运行不存在: " + runId);
        }
        Map<String, Object> run = Jsons.normalizeRow(runs.get(0));
        run.put("executions", Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, side, status, row_count, total_amount, org_group, version_map, edge_rows,"
                        + " elapsed_ms, error FROM sandbox_execution WHERE sandbox_run_id = ? ORDER BY id",
                runId)));
        return run;
    }

    public List<Map<String, Object>> list() {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, name, snapshot_id, catalog_id, map_build_id, candidate_id, candidate_label,"
                        + " status, created_at FROM sandbox_run ORDER BY id DESC LIMIT 50"));
    }

    /** 执行工件：行数、总金额、机构分组、主键集合、版本归属。 */
    public record Artifacts(long rowCount, BigDecimal totalAmount, Map<String, BigDecimal> orgGroup,
                            List<Object> pkList, Map<String, Object> versionMap) {

        static Artifacts of(List<Map<String, Object>> rows) {
            List<Object> pks = new ArrayList<>();
            Map<String, Object> versions = new TreeMap<>();
            BigDecimal total = BigDecimal.ZERO;
            Map<String, BigDecimal> orgGroup = new TreeMap<>();
            boolean hasId = !rows.isEmpty() && rows.get(0).containsKey("id");
            boolean hasVersion = !rows.isEmpty() && rows.get(0).containsKey("version_no");
            for (Map<String, Object> row : rows) {
                if (hasId) {
                    Object id = row.get("id");
                    pks.add(id);
                    if (hasVersion) {
                        versions.put(String.valueOf(id), row.get("version_no"));
                    }
                }
                BigDecimal amount = row.get("amount") instanceof BigDecimal b ? b : BigDecimal.ZERO;
                total = total.add(amount);
                Object org = row.get("org_code");
                if (org != null) {
                    orgGroup.merge(String.valueOf(org), amount, BigDecimal::add);
                }
            }
            return new Artifacts(rows.size(), total, orgGroup, pks, versions);
        }
    }
}
