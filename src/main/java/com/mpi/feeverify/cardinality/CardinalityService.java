package com.mpi.feeverify.cardinality;

import com.mpi.feeverify.support.Jsons;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 连接基数模型：每条连接边 × 参数桶记录期望行数（事实行数 × 期望扇出）与
 * 观察行数（按该侧实际连接语义探测）。观察 &gt; 期望 → INFLATED（重复行风险），
 * 观察 &lt; 期望 → DEFICIT（丢行风险）。模型条目带目录与沙箱运行的版本依赖。
 */
@Service
public class CardinalityService {

    private final JdbcTemplate jdbc;

    public CardinalityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 参数桶键：月份区间 + 机构 + 结算状态。 */
    public static String bucketKey(Map<String, Object> params) {
        Object org = params.get("orgCode");
        Object status = params.get("settleStatus");
        return params.get("monthFrom") + ".." + params.get("monthTo")
                + "|org=" + (org == null ? "ANY" : org)
                + "|status=" + (status == null ? "ANY" : status);
    }

    /** 记录一条基数观测。 */
    public void record(long catalogId, String edgeName, String bucketKey, String side, String semantics,
                       long expectedRows, long observedRows, Long sandboxRunId) {
        BigDecimal fanout = expectedRows == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(observedRows)
                        .divide(BigDecimal.valueOf(expectedRows), 4, RoundingMode.HALF_UP);
        String status = observedRows > expectedRows ? "INFLATED"
                : observedRows < expectedRows ? "DEFICIT" : "OK";
        jdbc.update(
                "INSERT INTO join_cardinality_stat (catalog_id, edge_name, bucket_key, side, semantics,"
                        + " expected_rows, observed_rows, fanout, status, sandbox_run_id)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                catalogId, edgeName, bucketKey, side, semantics, expectedRows, observedRows,
                fanout, status, sandboxRunId);
    }

    public List<Map<String, Object>> list(Long catalogId, String edgeName, String bucketKey) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM join_cardinality_stat WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (catalogId != null) {
            sql.append(" AND catalog_id = ?");
            args.add(catalogId);
        }
        if (edgeName != null) {
            sql.append(" AND edge_name = ?");
            args.add(edgeName);
        }
        if (bucketKey != null) {
            sql.append(" AND bucket_key = ?");
            args.add(bucketKey);
        }
        sql.append(" ORDER BY id DESC LIMIT 200");
        return Jsons.normalizeRows(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    /** 异常观测（INFLATED/DEFICIT），供治理视图与归因器使用。 */
    public List<Map<String, Object>> anomalies(long catalogId) {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT * FROM join_cardinality_stat WHERE catalog_id = ? AND status <> 'OK'"
                        + " ORDER BY id DESC LIMIT 100", catalogId));
    }
}
