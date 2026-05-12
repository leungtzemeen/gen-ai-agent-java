package com.gen.ai.web;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gen.ai.application.minus.runtime.MinusChatSseService;
import com.gen.ai.application.shopping.AiShoppingGuideApp;
import com.gen.ai.infrastructure.rag.service.RagDataService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

/**
 * 导购对话与 RAG 管理 API：对话（支持 SSE 流式）、知识库导入/清空、分区检索嗅探。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
@Tag(name = "AI 智能导购系统")
public class AiShoppingGuideController {

    private final AiShoppingGuideApp aiShoppingGuideApp;
    private final RagDataService ragDataService;
    private final MinusChatSseService minusChatSseService;

    /**
     * Minus 多步编排：独立路径 {@code /ai/chat/minus}；SSE 使用命名事件 {@code minus}（JSON 步事件）与
     * {@code done}（收尾摘要）。普通流式仍为 {@link #chat(String, String, String)} {@code /ai/chat}。
     */
    @GetMapping(value = "/chat/minus", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "智能对话（Minus 多步，SSE 步事件）",
            description =
                    "路径 {@code /ai/chat/minus}；SSE：event: minus 为 MinusStepEvent JSON；event: done 为整次任务收尾。"
                            + "参数 prompt、sessionId、category 与流式导购一致；maxSteps 为外层步上限（默认 5）。")
    public Flux<ServerSentEvent<String>> chatMinus(
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "maxSteps", defaultValue = "5") int maxSteps) {
        return minusChatSseService.stream(prompt, sessionId, category, maxSteps);
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "智能对话（RAG增强，流式）",
            description = "SSE：按片段推送模型输出；若启发式判定可能触发 WiseLink 工具，则先同步执行工具再以单段文本下发。")
    public Flux<String> chat(
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(value = "category", required = false) String category) {
        return aiShoppingGuideApp.doChatStream(prompt, sessionId, category);
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

    // @GetMapping("/test/filter-search")
    // @Operation(summary = "测试：分区检索（按分类过滤）", description = "用于验证 biz_category 元数据过滤是否生效：直接返回命中的切片内容（不调用大模型）。")
    // public String testFilterSearch(@RequestParam("prompt") String prompt, @RequestParam("category") String category) {
    //     List<Document> docs = ragDataService.similaritySearch(prompt, category);
    //     if (docs == null || docs.isEmpty()) {
    //         return "no matches";
    //     }
    //     StringBuilder sb = new StringBuilder();
    //     for (int i = 0; i < docs.size(); i++) {
    //         Document d = docs.get(i);
    //         sb.append("[")
    //                 .append(i)
    //                 .append("] ")
    //                 .append(d == null ? "" : String.valueOf(d.getText()))
    //                 .append(System.lineSeparator());
    //     }
    //     return sb.toString();
    // }
}
