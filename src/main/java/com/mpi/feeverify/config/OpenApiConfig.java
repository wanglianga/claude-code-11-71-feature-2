package com.mpi.feeverify.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI feeVerifyOpenApi() {
        return new OpenAPI().info(new Info()
                .title("省级医保费用查询改写验证服务")
                .version("1.0.0")
                .description("""
                        面向稽核、病种付费与预算团队的费用明细查询改写验证平台。
                        模块：查询登记 → 计划采集 → 规则诊断 → 候选生成 → 影子执行 →
                        结果裁判 → 发布门禁 → 路由/回退。
                        时态关联：关联语义目录 → 有效期匹配 → 连接基数模型 → 改写沙箱 →
                        重复行归因（RANGE 时态范围匹配 / MAP 展开映射两种语义）。
                        验证方式：冻结快照的只读副本（snap_* 表 + 校验和），
                        验证结论仅代表该快照与该目录版本，数据或目录更新后需按风险重验。"""));
    }
}
