package com.gen.ai.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gen.ai.app.AiShoppingGuideApp;
import com.gen.ai.service.RagDataService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
@Tag(name = "AI 智能导购系统")
public class ShoppingController {

    private final AiShoppingGuideApp aiShoppingGuideApp;
    private final RagDataService ragDataService;

    @GetMapping("/chat")
    @Operation(summary = "智能对话（RAG增强）", description = "输入 prompt 与 sessionId（默认 default），内部调用 doChatWithRag 进行检索增强对话。")
    public String chat(@RequestParam("prompt") String prompt,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId) {
        return aiShoppingGuideApp.doChat(prompt, sessionId);
    }

    @PostMapping("/admin/import")
    @Operation(summary = "增量导入知识库", description = "触发 RAG 增量 ETL：扫描 rag-docs 下的 Markdown 文件，按 source + file_hash 去重更新并向量化入库。")
    public String importKnowledge() {
        ragDataService.importDocs();
        return "ok";
    }

    @DeleteMapping("/admin/clear")
    @Operation(summary = "清空向量索引", description = "清空向量索引以便反复测试导入。SimpleVectorStore 将删除本地 JSON 文件；其他实现需要具体 ID 列表。")
    public String clearKnowledge() {
        ragDataService.deleteDocs();
        return "ok";
    }
}

