package com.gen.ai.service;

import java.net.URI;
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
    private static final int SCRAPE_MAX_TEXT_CHARS = 12_000;
    private static final String USER_AGENT = "WiseLinkBot/2.0 (+https://example.invalid/wiselink; external-search)";

    private static final String TOOL_GUARDRAIL =
            "【调用约束】仅当本地知识库/RAG 仍不足以回答，且用户话术中明确出现「全网」「网上」「站外」「比价」「对比价格」「横向对比」「多家对比」等意图时才可调用；"
                    + "普通询价、库存与站内导购场景禁止调用，以减少 Token 与外部请求。"
                    + " ";

    public record WebSearchRequest(String query) {
    }

    public record UrlRequest(String url) {
    }

    @WiseLinkTool(
            name = "searchProductOnWeb",
            description = TOOL_GUARDRAIL
                    + "模拟联网商品检索：根据关键词返回若干条演示级摘要（非实时电商 API），用于全网比价场景的占位数据。")
    public String searchProductOnWeb(WebSearchRequest request) {
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

        return """
                【WiseLink 模拟全网检索】
                关键词：%s
                检索时间(UTC)：%s
                频道推断：%s

                说明：以下为演示数据，不代表真实电商平台实时报价；正式上线可替换为合规搜索/比价 API。

                [1] 标题：%s — 入门套装 · 演示商户 A
                    摘要：参考到手价区间 ¥1999–¥2299（含税以商户为准），支持 7 天无理由（演示文案）。
                    来源标签：演示站 · 商户 A

                [2] 标题：%s — 热销款 · 演示商户 B
                    摘要：参考闪购价 ¥2088 起，赠品库存有限（演示文案）。
                    来源标签：演示站 · 商户 B

                [3] 标题：%s — 旗舰对比 · 演示商户 C
                    摘要：跨店满减后约 ¥2149，含延保套餐选项（演示文案）。
                    来源标签：演示站 · 商户 C
                """
                .formatted(q, Instant.now().toString(), channelHint, q, q, q);
    }

    @WiseLinkTool(
            name = "scrapeWebsiteContent",
            description = TOOL_GUARDRAIL
                    + "根据用户提供的公开商品详情页 URL（仅 http/https），使用 Jsoup 抓取页面正文纯文本，供跨站摘要与比价引用；"
                    + "不得对用户未给出的链接或内网地址擅自抓取。")
    public String scrapeWebsiteContent(UrlRequest request) {
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

            doc.select("script, style, noscript, svg").remove();
            String title = Objects.requireNonNullElse(doc.title(), "").strip();
            String text = doc.body() != null ? doc.body().text() : doc.text();
            text = text == null ? "" : text.replace('\u00a0', ' ').strip();
            if (text.length() > SCRAPE_MAX_TEXT_CHARS) {
                text = text.substring(0, SCRAPE_MAX_TEXT_CHARS) + "\n…（正文已截断，上限 " + SCRAPE_MAX_TEXT_CHARS + " 字符）";
            }
            String header = title.isEmpty() ? "" : "标题: " + title + "\n\n";
            return header + text;
        } catch (Exception ex) {
            log.warn(">>>> [WiseLink-External] 抓取失败 url='{}': {}", uri, ex.toString());
            return "抓取失败（可在合规前提下重试或更换 URL）：" + ex.getMessage();
        }
    }
}
