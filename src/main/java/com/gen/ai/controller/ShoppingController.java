package com.gen.ai.controller;

import java.util.List;

import org.springframework.ai.document.Document;
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
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(value = "category", required = false) String category) {
        return aiShoppingGuideApp.doChat(prompt, sessionId, category);
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

    @GetMapping("/test/filter-search")
    @Operation(summary = "测试：分区检索（按分类过滤）", description = "用于验证 biz_category 元数据过滤是否生效：直接返回命中的切片内容（不调用大模型）。")
    public String testFilterSearch(@RequestParam("prompt") String prompt,
            @RequestParam("category") String category) {
        List<Document> docs = ragDataService.similaritySearch(prompt, category);
        if (docs == null || docs.isEmpty()) {
            return "no matches";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            sb.append("[").append(i).append("] ")
                    .append(d == null ? "" : String.valueOf(d.getText()))
                    .append(System.lineSeparator());
        }
        return sb.toString();
    }
}

