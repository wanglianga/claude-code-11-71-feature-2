package com.mpi.feeverify.demo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@Tag(name = "演示场景", description = "一键跑通：参数倾斜 / 分组不一致 / 权限收窄 / 回退演练 / 数据刷新重验 / 时态关联六场景")
public class DemoController {

    private final DemoScenarioService demoScenarioService;
    private final TemporalDemoService temporalDemoService;

    public DemoController(DemoScenarioService demoScenarioService, TemporalDemoService temporalDemoService) {
        this.demoScenarioService = demoScenarioService;
        this.temporalDemoService = temporalDemoService;
    }

    @PostMapping("/run")
    @Operation(summary = "运行全部演示场景并返回结构化证据（所有对象真实落库，可逐条复查）")
    public Map<String, Object> run() {
        return demoScenarioService.run();
    }

    @GetMapping("/sql")
    @Operation(summary = "查看演示登记的 SQL 模板与两组参数（窄/宽）")
    public Map<String, Object> demoSql() {
        return Map.of("sql", DemoScenarioService.DEMO_SQL,
                "narrowParams", DemoScenarioService.NARROW_PARAMS,
                "wideParams", DemoScenarioService.WIDE_PARAMS);
    }

    @PostMapping("/temporal/run")
    @Operation(summary = "时态关联演示：边界日期闭开取舍 / 机构合并 / RANGE-MAP等价 / "
            + "局部金额膨胀阻断 / 灰度期间目录修订独立验证 / 映射重建滞后")
    public Map<String, Object> temporalRun() {
        return temporalDemoService.run();
    }

    @GetMapping("/temporal/sql")
    @Operation(summary = "查看时态演示的 SQL 语句（RANGE 明细 / MAP 明细 / RANGE 聚合 / 缺陷聚合）与窗口参数")
    public Map<String, Object> temporalSql() {
        return Map.of(
                "rangeDetailSql", TemporalDemoService.RANGE_DETAIL_SQL,
                "mapDetailSql", TemporalDemoService.MAP_DETAIL_SQL,
                "rangeAggSql", TemporalDemoService.RANGE_AGG_SQL,
                "orgOnlyAggSqlDefective", TemporalDemoService.ORG_ONLY_AGG_SQL,
                "windowParams", TemporalDemoService.WINDOW_PARAMS);
    }
}
