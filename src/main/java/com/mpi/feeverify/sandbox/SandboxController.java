package com.mpi.feeverify.sandbox;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/temporal/sandbox")
@Tag(name = "改写沙箱", description = "固定参数+冻结快照+目录版本下运行原语句与候选；目录修订后已运行沙箱保留原目录快照")
public class SandboxController {

    private final RewriteSandboxService sandboxService;

    public SandboxController(RewriteSandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @PostMapping
    @Operation(summary = "运行改写沙箱：固定 (快照, 目录, 映射构建) 三元组执行原语句与候选，"
            + "并记录每条连接边的期望/观察行数到基数模型")
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(@RequestBody Map<String, Object> body) {
        return sandboxService.run(
                (String) body.get("name"),
                (String) body.get("originalSql"),
                (String) body.get("candidateSql"),
                (String) body.get("candidateLabel"),
                (Map<String, Object>) body.get("params"),
                (Map<String, Map<String, String>>) body.get("edgeSemantics"),
                body.get("candidateId") == null ? null
                        : Long.parseLong(String.valueOf(body.get("candidateId"))));
    }

    @GetMapping
    @Operation(summary = "沙箱运行列表")
    public List<Map<String, Object>> list() {
        return sandboxService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "沙箱运行详情（含两侧执行工件与每边观察行数）")
    public Map<String, Object> get(@PathVariable long id) {
        return sandboxService.get(id);
    }
}
