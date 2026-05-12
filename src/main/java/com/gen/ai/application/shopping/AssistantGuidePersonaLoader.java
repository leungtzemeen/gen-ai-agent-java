package com.gen.ai.application.shopping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import com.gen.ai.prompt.AssistantGuidePromptBundle;

import lombok.extern.slf4j.Slf4j;

/**
 * 从 {@link AssistantGuidePromptBundle} 加载静态人设系统提示文本，供 {@link AiShoppingGuideApp} 与
 * {@link com.gen.ai.application.minus.runtime.SpringAiMinusStepExecutor} 共用，避免复制 IO 与错误处理。
 */
@Slf4j
public final class AssistantGuidePersonaLoader {

    private AssistantGuidePersonaLoader() {}

    public static String loadPlainSystemPersona(AssistantGuidePromptBundle bundle) {
        try {
            Resource systemResource = bundle.getSystemResource();
            if (systemResource == null) {
                return "";
            }
            String rendered = StreamUtils.copyToString(systemResource.getInputStream(), StandardCharsets.UTF_8);
            return Objects.requireNonNullElse(rendered.strip(), "");
        } catch (IOException e) {
            log.error(">>>> [Persona-Loader] 核心人设物理文件加载失败，返回空串兜底", e);
            return "";
        }
    }
}
