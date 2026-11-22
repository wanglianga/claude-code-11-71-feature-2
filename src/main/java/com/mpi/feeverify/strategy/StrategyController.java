package com.mpi.feeverify.strategy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/temporal/strategy")
@Tag(name = "时态策略与治理", description = "RANGE vs MAP 选择（变更频率/日期跨度/重建时延）及其对灰度覆盖与历史重算的影响")
public class StrategyController {

    private final TemporalStrategyService strategyService;

    public StrategyController(TemporalStrategyService strategyService) {
        this.strategyService = strategyService;
    }

    @PostMapping("/recommend")
    @Operation(summary = "按维表变更频率、查询日期跨度、允许重建时延推荐 RANGE/MAP，"
            + "并展示该选择对灰度覆盖与历史重算的影响")
    public Map<String, Object> recommend(@RequestBody Map<String, Object> body) {
        long span = Long.parseLong(String.valueOf(body.getOrDefault("dateSpanDays", 120)));
        Long rebuild = body.get("allowedRebuildMinutes") == null ? null
                : Long.parseLong(String.valueOf(body.get("allowedRebuildMinutes")));
        return strategyService.recommend(span, rebuild);
    }

    @GetMapping("/governance")
    @Operation(summary = "治理视图：目录版本、映射构建、沙箱/匹配运行、基数异常、灰度覆盖与历史重算窗口")
    public Map<String, Object> governance() {
        return strategyService.governance();
    }
}
