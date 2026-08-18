package com.mewcode.rag.parse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Extracts page, heading, sheet and table structure instead of handing binary blobs to chunking.
 * The output is intentionally plain text: the existing chunkers and index retain their normal
 * change-detection and source path semantics.
 */
public final class MultiFormatDocumentExtractor implements DocumentTextExtractor {
  @Override
  public boolean supports(Path path) {
    return switch (extension(path)) {
      case "pdf", "docx", "xlsx", "xls", "html", "htm", "csv", "tsv" -> true;
      default -> false;
    };
  }

  @Override
  public Optional<String> extract(Path path, byte[] bytes) throws IOException {
    String text =
        switch (extension(path)) {
          case "pdf" -> pdf(bytes);
          case "docx" -> docx(bytes);
          case "xlsx", "xls" -> workbook(bytes);
          case "html", "htm" -> html(bytes);
          case "csv" -> delimited(bytes, ',');
          case "tsv" -> delimited(bytes, '\t');
          default -> "";
        };
    return text.isBlank() ? Optional.empty() : Optional.of(text.strip());
  }

  private static String pdf(byte[] bytes) throws IOException {
    try (PDDocument document = Loader.loadPDF(bytes)) {
      PDFTextStripper stripper = new PDFTextStripper();
      StringBuilder output = new StringBuilder();
      for (int page = 1; page <= document.getNumberOfPages(); page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        output.append("[page ").append(page).append("]\n").append(stripper.getText(document));
      }
      return output.toString();
    }
  }

  private static String docx(byte[] bytes) throws IOException {
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
      StringBuilder output = new StringBuilder();
      for (XWPFParagraph paragraph : document.getParagraphs()) {
        String text = paragraph.getText().trim();
        if (!text.isEmpty()) {
          String style = Optional.ofNullable(paragraph.getStyle()).orElse("");
          output
              .append(style.toLowerCase(Locale.ROOT).startsWith("heading") ? "# " : "")
              .append(text)
              .append('\n');
        }
      }
      for (XWPFTable table : document.getTables()) {
        appendWordTable(output, table);
      }
      return output.toString();
    }
  }

  private static void appendWordTable(StringBuilder output, XWPFTable table) {
    List<XWPFTableRow> rows = table.getRows();
    if (rows.isEmpty()) {
      return;
    }
    List<String> headers =
        rows.getFirst().getTableCells().stream().map(cell -> cell.getText().trim()).toList();
    output.append("[table]\n");
    for (int row = 1; row < rows.size(); row++) {
      List<String> cells =
          rows.get(row).getTableCells().stream().map(cell -> cell.getText().trim()).toList();
      appendKeyValues(output, headers, cells);
    }
  }

  private static String workbook(byte[] bytes) throws IOException {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      StringBuilder output = new StringBuilder();
      DataFormatter formatter = new DataFormatter(Locale.ROOT);
      for (Sheet sheet : workbook) {
        Iterator<Row> rows = sheet.rowIterator();
        if (!rows.hasNext()) {
          continue;
        }
        List<String> headers = cells(rows.next(), formatter);
        output.append("[sheet: ").append(sheet.getSheetName()).append("]\n");
        while (rows.hasNext()) {
          appendKeyValues(output, headers, cells(rows.next(), formatter));
        }
      }
      return output.toString();
    }
  }

  private static List<String> cells(Row row, DataFormatter formatter) {
    List<String> values = new ArrayList<>();
    int width = Math.max(0, row.getLastCellNum());
    for (int index = 0; index < width; index++) {
      Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
      values.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
    }
    return values;
  }

  private static String html(byte[] bytes) {
    Document document = Jsoup.parse(new String(bytes, StandardCharsets.UTF_8));
    document.select("script,style,noscript,nav,footer").remove();
    StringBuilder output = new StringBuilder();
    for (Element element : document.select("h1,h2,h3,h4,h5,h6,p,li")) {
      String text = element.text().trim();
      if (!text.isEmpty()) {
        output.append(element.tagName().startsWith("h") ? "# " : "").append(text).append('\n');
      }
    }
    for (Element table : document.select("table")) {
      List<String> headers =
          table.select("tr").stream()
              .findFirst()
              .map(MultiFormatDocumentExtractor::tableCells)
              .orElse(List.of());
      for (Element row : table.select("tr").stream().skip(1).toList()) {
        appendKeyValues(output, headers, tableCells(row));
      }
    }
    return output.toString();
  }

  private static List<String> tableCells(Element row) {
    return row.select("th,td").stream().map(Element::text).map(String::trim).toList();
  }

  private static String delimited(byte[] bytes, char delimiter) {
    String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R");
    if (lines.length == 0) {
      return "";
    }
    List<String> headers = parseDelimited(lines[0], delimiter);
    StringBuilder output = new StringBuilder("[table]\n");
    for (int row = 1; row < lines.length; row++) {
      if (!lines[row].isBlank()) {
        appendKeyValues(output, headers, parseDelimited(lines[row], delimiter));
      }
    }
    return output.toString();
  }

  private static List<String> parseDelimited(String line, char delimiter) {
    List<String> fields = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < line.length(); index++) {
      char value = line.charAt(index);
      if (value == '"') {
        if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
          current.append(value);
          index++;
        } else {
          quoted = !quoted;
        }
      } else if (value == delimiter && !quoted) {
        fields.add(current.toString().trim());
        current.setLength(0);
      } else {
        current.append(value);
      }
    }
    fields.add(current.toString().trim());
    return List.copyOf(fields);
  }

  private static void appendKeyValues(
      StringBuilder output, List<String> headers, List<String> values) {
    for (int index = 0; index < values.size(); index++) {
      String header =
          index < headers.size() && !headers.get(index).isBlank()
              ? headers.get(index)
              : "column" + (index + 1);
      output
          .append(header)
          .append('=')
          .append(values.get(index))
          .append(index + 1 == values.size() ? '\n' : ", ");
    }
  }

  private static String extension(Path path) {
    Path fileName = Objects.requireNonNull(path.getFileName(), "document path needs a file name");
    String name = fileName.toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }
}
