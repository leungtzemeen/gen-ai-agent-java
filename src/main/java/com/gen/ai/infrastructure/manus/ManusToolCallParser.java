package com.gen.ai.infrastructure.manus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Manus 2.0：解析 Action JSON，不依赖 Markdown 反引号围栏；优先匹配 {@code ### Action:} / {@code Action:} 下方的 JSON，
 * 或 “{” 向上回溯存在 Action 关键字时的平衡括号对象。
 */
public final class ManusToolCallParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** ```json ... ``` — 仅作遗留输出清理，非主路径依赖 */
    private static final Pattern FENCED_BLOCK =
            Pattern.compile("(?is)```(?:json)?\\s*\\R?(.*?)\\R?```");

    /** 行首（或段首）### Action: / Action:（允许中英文冒号） */
    private static final Pattern ACTION_LABEL =
            Pattern.compile("(?im)(?:^|[\\r\\n]+)\\s*(?:###\\s*)?Action\\s*[:：]\\s*");

    /** 在 “{” 前窗口内出现过 Action 标签（用于回溯校验） */
    private static final Pattern ACTION_KEYWORD_BEFORE_BRACE =
            Pattern.compile("(?is)(?:###\\s*)?Action\\s*[:：]");

    private static final Pattern ACTION_LINE =
            Pattern.compile("(?im)^\\s*Action\\s*[:：]\\s*(\\S+)\\s*$");

    private static final Pattern ACTION_INPUT_LINE =
            Pattern.compile("(?is)\\bAction\\s*Input\\s*[:：]\\s*(.+)$");

    private static final Pattern FINAL_ANSWER =
            Pattern.compile("(?is)\\bFINAL_ANSWER\\s*[:：]\\s*(.+)$");

    /** “{” 前仅允许空白，用于 Action 标签与 JSON 之间的严格段落 */
    private static final int MAX_ACTION_TO_BRACE_GAP = 4096;

    private static final int LOOKBACK_BEFORE_BRACE = 4096;

    private ManusToolCallParser() {}

    /**
     * 解析完成后的参数洗白：校验 tool_input 为 JSON 对象（Map）；{@code maps_weather} 将 {@code cityName} 对齐为 {@code city}。
     *
     * @return 洗白后的调用；若参数无法解析为对象 Map 则 empty（引擎应提示模型修正参数）。
     */
    public static Optional<ManusParsedToolCall> sanitizeParsedToolCall(ManusParsedToolCall call) {
        Objects.requireNonNull(call, "call");
        JsonNode args;
        try {
            args = JSON.readTree(call.argumentsJson());
        } catch (Exception e) {
            return Optional.empty();
        }
        if (args == null || args.isNull()) {
            return Optional.of(new ManusParsedToolCall(call.toolName(), "{}"));
        }
        if (!args.isObject()) {
            return Optional.empty();
        }
        ObjectNode obj = ((ObjectNode) args).deepCopy();
        alignMapsWeatherCityField(call.toolName(), obj);
        try {
            return Optional.of(new ManusParsedToolCall(call.toolName(), JSON.writeValueAsString(obj)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static void alignMapsWeatherCityField(String toolName, ObjectNode obj) {
        if (toolName == null || !"maps_weather".equals(toolName.strip())) {
            return;
        }
        if (!obj.has("cityName")) {
            return;
        }
        JsonNode cityNameNode = obj.remove("cityName");
        if (!obj.has("city")) {
            obj.set("city", cityNameNode);
        }
    }

    /**
     * 移除 Markdown 代码围栏（可选兼容）；主解析逻辑不依赖围栏。
     */
    public static String stripMarkdownCodeFences(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Matcher m = FENCED_BLOCK.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String inner = m.group(1) != null ? m.group(1).strip() : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(inner));
        }
        m.appendTail(sb);
        return sb.toString().strip();
    }

    public static Optional<String> tryParseFinalAnswer(String assistantText) {
        if (assistantText == null || assistantText.isBlank()) {
            return Optional.empty();
        }
        Matcher fm = FINAL_ANSWER.matcher(assistantText);
        if (!fm.find()) {
            return Optional.empty();
        }
        String tail = fm.group(1) != null ? fm.group(1).strip() : "";
        return tail.isBlank() ? Optional.empty() : Optional.of(tail);
    }

    public static Optional<ManusParsedToolCall> tryParseToolCall(String assistantText) {
        if (assistantText == null || assistantText.isBlank()) {
            return Optional.empty();
        }
        String work = assistantText;

        List<String> anchored = extractJsonObjectsAfterActionLabels(work);
        for (int i = anchored.size() - 1; i >= 0; i--) {
            Optional<ManusParsedToolCall> r = parseJsonToolPayload(anchored.get(i));
            if (r.isPresent()) {
                return r;
            }
        }

        Optional<ManusParsedToolCall> rightAnchored = parseRightmostToolJsonWithActionKeyword(work);
        if (rightAnchored.isPresent()) {
            return rightAnchored;
        }

        Optional<String> backtracked = extractJsonWithActionBeforeBrace(work);
        if (backtracked.isPresent()) {
            Optional<ManusParsedToolCall> r = parseJsonToolPayload(backtracked.get());
            if (r.isPresent()) {
                return r;
            }
        }

        String unfenced = stripMarkdownCodeFences(work);
        Optional<ManusParsedToolCall> loose = parseJsonToolPayload(unfenced);
        if (loose.isPresent()) {
            return loose;
        }

        return fuzzyActionLines(unfenced);
    }

    /**
     * 从右向左扫描 {@code '{'}：向前窗口内出现过 {@code Action}/{@code ### Action} 时尝试解析为工具 JSON，
     * 以便在长前文（如 Plan）之后仍能命中**最后一处**合法工具块。
     */
    private static Optional<ManusParsedToolCall> parseRightmostToolJsonWithActionKeyword(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (text.charAt(i) != '{') {
                continue;
            }
            int winStart = Math.max(0, i - LOOKBACK_BEFORE_BRACE);
            String prefix = text.substring(winStart, i);
            if (!ACTION_KEYWORD_BEFORE_BRACE.matcher(prefix).find()) {
                continue;
            }
            String json = extractBalancedJsonObjectFrom(text, i);
            if (json == null) {
                continue;
            }
            Optional<ManusParsedToolCall> p = parseJsonToolPayload(json);
            if (p.isPresent()) {
                return p;
            }
        }
        return Optional.empty();
    }

    /**
     * 在每个 {@code ### Action:} / {@code Action:} 锚点之后，于窗口内查找**第一个** {@code '{'} 起的平衡对象（允许标签与括号之间有任意说明性文字）。
     */
    static List<String> extractJsonObjectsAfterActionLabels(String text) {
        List<String> out = new ArrayList<>(4);
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = ACTION_LABEL.matcher(text);
        while (m.find()) {
            int afterLabel = m.end();
            int scanEnd = Math.min(text.length(), afterLabel + MAX_ACTION_TO_BRACE_GAP);
            int brace = text.indexOf('{', afterLabel);
            if (brace < 0 || brace >= scanEnd) {
                continue;
            }
            String json = extractBalancedJsonObjectFrom(text, brace);
            if (json != null) {
                out.add(json);
            }
        }
        return out;
    }

    /**
     * 扫描每个 {@code '{'}，若向前 LOOKBACK 窗口内存在 Action 标签，则抽取平衡 JSON（标签与 {@code '{'} 之间允许非空说明）。
     */
    static Optional<String> extractJsonWithActionBeforeBrace(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '{') {
                continue;
            }
            int winStart = Math.max(0, i - LOOKBACK_BEFORE_BRACE);
            String prefix = text.substring(winStart, i);
            if (!ACTION_KEYWORD_BEFORE_BRACE.matcher(prefix).find()) {
                continue;
            }
            String json = extractBalancedJsonObjectFrom(text, i);
            if (json != null) {
                return Optional.of(json);
            }
        }
        return Optional.empty();
    }

    static Optional<ManusParsedToolCall> parseJsonToolPayload(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String candidate = extractBalancedJsonObjectFrom(text, text.indexOf('{'));
        if (candidate == null) {
            candidate = text.strip();
        }
        JsonNode root;
        try {
            root = JSON.readTree(candidate);
        } catch (Exception ignored) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }

        String toolName = firstTextual(root, "action", "tool", "name", "function");
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }

        JsonNode argsNode = firstPresent(root, "action_input", "arguments", "input", "tool_input");
        String argsJson;
        if (argsNode == null || argsNode.isNull()) {
            argsJson = "{}";
        } else if (argsNode.isTextual()) {
            argsJson = wrapRawArguments(argsNode.asText());
        } else {
            argsJson = argsNode.toString();
        }
        return Optional.of(new ManusParsedToolCall(toolName.strip(), argsJson));
    }

    private static String wrapRawArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        String t = raw.strip();
        try {
            JSON.readTree(t);
            return t;
        } catch (Exception e) {
            try {
                return JSON.writeValueAsString(t);
            } catch (Exception ex) {
                return "{}";
            }
        }
    }

    private static JsonNode firstPresent(JsonNode root, String... fields) {
        for (String f : fields) {
            if (root.has(f) && !root.get(f).isNull()) {
                return root.get(f);
            }
        }
        return null;
    }

    /** 仅接受字符串型工具名，避免误把嵌套对象序列化当成名称 */
    private static String firstTextual(JsonNode root, String... fields) {
        JsonNode n = firstPresent(root, fields);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.isTextual() ? n.asText() : null;
    }

    static String extractBalancedJsonObjectFrom(String text, int start) {
        if (text == null || start < 0 || start >= text.length() || text.charAt(start) != '{') {
            return null;
        }
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * @deprecated 使用 {@link #extractBalancedJsonObjectFrom(String, int)}；保留供测试或调用方兼容。
     */
    @Deprecated
    static String extractBalancedJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        return start < 0 ? null : extractBalancedJsonObjectFrom(text, start);
    }

    static Optional<ManusParsedToolCall> fuzzyActionLines(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher am = ACTION_LINE.matcher(text);
        if (!am.find()) {
            return Optional.empty();
        }
        String name = am.group(1).strip();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        name = name.replaceAll("^[\"']|[\"']$", "");
        if ("none".equalsIgnoreCase(name)) {
            return Optional.empty();
        }

        Matcher im = ACTION_INPUT_LINE.matcher(text);
        String argsJson = "{}";
        if (im.find()) {
            String payload = im.group(1) != null ? im.group(1).strip() : "";
            payload = stripMarkdownCodeFences(payload);
            String jsonObj = extractBalancedJsonObject(payload);
            if (jsonObj != null) {
                argsJson = jsonObj;
            } else if (!payload.isBlank()) {
                argsJson = wrapRawArguments(payload);
            }
        }
        return Optional.of(new ManusParsedToolCall(name, argsJson));
    }
}
