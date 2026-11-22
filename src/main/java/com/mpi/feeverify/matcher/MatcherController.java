package com.mpi.feeverify.matcher;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/temporal")
@Tag(name = "有效期匹配器", description = "为每笔结算产生可解释的机构版本归属；RANGE=查询时时态范围匹配，MAP=按日期展开映射")
public class MatcherController {

    private final ValidityMatcherService matcherService;

    public MatcherController(ValidityMatcherService matcherService) {
        this.matcherService = matcherService;
    }

    @PostMapping("/match")
    @Operation(summary = "有效期匹配：单笔（orgCode+settleDate）或批量（dateFrom+dateTo[+orgCode]），"
            + "模式 RANGE/MAP；边界日按目录闭开约定取舍并留下依据")
    public Map<String, Object> match(@RequestBody Map<String, Object> body) {
        String mode = body.get("mode") == null ? "RANGE" : String.valueOf(body.get("mode")).toUpperCase();
        Long catalogId = body.get("catalogId") == null ? null
                : Long.parseLong(String.valueOf(body.get("catalogId")));
        Long mapBuildId = body.get("mapBuildId") == null ? null
                : Long.parseLong(String.valueOf(body.get("mapBuildId")));
        if (body.get("settleDate") != null) {
            return matcherService.matchSingle(
                    String.valueOf(body.get("orgCode")),
                    LocalDate.parse(String.valueOf(body.get("settleDate"))),
                    mode, catalogId, mapBuildId);
        }
        return matcherService.matchBatch(
                LocalDate.parse(String.valueOf(body.get("dateFrom"))),
                LocalDate.parse(String.valueOf(body.get("dateTo"))),
                body.get("orgCode") == null ? null : String.valueOf(body.get("orgCode")),
                mode, catalogId, mapBuildId);
    }

    @GetMapping("/match-runs")
    @Operation(summary = "匹配运行列表")
    public List<Map<String, Object>> listRuns() {
        return matcherService.listRuns();
    }

    @GetMapping("/match-runs/{id}")
    @Operation(summary = "匹配运行详情（含每笔结算的归属与证据，前 200 条）")
    public Map<String, Object> runDetail(@PathVariable long id) {
        return matcherService.runDetail(id);
    }
}
