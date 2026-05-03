package com.gen.ai.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import com.gen.ai.wiselink.annotation.WiseLinkTool;

import lombok.extern.slf4j.Slf4j;

/**
 * WiseLink 外部检索与网页正文抓取（全网比价扩展）。工具描述中含调用约束，引导模型仅在必要时触发。
 */
@Service
@Slf4j
public class WiseLinkExternalSearchService {

    private static final int SCRAPE_TIMEOUT_MS = 12_000;
    /** 抓取正文最大字符数，避免注入对话的文本过长导致 Token 溢出。 */
    private static final int SCRAPE_MAX_TEXT_CHARS = 2_000;
    private static final String USER_AGENT = "WiseLinkBot/2.0 (+https://example.invalid/wiselink; external-search)";
    private static final String MOCK_LINK_BASE = "https://demo.wiselink.invalid/low-price";

    /**
     * 注入给模型的安全约束：明确触发条件与反滥用要求。
     */
    private static final String TOOL_GUARDRAIL =
            "【调用约束 — 务必遵守】仅在本地知识不足或用户明确要求全网对比（例如明确提及「全网」「网上」「站外」「比价」「横向对比多家」等）时才可触发；"
                    + "严禁因重复试探、过度兜底或与本工具无关的闲聊而频繁调用，以节省 Token 与外部资源。"
                    + "普通站内导购、本地知识/RAG 足以回答的问题禁止调用。"
                    + " ";

    public record WebSearchRequest(String query) {
    }

    public record UrlRequest(String url) {
    }

    @WiseLinkTool(
            name = "searchProductOnWeb",
            description = TOOL_GUARDRAIL
                    + "模拟全网低价检索（演示）：根据关键词返回若干条带 URL 的占位链接与参考价摘要，非真实电商接口；"
                    + "用于演示「全网比价」链路，上线后可替换为合规搜索 API。")
    public String searchProductOnWeb(WebSearchRequest request) {
        try {
            String q = request == null || request.query() == null ? "" : request.query().trim();
            if (q.isEmpty()) {
                return "错误：searchProductOnWeb 需要非空的 query。";
            }
            log.info(">>>> [WiseLink-External] 模拟联网搜索 query='{}'", q);

            String norm = q.toLowerCase(Locale.ROOT);
            String channelHint = "综合频道";
            if (norm.contains("耳机") || norm.contains("音响") || norm.contains("电视")) {
                channelHint = "影音数码";
            } else if (norm.contains("手机") || norm.contains("iphone") || norm.contains("小米")) {
                channelHint = "手机通讯";
            }

            String enc = URLEncoder.encode(q, StandardCharsets.UTF_8);
            String linkA = MOCK_LINK_BASE + "?kw=" + enc + "&merchant=demo-a&tag=入门";
            String linkB = MOCK_LINK_BASE + "?kw=" + enc + "&merchant=demo-b&tag=热销";
            String linkC = MOCK_LINK_BASE + "?kw=" + enc + "&merchant=demo-c&tag=旗舰";

            return """
                    【WiseLink 模拟全网低价链接】
                    关键词：%s
                    检索时间(UTC)：%s
                    频道推断：%s

                    说明：以下链接与价格为演示数据（域名无效、不可访问），不代表真实电商平台实时报价；正式环境请接入合规比价/搜索服务。

                    [1] %s — 入门套装 · 演示商户 A
                        模拟低价链接：%s
                        参考到手价：¥1999–¥2299（演示）

                    [2] %s — 热销款 · 演示商户 B
                        模拟低价链接：%s
                        参考闪购价：¥2088 起（演示）

                    [3] %s — 旗舰对比 · 演示商户 C
                        模拟低价链接：%s
                        参考满减后：约 ¥2149（演示）
                    """
                    .formatted(
                            q,
                            Instant.now().toString(),
                            channelHint,
                            q,
                            linkA,
                            q,
                            linkB,
                            q,
                            linkC);
        } catch (Exception ex) {
            log.warn(">>>> [WiseLink-External] 模拟搜索异常: {}", ex.toString());
            return "模拟全网检索处理失败（对话可继续）：" + ex.getMessage();
        }
    }

    @WiseLinkTool(
            name = "scrapeWebsiteContent",
            description = TOOL_GUARDRAIL
                    + "根据用户明确提供的公开商品详情页 URL（仅 http/https），使用 Jsoup 抓取页面并抽取标题与正文纯文本，"
                    + "供跨站摘要与比价引用；不得对用户未给出的链接、内网或非 HTTP(S) 地址擅自抓取。")
    public String scrapeWebsiteContent(UrlRequest request) {
        try {
            String raw = request == null || request.url() == null ? "" : request.url().trim();
            if (raw.isEmpty()) {
                return "错误：scrapeWebsiteContent 需要非空的 url。";
            }
            URI uri;
            try {
                uri = URI.create(raw);
            } catch (IllegalArgumentException ex) {
                return "错误：URL 无法解析 — " + ex.getMessage();
            }
            String scheme = uri.getScheme();
            if (scheme == null || !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                return "错误：仅允许 http 或 https URL。";
            }
            log.info(">>>> [WiseLink-External] Jsoup 抓取 url='{}'", uri);

            try {
                Document doc = Jsoup.connect(uri.normalize().toASCIIString())
                        .userAgent(USER_AGENT)
                        .timeout(SCRAPE_TIMEOUT_MS)
                        .maxBodySize(2_000_000)
                        .followRedirects(true)
                        .ignoreHttpErrors(false)
                        .get();

                doc.select("script, style, noscript, svg, iframe, header, footer, nav").remove();
                String title = Objects.requireNonNullElse(doc.title(), "").strip();
                String bodyText = doc.body() != null ? doc.body().text() : "";
                bodyText = bodyText == null ? "" : bodyText.replace('\u00a0', ' ').strip();
                bodyText = collapseWhitespace(bodyText);

                String core;
                if (title.isEmpty()) {
                    core = bodyText;
                } else {
                    core = "标题: " + title + "\n\n正文:\n" + bodyText;
                }
                if (core.length() > SCRAPE_MAX_TEXT_CHARS) {
                    core = core.substring(0, SCRAPE_MAX_TEXT_CHARS)
                            + "\n…（已截断至前 "
                            + SCRAPE_MAX_TEXT_CHARS
                            + " 字符，防止 Token 溢出）";
                }
                return core;
            } catch (Exception ex) {
                log.warn(">>>> [WiseLink-External] 抓取失败 url='{}': {}", uri, ex.toString());
                return "抓取失败（对话可继续；可在合规前提下重试或更换 URL）：" + ex.getMessage();
            }
        } catch (Exception ex) {
            log.warn(">>>> [WiseLink-External] scrapeWebsiteContent 外层异常: {}", ex.toString());
            return "网页抓取流程异常（对话可继续）：" + ex.getMessage();
        }
    }

    private static String collapseWhitespace(String s) {
        if (s.isEmpty()) {
            return s;
        }
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
    }
}
