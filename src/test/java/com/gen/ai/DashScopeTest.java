package com.gen.ai;

import cn.hutool.core.lang.UUID;
import cn.hutool.http.Header;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.gen.ai.application.shopping.AiShoppingGuideApp;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 端到端调用 DashScope 的集成测试；仅当已配置 {@code AI_DASHSCOPE_API_KEY} 时执行。
 */
@SpringBootTest(
        properties = {
            "spring.ai.mcp.client.enabled=false",
            // 与主 yml 一致：从环境变量注入，供本类 @Value 与 DashScope 自动配置解析
            "spring.ai.dashscope.api-key=${AI_DASHSCOPE_API_KEY}"
        })
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "AI_DASHSCOPE_API_KEY", matches = ".+")
class DashScopeTest {

    static {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Autowired
    private DashScopeChatModel dashScopeChatModel;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Test
    void sdkInvokeDemo() throws Exception {
        System.out.println("当前系统编码: " + System.getProperty("file.encoding"));

        // 原生 DashScope SDK 写法（已废弃，保留对照）
        /*
         * Generation generation = new Generation();
         * Message userMessage = Message.builder()
         * .role(Role.USER.getValue())
         * .content("你好，你是谁？")
         * .build();
         * GenerationParam param = GenerationParam.builder()
         * .apiKey(apiKey)
         * .model("qwen-plus")
         * .messages(List.of(userMessage))
         * .resultFormat(GenerationParam.ResultFormat.MESSAGE)
         * .build();
         * GenerationResult result = generation.call(param);
         * String content =
         * result.getOutput().getChoices().get(0).getMessage().getContent();
         * System.out.println("DashScope response: " + content);
         */

        // SAA / Spring AI 写法：DashScopeChatModel
        ChatResponse response = dashScopeChatModel.call(new Prompt(List.of(new UserMessage("你好，你是谁？"))));
        String content = response.getResult().getOutput().getText();
        System.out.println("DashScopeChatModel response: " + content);
    }

    @Test
    void springAiInvokeDemo() {
        ChatClient chatClient = chatClientBuilder.build();
        String content = chatClient.prompt("你好，你是谁？").call().content();
        System.out.println("ChatClient response: " + content);
    }

    @Test
    void httpClientInvokeDemo() {
        JSONObject message = JSONUtil.createObj()
                .set("role", "user")
                .set("content", "你好，你是谁？");
        JSONArray messages = JSONUtil.createArray().put(message);
        String requestBody = JSONUtil.createObj()
                .set("model", "qwen-plus")
                .set("messages", messages)
                .toString();

        HttpResponse response = HttpUtil
                .createPost("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
                .charset(StandardCharsets.UTF_8)
                .header(Header.CONTENT_TYPE, "application/json;charset=UTF-8")
                .header(Header.AUTHORIZATION, "Bearer " + apiKey)
                .body(requestBody, "application/json;charset=UTF-8")
                .execute();

        String responseBody = response.body();
        System.out.println("HTTP Response Body: " + responseBody);
    }

    @Resource
    private AiShoppingGuideApp aiShoppingGuideApp;

    @Test
    void aiShoppingGuideAppDoChatDemo() {
        // String chaId = UUID.randomUUID().toString();
        // String chaId = "c7b04518-e6b7-4c7b-8d02-8a9d16a1b260";
        String chaId = "c7b04518-e6b7-4c7b-8d02-8a9d16a1b242";
        // 第一轮
        String message = "如果我家客厅开间是 3.5 米，平时爱看 4K 电影，你建议我选多大的电视？给个具体的推荐型号。";
        // String message = "你好，你知道我的另一半是谁吗？";
        // String message = "你好，你知道我是谁吗？";
        // String message = "你好，我是国际影坛巨星梁朝伟";
        // String message = "我想给我的另一半“张曼玉”买点东西";
        String answer = aiShoppingGuideApp.doChat(message, chaId);
        Assertions.assertNotNull(answer);
        // 第二轮
        // message = "我想给我的另一半“张曼玉”买点东西";
        // // message = "你好，我是国际影坛巨星梁朝伟";
        // answer = aiShoppingGuideApp.doChat(message, chaId);
        // Assertions.assertNotNull(answer);
        // // 第三轮
        // message = "我的另一半叫什么来着？我刚刚和你说过，你帮我回忆一下";
        // answer = aiShoppingGuideApp.doChat(message, chaId);
        // Assertions.assertNotNull(answer);
    }

    @Test
    void aiShoppingGuideAppDoChatWithReportDemo() {
        String chaId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好，我是Edison陈冠希, 我想要买一部相机拍照, 但是我们不知道怎么选";
        // String message = "我想给我的另一半“张曼玉”买点东西";
        AiShoppingGuideApp.ShoppingReport shoppingReport = aiShoppingGuideApp.doChatWithReport(message, chaId);
        Assertions.assertNotNull(shoppingReport);
    }

}
