package com.gen.ai;

import cn.hutool.http.Header;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("dev")
class DashScopeTest {

    static {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Value("${dashscope.api-key}")
    private String apiKey;

    @Test
    void syncInvokeDemo() throws Exception {
        System.out.println("当前系统编码: " + System.getProperty("file.encoding"));
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
    }

    @Test
    void testHttpChat() {
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
}
