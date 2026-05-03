package com.gen.ai.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 存活探测接口，供负载均衡或运维巡检使用。 */
@RestController
@Tag(name = "健康检查")
public class HealthController {

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "返回 OK 表示系统正常。")
    public String health() {
        return "ok";
    }
}
