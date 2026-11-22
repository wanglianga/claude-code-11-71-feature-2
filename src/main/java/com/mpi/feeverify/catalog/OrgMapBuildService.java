package com.mpi.feeverify.catalog;

import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 有效映射构建（方案B）：把机构维表版本按日期展开为 (机构, 日) → (归属机构, 版本) 的
 * 映射表，使连接接近等值查找。映射为平台派生数据、append-only（按 build_id 固定版本）；
 * 构建时把合并后继路由一并物化。目录修订后旧构建标记 STALE，重建前为“映射重建滞后”窗口。
 */
@Service
public class OrgMapBuildService {

    private final JdbcTemplate jdbc;
    private final JoinCatalogService catalogService;

    public OrgMapBuildService(JdbcTemplate jdbc, JoinCatalogService catalogService) {
        this.jdbc = jdbc;
        this.catalogService = catalogService;
    }

    /** 按当前 ACTIVE 目录从维表基表展开映射，并同步到当前快照的派生表（append-only）。 */
    @Transactional
    public Map<String, Object> build(LocalDate fromDate, LocalDate toDate) {
        Map<String, Object> catalog = catalogService.active();
        long catalogId = ((Number) catalog.get("id")).longValue();
        if (fromDate == null || toDate == null || fromDate.isAfter(toDate)) {
            throw ApiException.badRequest("fromDate/toDate 必填且 fromDate 不得晚于 toDate");
        }

        Long buildId = jdbc.queryForObject(
                "INSERT INTO org_map_build (catalog_id, from_date, to_date) VALUES (?,?,?) RETURNING id",
                Long.class, catalogId, fromDate, toDate);

        // 展开：合并生效日起，被合并机构的日期行路由到后继机构版本（与目录 SUCCESSOR 规则一致）
        jdbc.update(
                "INSERT INTO org_date_version_map (org_code, day, matched_org_code, version_no, build_id)"
                        + " SELECT f.org_code, f.day, f.matched_org, v.version_no, ?"
                        + " FROM ("
                        + "   SELECT o.org_code, gs::date AS day,"
                        + "          COALESCE((SELECT o2.merged_into FROM dim_org o2"
                        + "                    WHERE o2.org_code = o.org_code AND o2.merge_effective IS NOT NULL"
                        + "                      AND gs::date >= o2.merge_effective),"
                        + "                   o.org_code) AS matched_org"
                        + "   FROM dim_org o"
                        + "   CROSS JOIN generate_series(?::date, ?::date, interval '1 day') gs"
                        + " ) f"
                        + " JOIN dim_org_version v"
                        + "   ON v.org_code = f.matched_org"
                        + "  AND f.day >= v.valid_from"
                        + "  AND f.day < COALESCE(v.valid_to, DATE '9999-12-31')",
                buildId, fromDate, toDate);

        Long rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_date_version_map WHERE build_id = ?", Long.class, buildId);
        jdbc.update("UPDATE org_map_build SET row_count = ? WHERE id = ?", rowCount, buildId);

        // 同步到当前快照的派生映射表（append-only：仅补充本构建缺失行，旧构建保留可复验）
        Long snapshotId = jdbc.queryForObject(
                "SELECT id FROM data_snapshot WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1", Long.class);
        int snapRows = 0;
        if (snapshotId != null && tableExists("snap_org_date_version_map")) {
            snapRows = jdbc.update(
                    "INSERT INTO snap_org_date_version_map (org_code, day, matched_org_code, version_no, build_id)"
                            + " SELECT m.org_code, m.day, m.matched_org_code, m.version_no, m.build_id"
                            + " FROM org_date_version_map m"
                            + " WHERE m.build_id = ?"
                            + "   AND NOT EXISTS (SELECT 1 FROM snap_org_date_version_map s"
                            + "                   WHERE s.org_code = m.org_code AND s.day = m.day"
                            + "                     AND s.build_id = m.build_id)",
                    buildId);
        }

        Map<String, Object> result = get(buildId);
        result.put("snapshotId", snapshotId);
        result.put("snapshotRowsAppended", snapRows);
        return result;
    }

    private boolean tableExists(String table) {
        return jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
    }

    /** 当前 ACTIVE 目录下最新的 CURRENT 构建；无则 null。 */
    public Map<String, Object> latestCurrent() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.* FROM org_map_build b"
                        + " JOIN join_catalog c ON c.id = b.catalog_id AND c.status = 'ACTIVE'"
                        + " WHERE b.status = 'CURRENT' ORDER BY b.id DESC LIMIT 1");
        return rows.isEmpty() ? null : Jsons.normalizeRow(rows.get(0));
    }

    public Map<String, Object> get(long buildId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM org_map_build WHERE id = ?", buildId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("映射构建不存在: " + buildId);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> list() {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, catalog_id, from_date, to_date, row_count, status, built_at"
                        + " FROM org_map_build ORDER BY id DESC"));
    }

    /** 覆盖度自检：指定日期范围内有结算但无映射的 (机构, 日) 列表（映射空洞）。 */
    public List<Map<String, Object>> gaps(long buildId, LocalDate from, LocalDate to) {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT d.org_code, d.settle_date FROM snap_fee_detail d"
                        + " WHERE d.settle_date BETWEEN ? AND ?"
                        + "   AND NOT EXISTS (SELECT 1 FROM snap_org_date_version_map m"
                        + "                   WHERE m.org_code = d.org_code AND m.day = d.settle_date"
                        + "                     AND m.build_id = ?)"
                        + " GROUP BY d.org_code, d.settle_date ORDER BY d.settle_date, d.org_code LIMIT 50",
                from, to, buildId));
    }

    /** 供响应组装使用的构建概要。 */
    public Map<String, Object> summary(long buildId) {
        Map<String, Object> b = get(buildId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buildId", b.get("id"));
        out.put("catalogId", b.get("catalog_id"));
        out.put("status", b.get("status"));
        out.put("fromDate", b.get("from_date"));
        out.put("toDate", b.get("to_date"));
        out.put("rowCount", b.get("row_count"));
        return out;
    }
}
