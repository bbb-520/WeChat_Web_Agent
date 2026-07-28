package log.summer.aigc.tool.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DocumentGeneratorTest {

    private DocumentGenerator generator;

    private static final String SAMPLE_OUTLINE = """
        {
          "title": "Q2业绩报告",
          "sections": [
            {"title": "业绩概览", "points": ["总营收 5000万", "同比增长 15%"]},
            {"title": "各部门分析", "points": ["销售部达成率 120%", "研发部完成3个核心项目"]},
            {"title": "下季度规划", "points": ["拓展华东市场", "上线新版CRM系统"]}
          ]
        }""";

    @BeforeEach
    void setUp() {
        generator = new DocumentGenerator();
    }

    @Test
    void shouldGenerateWordDocument(@TempDir Path tempDir) throws IOException {
        byte[] docxBytes = generator.generateWord("Q2业绩报告", SAMPLE_OUTLINE);

        assertNotNull(docxBytes);
        assertTrue(docxBytes.length > 0, "Word document should have content");

        // Verify it's a valid ZIP (OOXML format)
        Path file = tempDir.resolve("test.docx");
        Files.write(file, docxBytes);
        assertTrue(Files.size(file) > 0);
    }

    @Test
    void shouldGeneratePptDocument(@TempDir Path tempDir) throws IOException {
        byte[] pptxBytes = generator.generatePpt("Q2业绩报告", SAMPLE_OUTLINE);

        assertNotNull(pptxBytes);
        assertTrue(pptxBytes.length > 0, "PPT document should have content");

        Path file = tempDir.resolve("test.pptx");
        Files.write(file, pptxBytes);
        assertTrue(Files.size(file) > 0);
    }

    @Test
    void shouldGenerateExcelDocument(@TempDir Path tempDir) throws IOException {
        byte[] xlsxBytes = generator.generateExcel("Q2业绩报告", SAMPLE_OUTLINE);

        assertNotNull(xlsxBytes);
        assertTrue(xlsxBytes.length > 0, "Excel document should have content");

        Path file = tempDir.resolve("test.xlsx");
        Files.write(file, xlsxBytes);
        assertTrue(Files.size(file) > 0);
    }

    @Test
    void shouldRejectInvalidOutlineJson() {
        assertThrows(DocumentGenerator.OutlineParseException.class, () -> {
            generator.generateWord("Test", "{invalid json}");
        });
    }

    @Test
    void shouldRejectEmptySections() {
        String emptyOutline = "{\"title\":\"Test\", \"sections\":[]}";

        assertThrows(DocumentGenerator.OutlineParseException.class, () -> {
            generator.generateWord("Test", emptyOutline);
        });
    }
}
