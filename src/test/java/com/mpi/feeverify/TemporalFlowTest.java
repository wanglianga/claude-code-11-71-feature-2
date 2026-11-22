package com.mpi.feeverify;

import com.mpi.feeverify.demo.TemporalDemoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时态关联演示集成测试：在独立 schema（verify_it）中真实跑通
 * 边界日期 / 机构合并 / RANGE-MAP等价 / 局部金额膨胀阻断 / 目录修订独立验证 / 映射重建滞后。
 */
@SpringBootTest
@ActiveProfiles("test")
class TemporalFlowTest {

    @Autowired
    private TemporalDemoService temporalDemoService;

    @Test
    @SuppressWarnings("unchecked")
    void temporalDemoCoversAllRequiredScenarios() {
        Map<String, Object> report = temporalDemoService.run();

        // 1. 边界日期：闭开约定选定一版并留下依据
        Map<String, Object> s1 = (Map<String, Object>) report.get("scenario1_boundaryDates");
        Map<String, Object> before = (Map<String, Object>) s1.get("org001_2025_12_31");
        assertEquals(1, ((Number) before.get("versionNo")).intValue(), "边界前一日归属 v1");
        Map<String, Object> on = (Map<String, Object>) s1.get("org001_2026_01_01");
        assertEquals(2, ((Number) on.get("versionNo")).intValue(), "边界当日按闭开约定归属 v2");
        Map<String, Object> evidence = (Map<String, Object>) on.get("evidence");
        assertEquals("CLOSED_OPEN", evidence.get("rule"));
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) evidence.get("candidates");
        assertEquals(2, candidates.size(), "证据须列出全部候选版本");
        assertTrue(candidates.stream().anyMatch(c -> Boolean.TRUE.equals(c.get("selected"))));
        Map<String, Object> batch = (Map<String, Object>) s1.get("boundaryWeekBatch");
        assertTrue(((Number) batch.get("total")).intValue() > 0);
        assertTrue(((Number) batch.get("matchedSuccessor")).intValue() > 0,
                "边界周批量匹配应含合并后继路由行（ORG002 自 2026-01-01 起）");

        // 2. 机构合并：生效日后路由后继机构版本
        Map<String, Object> s2 = (Map<String, Object>) report.get("scenario2_orgMerge");
        Map<String, Object> merged = (Map<String, Object>) s2.get("org002_2026_02_10");
        assertEquals("MATCHED_SUCCESSOR", merged.get("status"));
        assertEquals("ORG001", merged.get("matchedOrg"));
        assertEquals(2, ((Number) merged.get("versionNo")).intValue());

        // 3. RANGE vs MAP 等价：PASS 且基数模型无异常
        Map<String, Object> s3 = (Map<String, Object>) report.get("scenario3_rangeVsMapPass");
        assertEquals("PASS", s3.get("verdict"));
        assertEquals("ELIGIBLE", s3.get("releaseDecision"));
        assertEquals(Boolean.TRUE, s3.get("versionEqual"));
        assertTrue(((Number) s3.get("cardinalityOk")).intValue() >= 8,
                "4 条连接边 × 两侧的基数观测应全部 OK");

