package com.mpi.feeverify.catalog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/temporal/catalogs")
@Tag(name = "关联语义目录", description = "主外键/有效区间/闭开边界规则/维表修订频率；目录版本即语义快照，修订触发独立验证")
public class JoinCatalogController {

    private final JoinCatalogService catalogService;

    public JoinCatalogController(JoinCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @PostMapping
    @Operation(summary = "创建目录新版本（从当前维表快照区间与合并信息；旧目录的沙箱/映射标记滞后并给出重算窗口）")
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        return catalogService.createVersion(
                body == null ? null : (String) body.get("revisionReason"),
                body == null ? null : (String) body.get("dimRevisionFreq"));
    }

    @GetMapping
    @Operation(summary = "目录版本列表")
    public List<Map<String, Object>> list() {
        return catalogService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "目录详情（含全部连接边与版本区间快照）")
    public Map<String, Object> get(@PathVariable long id) {
        return catalogService.get(id);
    }
}
