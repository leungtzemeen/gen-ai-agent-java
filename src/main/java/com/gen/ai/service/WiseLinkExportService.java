package com.gen.ai.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.gen.ai.wiselink.annotation.WiseLinkTool;
import com.gen.ai.wiselink.security.WiseLinkToolSecurityInterceptor;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

import lombok.extern.slf4j.Slf4j;

/**
 * WiseLink 2.0：将选购建议导出为 PDF，供用户留存或分享。
 */
@Service
@Slf4j
public class WiseLinkExportService {

    private static final String EXPORT_SUBDIR = "exports";
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withLocale(Locale.ROOT);
    private static final DateTimeFormatter HEADER_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withLocale(Locale.ROOT);
    private static final int MAX_BODY_CHARS = 80_000;

    /** 可通过环境变量指定字体路径；TTC 需带索引，例如 {@code C:/Windows/Fonts/msyh.ttc,0}。 */
    private static final String ENV_FONT = "WISELINK_PDF_FONT";

    public record ShoppingReportExportRequest(String recommendationText) {
    }

    @WiseLinkTool(
            name = "exportShoppingReport",
            description = "将完整的选购建议、对比结论或方案总结导出为 PDF 文件。"
                    + "当用户表示想保存方案、发给家人朋友查看、留档、打印，或需要一份正式的选购建议书 / 简报时，请主动调用本工具；"
                    + "入参 recommendationText 应填入当前对话中已形成的可读选购结论（可分条、含预算与备选型号等）。"
                    + "调用成功后请将返回的文件路径告知用户，便于其在 exports 目录下打开该 PDF。"
                    + WiseLinkToolSecurityInterceptor.TOOL_DESCRIPTION_SECURITY_NOTICE)
    public String exportShoppingReport(ShoppingReportExportRequest request) {
        try {
            String raw = request == null || request.recommendationText() == null
                    ? ""
                    : request.recommendationText().trim();
            if (raw.isEmpty()) {
                return "错误：exportShoppingReport 需要非空的 recommendationText（选购建议正文）。";
            }
            if (raw.length() > MAX_BODY_CHARS) {
                raw = raw.substring(0, MAX_BODY_CHARS)
                        + "\n\n…（正文已超过 "
                        + MAX_BODY_CHARS
                        + " 字符上限，后续内容已省略以保证导出稳定。）";
            }

            Path projectRoot = Paths.get(System.getProperty("user.dir", ".")).normalize();
            Path exportDir = projectRoot.resolve(EXPORT_SUBDIR);
            Files.createDirectories(exportDir);

            String stamp = LocalDateTime.now().format(FILE_TS);
            Path target = exportDir.resolve("shopping-report-" + stamp + ".pdf");

            BaseFont baseFont = resolveChineseBaseFont();
            boolean cjkReady = BaseFont.IDENTITY_H.equals(baseFont.getEncoding());

            Font titleFont = new Font(baseFont, 16f, Font.BOLD);
            Font bodyFont = new Font(baseFont, 11f, Font.NORMAL);
            Font metaFont = new Font(baseFont, 9.5f, Font.NORMAL);

            String headerInstant = LocalDateTime.now().format(HEADER_TS);
            Path temp = Files.createTempFile(exportDir, ".shopping-report-", ".wip.pdf");
            try {
                try (OutputStream os = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                    Document document =
                            new Document(PageSize.A4, 54f, 54f, 72f, 72f);
                    PdfWriter writer = PdfWriter.getInstance(document, os);
                    writer.setPageEvent(new ReportPageDecoration(baseFont, headerInstant));
                    document.open();

                    if (!cjkReady) {
                        document.add(
                                new Paragraph(
                                        "Notice: No suitable CJK font was found. Set environment variable "
                                                + ENV_FONT
                                                + " to a .ttf/.ttc path (TTC example: C:/Windows/Fonts/msyh.ttc,0), "
                                                + "or add src/main/resources/fonts/*.ttf.",
                                        bodyFont));
                        document.add(new Paragraph(" ", bodyFont));
                    }

                    document.add(new Paragraph("WiseLink 2.0 选购建议书", titleFont));
                    document.add(new Paragraph("生成时间：" + headerInstant, metaFont));
                    document.add(new Paragraph(" ", metaFont));

                    for (String block : splitIntoParagraphs(raw)) {
                        Paragraph p = new Paragraph(block.isBlank() ? " " : block, bodyFont);
                        p.setSpacingAfter(6f);
                        p.setAlignment(Element.ALIGN_JUSTIFIED);
                        document.add(p);
                    }

                    document.close();
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                temp = null;
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException ex) {
                        log.debug("删除临时 PDF 失败: {}", ex.getMessage());
                    }
                }
            }

            Path absolute = target.toAbsolutePath().normalize();
            log.info(">>>> [WiseLink-Export] PDF 已生成 path='{}'", absolute);
            return "导出成功。PDF 绝对路径：" + absolute + "（请将该路径告知用户，在项目 exports 目录下可打开。）";
        } catch (Exception ex) {
            log.warn(">>>> [WiseLink-Export] 导出失败: {}", ex.toString());
            return "PDF 导出失败（对话可继续）：" + ex.getMessage();
        }
    }

    private static List<String> splitIntoParagraphs(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> out = new ArrayList<>();
        for (String line : normalized.split("\n", -1)) {
            out.add(line);
        }
        return out;
    }

    private static BaseFont resolveChineseBaseFont() throws DocumentException, IOException {
        List<String> candidates = new ArrayList<>();
        String env = System.getenv(ENV_FONT);
        if (env != null && !env.isBlank()) {
            candidates.add(env.trim());
        }
        candidates.addAll(
                List.of(
                        "C:/Windows/Fonts/msyh.ttc,0",
                        "C:/Windows/Fonts/msyhbd.ttc,0",
                        "C:/Windows/Fonts/simsun.ttc,0",
                        "C:/Windows/Fonts/simhei.ttf",
                        "C:/Windows/Fonts/msyh.ttf",
                        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                        "/usr/share/fonts/truetype/arphic/uming.ttc",
                        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0",
                        "/Library/Fonts/Arial Unicode.ttf",
                        "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
                        "/System/Library/Fonts/PingFang.ttc,0"));

        for (String spec : candidates) {
            Path pathOnly = fontPathOnly(spec);
            try {
                if (!Files.isRegularFile(pathOnly)) {
                    continue;
                }
                return BaseFont.createFont(spec, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception ex) {
                log.debug("跳过不可用字体 spec='{}': {}", spec, ex.getMessage());
            }
        }

        try (InputStream in =
                WiseLinkExportService.class.getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            if (in != null) {
                byte[] bytes = in.readAllBytes();
                return BaseFont.createFont(
                        "NotoSansSC-Regular.ttf",
                        BaseFont.IDENTITY_H,
                        BaseFont.EMBEDDED,
                        true,
                        bytes,
                        null);
            }
        }

        log.warn(
                "未找到可用的中文字体（已尝试常见系统路径与 classpath:/fonts/NotoSansSC-Regular.ttf）。"
                        + "请设置 {} 或自行放置 TTF。PDF 将退回 Helvetica，中文可能显示为空白或乱码。",
                ENV_FONT);
        return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, false);
    }

    private static Path fontPathOnly(String fontSpec) {
        int comma = fontSpec.indexOf(',');
        String pathPart = comma >= 0 ? fontSpec.substring(0, comma) : fontSpec;
        return Paths.get(pathPart);
    }

    private static final class ReportPageDecoration extends PdfPageEventHelper {

        private final BaseFont bf;
        private final String generatedAt;

        ReportPageDecoration(BaseFont bf, String generatedAt) {
            this.bf = bf;
            this.generatedAt = generatedAt;
        }

        @Override
        public void onStartPage(PdfWriter writer, Document document) {
            Rectangle ps = document.getPageSize();
            PdfContentByte cb = writer.getDirectContent();
            float left = ps.getLeft() + 48f;
            float right = ps.getRight() - 48f;
            float top = ps.getTop() - 26f;

            cb.saveState();
            cb.setLineWidth(0.4f);
            cb.moveTo(left, ps.getTop() - 34f);
            cb.lineTo(right, ps.getTop() - 34f);
            cb.stroke();
            cb.restoreState();

            cb.saveState();
            cb.beginText();
            cb.setFontAndSize(bf, 9f);
            cb.showTextAligned(Element.ALIGN_LEFT, "WiseLink 2.0 · 选购报告", left, top, 0f);
            cb.showTextAligned(Element.ALIGN_RIGHT, "生成时间 " + generatedAt, right, top, 0f);
            cb.endText();
            cb.restoreState();
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle ps = document.getPageSize();
            PdfContentByte cb = writer.getDirectContent();
            float cx = (ps.getLeft() + ps.getRight()) / 2f;
            float footerY = ps.getBottom() + 28f;

            cb.saveState();
            cb.setLineWidth(0.35f);
            cb.moveTo(ps.getLeft() + 48f, ps.getBottom() + 38f);
            cb.lineTo(ps.getRight() - 48f, ps.getBottom() + 38f);
            cb.stroke();
            cb.restoreState();

            String footerText =
                    "本文件由 WiseLink 导购助手自动生成 · 第 " + writer.getPageNumber() + " 页";

            cb.saveState();
            cb.beginText();
            cb.setFontAndSize(bf, 8.5f);
            cb.showTextAligned(Element.ALIGN_CENTER, footerText, cx, footerY, 0f);
            cb.endText();
            cb.restoreState();
        }
    }
}
