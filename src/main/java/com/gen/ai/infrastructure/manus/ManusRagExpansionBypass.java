package com.gen.ai.infrastructure.manus;

/**
 * Thread-local flag：{@link com.gen.ai.application.shopping.AiShoppingGuideApp} 在 OLLAMA + Manus 路径上置位，
 * 供 {@link com.gen.ai.rag.WiseLinkMultiQueryExpander} 跳过分身扩展 LLM 调用；退出路径后必须 {@link #clear()}。
 */
public final class ManusRagExpansionBypass {

    private static final ThreadLocal<Boolean> SKIP_MULTI_QUERY = new ThreadLocal<>();

    private ManusRagExpansionBypass() {}

    /** 进入 Manus（OLLAMA）会话链路：跳过后续 RAG 分身扩展。 */
    public static void enter() {
        SKIP_MULTI_QUERY.set(Boolean.TRUE);
    }

    public static void clear() {
        SKIP_MULTI_QUERY.remove();
    }

    public static boolean skipMultiQueryExpansion() {
        return Boolean.TRUE.equals(SKIP_MULTI_QUERY.get());
    }
}
