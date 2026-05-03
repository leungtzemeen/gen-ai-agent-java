package com.gen.ai.wiselink.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记可由 WiseLink 注册中心暴露给大模型的工具方法（Spring AI {@link org.springframework.ai.tool.ToolCallback}）。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WiseLinkTool {

    /** 工具名称（对应对话侧 tool / function 名，需全局唯一）。 */
    String name();

    /** 工具自然语言描述，供模型理解调用时机与语义。 */
    String description();

    /** 功能开关：为 {@code false} 时不注册到 WiseLink，对话侧不可见、不可调用。 */
    boolean enabled() default true;
}
