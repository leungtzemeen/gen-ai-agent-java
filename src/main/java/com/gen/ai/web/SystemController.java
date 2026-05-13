package com.gen.ai.web;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gen.ai.infrastructure.rag.service.RagDataService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 运维向接口（知识库导入/向量清空）。访问控制见 {@link LocalhostOnlyAdminInterceptor}，仅本机可调用。
 */
@RestController
@RequestMapping("/ai/admin")
@RequiredArgsConstructor
@Tag(name = "系统运维（本机）")
public class SystemController {

    private final RagDataService ragDataService;

    @PostMapping("/import")
    @Operation(summary = "增量导入知识库（本机）", description = "仅放行 loopback 访问。触发 RAG 增量 ETL。")
    public String importKnowledge() {
        ragDataService.importDocs();
        return "ok";
    }

    @DeleteMapping("/clear")
    @Operation(summary = "清空向量索引（本机）", description = "仅放行 loopback 访问。删除本地向量索引文件。")
    public String clearKnowledge() {
        ragDataService.deleteDocs();
        return "ok";
    }
}
