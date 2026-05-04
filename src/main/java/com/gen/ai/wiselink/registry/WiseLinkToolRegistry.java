package com.gen.ai.wiselink.registry;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.ai.util.json.schema.JsonSchemaUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import com.gen.ai.wiselink.annotation.WiseLinkTool;

import lombok.extern.slf4j.Slf4j;

/**
 * WiseLink 工具注册中心：在容器启动完成后扫描 Spring Bean，将 {@link WiseLinkTool} 标注的实例方法
 * 包装为 Spring AI 的 {@link MethodToolCallback}（内部基于反射分发，与 {@link java.lang.invoke.MethodHandle} 同属 JVM 可调目标）。
 * 仅当 {@link WiseLinkTool#enabled()} 为 {@code true} 时才会注册；禁用的工具不会出现在模型可用工具列表中。
 * {@link WiseLinkTool#vipOnly()} 在注册时记录下来，供安全拦截层按名称查询。
 */
@Component
@Slf4j
public class WiseLinkToolRegistry implements SmartInitializingSingleton {

    private final ApplicationContext applicationContext;

    private Map<String, ToolCallback> callbacksByName = Map.of();

    private Map<String, Boolean> vipOnlyByToolName = Map.of();

    public WiseLinkToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, ToolCallback> discovered = new LinkedHashMap<>();
        Map<String, Boolean> vipFlags = new LinkedHashMap<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (BeansException ex) {
                log.debug("WiseLink tool scan skip beanName='{}': {}", beanName, ex.getMessage());
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(bean.getClass());
            ReflectionUtils.doWithMethods(
                    userClass,
                    method -> registerMethodIfAnnotated(bean, method, discovered, vipFlags),
                    ReflectionUtils.USER_DECLARED_METHODS);
        }
        this.callbacksByName = Collections.unmodifiableMap(discovered);
        this.vipOnlyByToolName = Collections.unmodifiableMap(vipFlags);
        log.info("WiseLink 工具注册完成，共 {} 个：{}", callbacksByName.size(), callbacksByName.keySet());
    }

    private void registerMethodIfAnnotated(
            Object bean,
            Method method,
            Map<String, ToolCallback> discovered,
            Map<String, Boolean> vipFlags) {
        WiseLinkTool meta = AnnotatedElementUtils.findMergedAnnotation(method, WiseLinkTool.class);
        if (meta == null) {
            return;
        }
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalStateException(
                    "@WiseLinkTool 仅支持 public 方法: " + methodKey(bean, method));
        }
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException(
                    "@WiseLinkTool 不支持 static 方法: " + methodKey(bean, method));
        }
        String toolName = Objects.requireNonNull(meta.name(), "name").trim();
        if (toolName.isEmpty()) {
            throw new IllegalStateException("@WiseLinkTool name 不能为空: " + methodKey(bean, method));
        }
        if (!meta.enabled()) {
            log.info("WiseLink 工具已禁用（enabled=false），跳过注册：{}", toolName);
            return;
        }
        if (discovered.containsKey(toolName)) {
            throw new IllegalStateException("WiseLink 工具名冲突: " + toolName);
        }
        String description = Objects.requireNonNullElse(meta.description(), "").trim();
        String rawSchema = JsonSchemaGenerator.generateForMethodInput(method);
        String inputSchema = JsonSchemaUtils.ensureValidInputSchema(rawSchema);
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(toolName)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        ToolCallback callback = MethodToolCallback.builder()
                .toolDefinition(definition)
                .toolMethod(method)
                .toolObject(bean)
                .build();
        discovered.put(toolName, callback);
        vipFlags.put(toolName, meta.vipOnly());
    }

    private static String methodKey(Object bean, Method method) {
        return bean.getClass().getName() + "#" + method.getName();
    }

    public ToolCallback getCallback(String toolName) {
        return callbacksByName.get(toolName);
    }

    /**
     * 是否为 {@link WiseLinkTool#vipOnly()} 注册的工具（仅对已注册名称有意义）。
     */
    public boolean isVipOnlyTool(String toolName) {
        return Boolean.TRUE.equals(vipOnlyByToolName.get(toolName));
    }

    public Map<String, ToolCallback> getCallbacksByName() {
        return callbacksByName;
    }

    /** 稳定顺序列表，供 {@link org.springframework.ai.chat.client.ChatClient} 绑定。 */
    public List<ToolCallback> getAllCallbacksAsList() {
        return List.copyOf(callbacksByName.values());
    }

    public Collection<ToolCallback> getAllCallbacks() {
        return callbacksByName.values();
    }
}
