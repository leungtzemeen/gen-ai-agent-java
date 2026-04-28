package com.gen.ai;

import cn.hutool.http.Header;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenModelName;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("dev")
class DashScopeTest {

    static {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    private QwenChatModel qwenChatModel;

    @Autowired
    private DashScopeChatModel dashScopeChatModel;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @BeforeEach
    void setUpLangChain4jModel() {
        qwenChatModel = QwenChatModel.builder()
            .apiKey(apiKey)
            .modelName(QwenModelName.QWEN_PLUS)
            .build();
    }

    @Test
    void sdkInvokeDemo() throws Exception {
        System.out.println("当前系统编码: " + System.getProperty("file.encoding"));

        // 原生 DashScope SDK 写法（已废弃，保留对照）
        /*
        Generation generation = new Generation();
        Message userMessage = Message.builder()
            .role(Role.USER.getValue())
            .content("你好，你是谁？")
            .build();
        GenerationParam param = GenerationParam.builder()
            .apiKey(apiKey)
            .model("qwen-plus")
            .messages(List.of(userMessage))
            .resultFormat(GenerationParam.ResultFormat.MESSAGE)
            .build();
        GenerationResult result = generation.call(param);
        String content = result.getOutput().getChoices().get(0).getMessage().getContent();
        System.out.println("DashScope response: " + content);
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

        HttpResponse response = HttpUtil.createPost("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
            .charset(StandardCharsets.UTF_8)
            .header(Header.CONTENT_TYPE, "application/json;charset=UTF-8")
            .header(Header.AUTHORIZATION, "Bearer " + apiKey)
            .body(requestBody, "application/json;charset=UTF-8")
            .execute();

        String responseBody = response.body();
        System.out.println("HTTP Response Body: " + responseBody);
    }

    @Test
    void langchain4jInvokeDemo() {
        String response = qwenChatModel.chat("你是谁？用 LangChain4j 规范回答。");
        System.out.println("LangChain4j response: " + response);
    }
}
