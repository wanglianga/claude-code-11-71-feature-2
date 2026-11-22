package com.mpi.feeverify.sandbox;

import java.util.Map;

/**
 * 时态连接 SQL 片段：沙箱探测语句与演示语句共用同一组连接语义片段，
 * 保证“目录声明的语义”与“实际执行的 SQL”一致。
 */
public final class TemporalJoinSql {

    private TemporalJoinSql() {
    }

    /** 目录时态边名。 */
    public static final String TEMPORAL_EDGE = "fee_detail__dim_org_version";

    /**
     * TEMPORAL_RANGE：查询时按结算日做时态范围匹配（闭开区间），
     * 合并生效日后被合并机构路由到后继机构（目录 SUCCESSOR 规则）。
     */
    public static final String RANGE_JOIN = """
            JOIN snap_dim_org_version v
              ON v.org_code = COALESCE(
                   (SELECT o.merged_into FROM snap_dim_org o
                     WHERE o.org_code = d.org_code AND o.merge_effective IS NOT NULL
                       AND d.settle_date >= o.merge_effective),
                   d.org_code)
             AND d.settle_date >= v.valid_from
             AND d.settle_date < COALESCE(v.valid_to, DATE '9999-12-31')""";

    /** TEMPORAL_MAP：按日期展开映射做等值查找（映射已物化合并后继路由）。 */
    public static final String MAP_JOIN = """
            JOIN snap_org_date_version_map m
              ON m.org_code = d.org_code AND m.day = d.settle_date AND m.build_id = :mapBuildId
            JOIN snap_dim_org_version v
              ON v.org_code = m.matched_org_code AND v.version_no = m.version_no""";

    /** ORG_ONLY：缺陷语义——仅按机构代码连接维表版本（一条结算可命中多个版本）。 */
    public static final String ORG_ONLY_JOIN = """
            JOIN snap_dim_org_version v ON v.org_code = d.org_code""";

    /** 按语义名返回连接片段。 */
    public static String joinFragment(String semantics) {
        return switch (semantics) {
            case "TEMPORAL_RANGE" -> RANGE_JOIN;
            case "TEMPORAL_MAP" -> MAP_JOIN;
            case "ORG_ONLY" -> ORG_ONLY_JOIN;
            default -> throw new IllegalArgumentException("未知连接语义: " + semantics);
        };
    }

    /** 过滤条件（与登记模板族一致的行式条件）。 */
    public static String whereClause(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(
                "to_char(d.settle_date, 'YYYY-MM') BETWEEN :monthFrom AND :monthTo");
        if (params.get("orgCode") != null) {
            sb.append("\n  AND d.org_code = :orgCode");
        }
        if (params.get("settleStatus") != null) {
            sb.append("\n  AND d.settle_status = :settleStatus");
        }
        return sb.toString();
    }

    /** 连接边观察行数探测：事实表按指定语义连接后的行数。 */
    public static String countProbe(String semantics, Map<String, Object> params) {
        return "SELECT COUNT(*) FROM snap_fee_detail d\n"
                + joinFragment(semantics) + "\nWHERE " + whereClause(params);
    }

    /** 事实行数（期望行数基准）。 */
    public static String factCount(Map<String, Object> params) {
        return "SELECT COUNT(*) FROM snap_fee_detail d\nWHERE " + whereClause(params);
    }

    /** 重复定位：按机构分组找出命中多个版本的机构及版本集合（膨胀路径证据）。 */
    public static String duplicationProbe(String semantics, Map<String, Object> params) {
        return "SELECT d.org_code, COUNT(*) AS join_rows, COUNT(DISTINCT d.id) AS fact_rows,"
                + " string_agg(DISTINCT v.version_no::text, ',' ORDER BY v.version_no::text) AS versions"
                + " FROM snap_fee_detail d\n"
                + joinFragment(semantics)
                + "\nWHERE " + whereClause(params)
                + "\nGROUP BY d.org_code"
                + "\nHAVING COUNT(*) > COUNT(DISTINCT d.id)"
                + "\nORDER BY d.org_code";
    }
}
