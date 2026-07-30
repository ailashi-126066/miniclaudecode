package dev.miniclaudecode.rag.parse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class MultiFormatDocumentExtractorTest {
  private final MultiFormatDocumentExtractor extractor = new MultiFormatDocumentExtractor();

  @Test
  void retainsHeadingAndTableSemanticsForHtmlAndCsv() throws Exception {
    String html =
        this.extractor
            .extract(
                Path.of("guide.html"),
                "<h1>Install Guide</h1><p>Run setup.</p><table><tr><th>Plan</th><th>Price</th></tr><tr><td>Pro</td><td>9</td></tr></table>"
                    .getBytes(StandardCharsets.UTF_8))
            .orElseThrow();
    String csv =
        this.extractor
            .extract(Path.of("plans.csv"), "Plan,Price\nPro,9\n".getBytes(StandardCharsets.UTF_8))
            .orElseThrow();

    Assertions.assertThat(html).contains("# Install Guide", "Plan=Pro", "Price=9");
    Assertions.assertThat(csv).contains("Plan=Pro", "Price=9");
  }

  @Test
  void retainsHeadingAndColumnSemanticsForOfficeDocuments() throws Exception {
    byte[] word;
    try (XWPFDocument document = new XWPFDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var heading = document.createParagraph();
      heading.setStyle("Heading1");
      heading.createRun().setText("Deployment");
      document.createParagraph().createRun().setText("Restart the service.");
      document.write(output);
      word = output.toByteArray();
    }
    byte[] spreadsheet;
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("Plans");
      sheet.createRow(0).createCell(0).setCellValue("Plan");
      sheet.getRow(0).createCell(1).setCellValue("Price");
      sheet.createRow(1).createCell(0).setCellValue("Pro");
      sheet.getRow(1).createCell(1).setCellValue(9);
      workbook.write(output);
      spreadsheet = output.toByteArray();
    }

    String docx = this.extractor.extract(Path.of("guide.docx"), word).orElseThrow();
    String xlsx = this.extractor.extract(Path.of("plans.xlsx"), spreadsheet).orElseThrow();

    Assertions.assertThat(docx).contains("# Deployment", "Restart the service.");
    Assertions.assertThat(xlsx).contains("[sheet: Plans]", "Plan=Pro", "Price=9");
  }
}
