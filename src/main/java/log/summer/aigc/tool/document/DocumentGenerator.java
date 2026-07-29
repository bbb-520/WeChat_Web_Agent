package log.summer.aigc.tool.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * Generates Office documents (Word, PPT, Excel) from structured outline JSON
 * using Apache POI.
 *
 * <h3>Supported formats</h3>
 * <ul>
 *   <li><b>Word</b> (.docx) — heading paragraphs for sections, bullet points for items</li>
 *   <li><b>PPT</b>  (.pptx) — one slide per section, title + bullet list</li>
 *   <li><b>Excel</b> (.xlsx) — one sheet, sections as row groups with bold headers</li>
 * </ul>
 *
 * <p>Outline JSON format:</p>
 * <pre>{@code
 * {
 *   "title": "Document Title",
 *   "sections": [
 *     {"title": "Section 1", "points": ["point a", "point b"]},
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
public class DocumentGenerator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ═══════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════

    /**
     * Generate a Word (.docx) document from an outline.
     *
     * @param title       the document title
     * @param outlineJson structured outline JSON string
     * @return the .docx file as a byte array
     * @throws OutlineParseException if the outline JSON is malformed or empty
     */
    public byte[] generateWord(String title, String outlineJson) {
        OutlineData outline = parseOutline(outlineJson);

        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            // ── Title ──
            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText(title);
            titleRun.setBold(true);
            titleRun.setFontSize(20);
            titleRun.setFontFamily("Microsoft YaHei");
            titleRun.addBreak();

            // ── Blank line after title ──
            doc.createParagraph();

            // ── Sections ──
            for (int i = 0; i < outline.sections().size(); i++) {
                SectionData section = outline.sections().get(i);

                // Section heading
                XWPFParagraph heading = doc.createParagraph();
                XWPFRun headingRun = heading.createRun();
                headingRun.setText((i + 1) + ". " + section.title());
                headingRun.setBold(true);
                headingRun.setFontSize(16);
                headingRun.setFontFamily("Microsoft YaHei");

                // Section points
                for (String point : section.points()) {
                    XWPFParagraph pointPara = doc.createParagraph();
                    pointPara.setIndentationLeft(400);
                    XWPFRun pointRun = pointPara.createRun();
                    pointRun.setText("• " + point);
                    pointRun.setFontSize(12);
                    pointRun.setFontFamily("Microsoft YaHei");
                }

                // Blank line between sections
                doc.createParagraph();
            }

            doc.write(baos);
            log.info("[DOC-GEN] Word 生成完成 | title={} | sections={} | size={}bytes",
                    title, outline.sections().size(), baos.size());
            return baos.toByteArray();

        } catch (OutlineParseException e) {
            throw e;
        } catch (Exception e) {
            log.error("[DOC-GEN] Word 生成失败 | title={}", title, e);
            throw new RuntimeException("Word document generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generate a PowerPoint (.pptx) document from an outline.
     *
     * @param title       the presentation title
     * @param outlineJson structured outline JSON string
     * @return the .pptx file as a byte array
     * @throws OutlineParseException if the outline JSON is malformed or empty
     */
    public byte[] generatePpt(String title, String outlineJson) {
        OutlineData outline = parseOutline(outlineJson);

        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            // ── Title slide ──
            XSLFSlide titleSlide = ppt.createSlide();
            XSLFTextBox titleBox = titleSlide.createTextBox();
            titleBox.setAnchor(new Rectangle(50, 80, 620, 100));
            XSLFTextParagraph titlePara = titleBox.addNewTextParagraph();
            titlePara.setTextAlign(TextAlign.CENTER);
            XSLFTextRun titleRun = titlePara.addNewTextRun();
            titleRun.setText(title);
            titleRun.setBold(true);
            titleRun.setFontSize(36.0);
            titleRun.setFontFamily("Microsoft YaHei");
            titleRun.setFontColor(Color.BLACK);

            // ── Content slides (one per section) ──
            for (SectionData section : outline.sections()) {
                XSLFSlide slide = ppt.createSlide();

                // Section title
                XSLFTextBox sectionTitleBox = slide.createTextBox();
                sectionTitleBox.setAnchor(new Rectangle(50, 40, 620, 60));
                XSLFTextParagraph stPara = sectionTitleBox.addNewTextParagraph();
                XSLFTextRun stRun = stPara.addNewTextRun();
                stRun.setText(section.title());
                stRun.setBold(true);
                stRun.setFontSize(28.0);
                stRun.setFontFamily("Microsoft YaHei");
                stRun.setFontColor(new Color(0x1A, 0x56, 0xDB)); // blue accent

                // Separator line
                XSLFTextBox sepBox = slide.createTextBox();
                sepBox.setAnchor(new Rectangle(50, 95, 620, 5));
                XSLFTextParagraph sepPara = sepBox.addNewTextParagraph();
                XSLFTextRun sepRun = sepPara.addNewTextRun();
                sepRun.setText("━━━━━━━━━━━━━━━━━━━━━━━━━━");
                sepRun.setFontSize(10.0);
                sepRun.setFontColor(Color.LIGHT_GRAY);

                // Points
                XSLFTextBox pointsBox = slide.createTextBox();
                pointsBox.setAnchor(new Rectangle(70, 120, 580, 300));
                XSLFTextParagraph pointsPara = pointsBox.addNewTextParagraph();
                for (int j = 0; j < section.points().size(); j++) {
                    if (j > 0) pointsPara.addLineBreak();
                    XSLFTextRun pointRun = pointsPara.addNewTextRun();
                    pointRun.setText("• " + section.points().get(j));
                    pointRun.setFontSize(18.0);
                    pointRun.setFontFamily("Microsoft YaHei");
                    pointRun.setFontColor(Color.DARK_GRAY);
                }
            }

            ppt.write(baos);
            log.info("[DOC-GEN] PPT 生成完成 | title={} | slides={} | size={}bytes",
                    title, outline.sections().size() + 1, baos.size());
            return baos.toByteArray();

        } catch (OutlineParseException e) {
            throw e;
        } catch (Exception e) {
            log.error("[DOC-GEN] PPT 生成失败 | title={}", title, e);
            throw new RuntimeException("PPT document generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generate an Excel (.xlsx) document from an outline.
     *
     * @param title       the spreadsheet title
     * @param outlineJson structured outline JSON string
     * @return the .xlsx file as a byte array
     * @throws OutlineParseException if the outline JSON is malformed or empty
     */
    public byte[] generateExcel(String title, String outlineJson) {
        OutlineData outline = parseOutline(outlineJson);

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            var sheet = wb.createSheet(title != null ? title : "Sheet1");

            // ── Styles ──
            var headerStyle = wb.createCellStyle();
            var headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 14);
            headerStyle.setFont(headerFont);

            var sectionStyle = wb.createCellStyle();
            var sectionFont = wb.createFont();
            sectionFont.setBold(true);
            sectionFont.setFontHeightInPoints((short) 12);
            sectionStyle.setFont(sectionFont);

            int rowIdx = 0;

            // ── Title row ──
            var titleRow = sheet.createRow(rowIdx++);
            var titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title);
            titleCell.setCellStyle(headerStyle);
            rowIdx++; // blank row

            // ── Sections ──
            for (SectionData section : outline.sections()) {
                // Section header
                var sectionRow = sheet.createRow(rowIdx++);
                var sectionCell = sectionRow.createCell(0);
                sectionCell.setCellValue(section.title());
                sectionCell.setCellStyle(sectionStyle);

                // Points (indented via column B)
                for (String point : section.points()) {
                    var pointRow = sheet.createRow(rowIdx++);
                    pointRow.createCell(0).setCellValue("");  // indent
                    pointRow.createCell(1).setCellValue("• " + point);
                }

                rowIdx++; // blank row between sections
            }

            // Auto-size columns
            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);

            wb.write(baos);
            log.info("[DOC-GEN] Excel 生成完成 | title={} | sections={} | size={}bytes",
                    title, outline.sections().size(), baos.size());
            return baos.toByteArray();

        } catch (OutlineParseException e) {
            throw e;
        } catch (Exception e) {
            log.error("[DOC-GEN] Excel 生成失败 | title={}", title, e);
            throw new RuntimeException("Excel document generation failed: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Outline parsing
    // ═══════════════════════════════════════════════════════════

    /**
     * Parse outline JSON into typed data objects.
     */
    @SuppressWarnings("unchecked")
    private OutlineData parseOutline(String outlineJson) {
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(outlineJson, Map.class);

            List<Map<String, Object>> sectionsRaw =
                    (List<Map<String, Object>>) root.get("sections");
            if (sectionsRaw == null || sectionsRaw.isEmpty()) {
                throw new OutlineParseException("大纲中没有定义任何章节（sections 为空）");
            }

            List<SectionData> sections = sectionsRaw.stream()
                    .map(s -> {
                        String sectionTitle = (String) s.getOrDefault("title", "");
                        List<String> points = (List<String>) s.getOrDefault("points", List.of());
                        return new SectionData(sectionTitle, points);
                    })
                    .toList();

            return new OutlineData(
                    (String) root.getOrDefault("title", "Untitled"),
                    sections);

        } catch (OutlineParseException e) {
            throw e;
        } catch (Exception e) {
            throw new OutlineParseException("大纲 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Data types
    // ═══════════════════════════════════════════════════════════

    record OutlineData(String title, List<SectionData> sections) {}

    record SectionData(String title, List<String> points) {}

    /**
     * Thrown when the outline JSON cannot be parsed or is semantically invalid.
     */
    public static class OutlineParseException extends RuntimeException {
        public OutlineParseException(String message) {
            super(message);
        }

        public OutlineParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