        // 4. 局部金额膨胀：总行数相同但 ORG001 金额翻倍 → 阻断发布并列出膨胀路径
        Map<String, Object> s4 = (Map<String, Object>) report.get("scenario4_partialInflationBlocked");
        assertEquals("BLOCK", s4.get("verdict"));
        assertEquals(Boolean.TRUE, s4.get("rowCountEqual"), "聚合输出总行数相同");
        assertEquals(Boolean.FALSE, s4.get("amountEqual"));
        assertEquals(Boolean.FALSE, s4.get("orgGroupEqual"));
        List<Map<String, Object>> orgDiff = (List<Map<String, Object>>) s4.get("orgAmountDiff");
        assertEquals(1, orgDiff.size(), "膨胀应局部化到 ORG001 一家");
        Map<String, Object> org001 = orgDiff.get(0);
        assertEquals("ORG001", org001.get("org"));
        assertEquals(0, new BigDecimal("2.0000").compareTo(
                new BigDecimal(String.valueOf(org001.get("ratio")))), "ORG001 金额恰好翻倍");
        List<Map<String, Object>> path = (List<Map<String, Object>>) s4.get("inflationPath");
        assertEquals(1, path.size());
        Map<String, Object> edge = path.get(0);
        assertEquals("fee_detail__dim_org_version", edge.get("edge"));
        List<Map<String, Object>> duplicated = (List<Map<String, Object>>) edge.get("duplicatedOrgs");
        assertEquals(1, duplicated.size());
        assertEquals("ORG001", duplicated.get(0).get("org"));
        assertEquals(2, ((List<?>) duplicated.get(0).get("versions")).size(),
                "膨胀路径须列出命中的一对版本");
        assertEquals("BLOCKED", s4.get("releaseDecision"));
        assertEquals("BLOCKED", s4.get("candidateStatus"));
        assertEquals(Boolean.TRUE, s4.get("gateRejected"), "被归因器阻断的候选不得进入发布门禁");

        // 5. 灰度期间目录修订：旧沙箱保留原目录快照（CATALOG_STALE），新目录独立验证
        Map<String, Object> s5 = (Map<String, Object>) report.get("scenario5_catalogRevisionDuringGray");
        Map<String, Object> impact = (Map<String, Object>) s5.get("revisionImpact");
        assertTrue(((Number) impact.get("sandboxRunsMarkedStale")).intValue() >= 2,
                "场景3/4 的沙箱应保留原目录快照并标记 CATALOG_STALE");
        assertTrue(((Number) impact.get("mapBuildsMarkedStale")).intValue() >= 1);
        List<Map<String, Object>> windows = (List<Map<String, Object>>) impact.get("recalcWindows");
        assertTrue(windows.stream().anyMatch(w -> "ORG001".equals(w.get("org"))
                && "2025-12-15".equals(w.get("affectedFrom"))
                && "2026-01-01".equals(w.get("affectedTo"))), "重算窗口应定位到归属分歧区间");
        Map<String, Object> underV1 = (Map<String, Object>) s5.get("org001_2025_12_20_underV1");
        Map<String, Object> underV2 = (Map<String, Object>) s5.get("org001_2025_12_20_underV2");
        assertEquals(1, ((Number) underV1.get("versionNo")).intValue(), "旧目录下该日归属 v1");
        assertEquals(2, ((Number) underV2.get("versionNo")).intValue(), "新目录立即反映新解释（v2）");
        Map<String, Object> independent = (Map<String, Object>) s5.get("independentValidation");
        assertEquals("PASS", independent.get("verdict"), "新目录须独立验证通过");

        // 6. 映射重建滞后：旧构建归属漂移被阻断，重建后通过
        Map<String, Object> s6 = (Map<String, Object>) report.get("scenario6_mapRebuildLag");
        assertEquals("BLOCK", s6.get("staleRunVerdict"), "滞后映射的归属漂移必须阻断");
        assertEquals(Boolean.FALSE, s6.get("versionEqual"));
        List<Map<String, Object>> versionDiff = (List<Map<String, Object>>) s6.get("versionDiffSamples");
        assertTrue(versionDiff.size() > 0, "须列出版本归属差异样例");
        Map<String, Object> staleness = (Map<String, Object>) s6.get("staleness");
        assertNotNull(staleness);
        assertTrue(String.valueOf(staleness.get("reason")).contains("映射重建滞后"));
        assertEquals("PASS", s6.get("fixedVerdict"), "映射重建后应通过");

        // 策略推荐：120 天跨度 + MEDIUM 频率 → MAP
        Map<String, Object> strategy = (Map<String, Object>) report.get("strategySample");
        assertEquals("MAP", strategy.get("recommended"));
    }
}
