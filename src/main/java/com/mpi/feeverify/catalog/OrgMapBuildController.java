package com.mpi.feeverify.catalog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/temporal/map-builds")
@Tag(name = "有效映射构建", description = "方案B：按日期展开 (机构,日)→(归属机构,版本) 映射；目录修订后旧构建 STALE，重建前为滞后窗口")
public class OrgMapBuildController {

    private final OrgMapBuildService mapBuildService;

    public OrgMapBuildController(OrgMapBuildService mapBuildService) {
        this.mapBuildService = mapBuildService;
    }

    @PostMapping
    @Operation(summary = "按当前 ACTIVE 目录构建有效映射（append-only，同步到当前快照派生表）")
    public Map<String, Object> build(@RequestBody Map<String, Object> body) {
        return mapBuildService.build(
                LocalDate.parse(String.valueOf(body.get("fromDate"))),
                LocalDate.parse(String.valueOf(body.get("toDate"))));
    }

    @GetMapping
    @Operation(summary = "映射构建列表（含 CURRENT/STALE 状态与所属目录版本）")
    public List<Map<String, Object>> list() {
        return mapBuildService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "映射构建详情")
    public Map<String, Object> get(@PathVariable long id) {
        return mapBuildService.get(id);
    }

    @GetMapping("/{id}/gaps")
    @Operation(summary = "映射覆盖度自检：指定日期范围内有结算但无映射的 (机构, 日)")
    public Map<String, Object> gaps(@PathVariable long id, @RequestParam String from,
                                    @RequestParam String to) {
        return Map.of("buildId", id,
                "gaps", mapBuildService.gaps(id, LocalDate.parse(from), LocalDate.parse(to)));
    }
}
