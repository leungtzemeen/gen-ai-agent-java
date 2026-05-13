package com.gen.ai.web;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import com.gen.ai.application.manus.runtime.ManusChatSseService;
import com.gen.ai.application.shopping.AiShoppingGuideApp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

/**
 * 导购对话 API：Manus 多步 SSE 与普通流式 SSE。知识库运维接口见 {@link SystemController}。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
@Tag(name = "AI 智能导购系统")
public class AiShoppingGuideController {

    private final AiShoppingGuideApp aiShoppingGuideApp;
    private final ManusChatSseService manusChatSseService;

    /**
     * Manus 多步编排：独立路径 {@code /ai/chat/manus}；SSE 使用命名事件 {@code manus}（JSON 步事件）与
     * {@code done}（收尾摘要）。普通流式仍为 {@link #chat(String, String)} {@code /ai/chat}。
     */
    @GetMapping(value = "/chat/manus", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "智能对话（Manus 多步，SSE 步事件）",
            description =
                    "路径 {@code /ai/chat/manus}；SSE：event: manus 为 ManusStepEvent JSON；event: done 为整次任务收尾。"
                            + "必传：{@code prompt}、{@code sessionId}。外层步上限由配置 {@code wiselink.manus.max-steps} 决定，不按类目过滤 RAG。")
    public Flux<ServerSentEvent<String>> chatManus(
            @RequestParam("prompt") String prompt, @RequestParam("sessionId") String sessionId) {
        return blankPromptOrSessionError(prompt, sessionId)
                .<Flux<ServerSentEvent<String>>>map(msg -> Flux.error(new IllegalArgumentException(msg)))
                .orElseGet(() -> manusChatSseService.stream(prompt, sessionId));
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "智能对话（RAG增强，流式）",
            description =
                    "SSE：按片段推送模型输出；若启发式判定可能触发 WiseLink 工具，则先同步执行工具再以单段文本下发。"
                            + "必传：{@code prompt}、{@code sessionId}；不按 HTTP 传类目，RAG 不按 biz_category 过滤。")
    public Flux<String> chat(@RequestParam("prompt") String prompt, @RequestParam("sessionId") String sessionId) {
        return blankPromptOrSessionError(prompt, sessionId)
                .map(msg -> Flux.<String>error(new IllegalArgumentException(msg)))
                .orElseGet(() -> aiShoppingGuideApp.doChatStream(prompt, sessionId, null));
    }

    /**
     * 与 {@link com.gen.ai.application.manus.runtime.ManusChatSseService#stream} 内校验对齐：空白 prompt / sessionId
     * 在 Controller 即失败，避免无意义进入 Manus 或导购流。
     *
     * @return 若有误则携带错误文案（用于 {@link Flux#error(Throwable)}）；合法则 empty
     */
    private static Optional<String> blankPromptOrSessionError(String prompt, String sessionId) {
        if (prompt == null || prompt.isBlank()) {
            return Optional.of("prompt must not be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.of("sessionId must not be blank");
        }
        return Optional.empty();
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
