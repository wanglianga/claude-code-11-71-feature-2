package com.mpi.feeverify.cardinality;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/temporal/cardinality")
@Tag(name = "连接基数模型", description = "每条连接边 × 参数桶的期望/观察行数；INFLATED=重复行风险，DEFICIT=丢行风险")
public class CardinalityController {

    private final CardinalityService cardinalityService;

    public CardinalityController(CardinalityService cardinalityService) {
        this.cardinalityService = cardinalityService;
    }

    @GetMapping
    @Operation(summary = "基数观测列表（可按目录/连接边/参数桶过滤）")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long catalogId,
                                          @RequestParam(required = false) String edge,
                                          @RequestParam(required = false) String bucket) {
        return cardinalityService.list(catalogId, edge, bucket);
    }

    @GetMapping("/anomalies")
    @Operation(summary = "指定目录下的异常基数观测（INFLATED/DEFICIT）")
    public List<Map<String, Object>> anomalies(@RequestParam long catalogId) {
        return cardinalityService.anomalies(catalogId);
    }
}
