package com.gen.ai.infrastructure.rag.bootstrap;

import org.springframework.context.annotation.Configuration;

import com.gen.ai.infrastructure.rag.service.RagDataService;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动期预热：委托 {@link RagDataService#importDocs()}，与手动导入共用灌库、Markdown 换源删旧、JSON 增量过滤逻辑。
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class WiseLinkDynamicWarmupEngine {

    private final RagDataService ragDataService;

    /** 应用启动完成后触发一次 {@link RagDataService#importDocs()}，与手动导入共用同一套管线。 */
    @PostConstruct
    public void executeDynamicWarmup() {
        log.info(">>>> [Engine-Boot] 知识库热开机：执行 importDocs()");
        ragDataService.importDocs();
    }
}
