package com.mpi.feeverify.attribution;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/temporal/attributions")
@Tag(name = "重复行归因器", description = "金额/行数膨胀定位至具体连接边；总行数相同但机构金额翻倍必须阻断发布并列出膨胀路径")
public class AttributionController {

    private final DupAttributionService attributionService;

    public AttributionController(DupAttributionService attributionService) {
        this.attributionService = attributionService;
    }

    @PostMapping("/sandbox/{sandboxRunId}")
    @Operation(summary = "对一次沙箱运行做重复行归因：比较行数/金额/机构分组/版本归属，"
            + "膨胀定位至连接边；BLOCK 时阻断关联候选的发布")
    public Map<String, Object> attribute(@PathVariable long sandboxRunId) {
        return attributionService.attribute(sandboxRunId);
    }

    @GetMapping
    @Operation(summary = "归因报告列表")
    public List<Map<String, Object>> list() {
        return attributionService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "归因报告详情（含膨胀路径与版本差异）")
    public Map<String, Object> get(@PathVariable long id) {
        return attributionService.get(id);
    }
}
