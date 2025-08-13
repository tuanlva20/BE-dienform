package com.dienform.tool.mahoa.service.impl;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import com.dienform.tool.dienformtudong.datamapping.dto.response.SheetAccessibilityInfo;
import com.dienform.tool.dienformtudong.datamapping.service.GoogleSheetsService;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import com.dienform.tool.dienformtudong.googleform.service.GoogleFormService;
import com.dienform.tool.dienformtudong.googleform.util.GoogleFormParser;
import com.dienform.tool.dienformtudong.googleform.util.GoogleFormParser.ExtractedOption;
import com.dienform.tool.dienformtudong.googleform.util.GoogleFormParser.ExtractedQuestion;
import com.dienform.tool.mahoa.dto.request.EncodeDataRequest;
import com.dienform.tool.mahoa.service.DataEncodingService;
import com.dienform.tool.mahoa.service.model.EncodeResult;
import com.dienform.tool.mahoa.service.model.EncodeResult.RowError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataEncodingServiceImpl implements DataEncodingService {

  // Encoders
  private interface QuestionEncoder {
    String encode(String value);

    String getQuestionTitle();

    default String getGridRowTitle() {
      return null;
    }
  }
  private static class SimpleEncoder implements QuestionEncoder {
    // keep only the fields we need to avoid unused warnings
    private final Map<String, Integer> optionIndexByText;
    private final boolean multiSelect;
    private final String questionTitle;
    private final Integer otherIndex; // 1-based index of "Khác" if present

    SimpleEncoder(ExtractedQuestion q) {
      this.optionIndexByText = new HashMap<>();
      int idx = 1; // index starts from 1
      Integer tempOtherIndex = null;
      for (ExtractedOption opt : q.getOptions()) {
        optionIndexByText.put(opt.getText() == null ? "" : opt.getText().trim().toLowerCase(),
            idx++);
        String val = opt.getValue();
        if (tempOtherIndex == null && val != null && "__other_option__".equalsIgnoreCase(val)) {
          // The index we just assigned corresponds to this option
          tempOtherIndex = idx - 1;
        }
      }
      String type = q.getType() == null ? "" : q.getType().toLowerCase();
      this.multiSelect = type.contains("checkbox");
      this.questionTitle = q.getTitle();
      this.otherIndex = tempOtherIndex;
    }

    @Override
    public String encode(String value) {
      if (value == null || value.isEmpty())
        return "";
      if (optionIndexByText.isEmpty())
        return value; // free text

      if (multiSelect) {
        // Multi-select encoding with support for 'Khác' text when unmatched input remains
        String raw = value.trim();

        List<Integer> matchedIndices = new ArrayList<>();
        String otherText = null;

        if (raw.contains("|")) {
          // User explicitly separated options with '|'
          String[] parts = raw.split("\\|", -1);
          List<String> unmatchedParts = new ArrayList<>();
          for (String p : parts) {
            String token = p == null ? "" : p.trim();
            if (token.isEmpty())
              continue;
            Integer idx = optionIndexByText.get(token.toLowerCase());
            if (idx != null) {
              matchedIndices.add(idx);
            } else {
              unmatchedParts.add(token);
            }
          }
          if (!unmatchedParts.isEmpty()) {
            // Treat all unmatched as Other text if 'Khác' exists
            if (otherIndex == null) {
              throw new IllegalArgumentException(
                  "Giá trị không khớp lựa chọn: '" + String.join("|", unmatchedParts)
                      + "'. Câu hỏi không có lựa chọn 'Khác'. Vui lòng chọn đúng tiêu đề đáp án.");
            }
            otherText = String.join(" ", unmatchedParts).trim();
          }
        } else {
          // User separated by commas; options/text may contain commas -> greedy matching
          String[] segs = raw.split(",");
          List<String> otherSegments = new ArrayList<>();
          int i = 0;
          while (i < segs.length) {
            String bestMatch = null;
            int bestJ = i;
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < segs.length; j++) {
              if (j > i)
                sb.append(",");
              sb.append(segs[j]);
              String candidate = sb.toString().trim().toLowerCase();
              if (optionIndexByText.containsKey(candidate)) {
                bestMatch = candidate;
                bestJ = j;
              }
            }
            if (bestMatch != null) {
              matchedIndices.add(optionIndexByText.get(bestMatch));
              i = bestJ + 1;
            } else {
              // accumulate to Other text
              otherSegments.add(segs[i].trim());
              i++;
            }
          }
          if (!otherSegments.isEmpty()) {
            if (otherIndex == null) {
              throw new IllegalArgumentException(
                  "Một phần giá trị không khớp bất kỳ lựa chọn nào: '"
                      + String.join(", ", otherSegments)
                      + "'. Câu hỏi không có lựa chọn 'Khác'. Vui lòng chọn đúng tiêu đề đáp án.");
            }
            otherText = String.join(",", otherSegments).trim();
          }
        }

        if (matchedIndices.isEmpty() && (otherText == null || otherText.isEmpty())) {
          throw new IllegalArgumentException("Không tìm thấy đáp án hợp lệ để mã hóa.");
        }

        matchedIndices = matchedIndices.stream().distinct().collect(Collectors.toList());
        matchedIndices.sort(Integer::compareTo);
        String left = matchedIndices.stream().map(String::valueOf).collect(Collectors.joining("|"));
        if (otherText != null && !otherText.isEmpty()) {
          if (otherIndex == null) {
            throw new IllegalArgumentException(
                "Đã nhập ghi chú cho 'Khác' nhưng câu hỏi không có lựa chọn 'Khác'.");
          }
          // Không ép thêm index 'Khác' vào bên trái. Ưu tiên để nguyên text user ở hậu tố
          return (left == null || left.isEmpty()) ? otherText : (left + "-" + otherText);
        }
        return left;
      } else {
        String raw = value.trim();
        Integer idx = optionIndexByText.get(raw.toLowerCase());
        if (idx != null) {
          return String.valueOf(idx);
        }
        // Không thêm otherIndex-text. Nếu có 'Khác' thì ưu tiên giữ nguyên text người dùng
        if (otherIndex != null) {
          return raw;
        }
        throw new IllegalArgumentException(
            "Đáp án '" + value + "' không tồn tại và câu hỏi không có lựa chọn 'Khác'.");
      }
    }

    @Override
    public String getQuestionTitle() {
      return questionTitle;
    }

    private List<String> tokenizeMultiValues(String raw) {
      String input = raw.trim();
      // Case 1: explicit "|" delimiter used
      if (input.contains("|")) {
        String[] parts = input.split("\\|", -1);
        List<String> list = new ArrayList<>();
        for (String p : parts)
          list.add(p.trim());
        return list;
      }

      // Case 2: comma-delimited, but options themselves may contain commas.
      // We perform a greedy match over segments joined by commas to map to known options.
      String[] segs = input.split(",");
      List<String> tokens = new ArrayList<>();
      int i = 0;
      while (i < segs.length) {
        String bestMatch = null;
        int bestJ = i;
        StringBuilder sb = new StringBuilder();
        for (int j = i; j < segs.length; j++) {
          if (j > i)
            sb.append(",");
          sb.append(segs[j]);
          String candidate = sb.toString().trim().toLowerCase();
          if (optionIndexByText.containsKey(candidate)) {
            bestMatch = candidate;
            bestJ = j;
          }
        }
        if (bestMatch != null) {
          tokens.add(bestMatch);
          i = bestJ + 1;
        } else {
          // fallback: use the single segment (will trigger error if not matched)
          tokens.add(segs[i].trim());
          i++;
        }
      }
      return tokens;
    }
  }
  
  private static class GridEncoder implements QuestionEncoder {
    private final Map<String, Integer> columnIndexByText = new HashMap<>();
    private final String rowTitle;
    private final boolean multiSelect; // checkbox_grid supports multiple selections
    private final String questionTitle;

    GridEncoder(ExtractedQuestion gridQuestion, String rowTitle) {
      this.rowTitle = rowTitle;
      String type = gridQuestion.getType() == null ? "" : gridQuestion.getType().toLowerCase();
      this.multiSelect = type.contains("checkbox");
      this.questionTitle = gridQuestion.getTitle();
      // find row by text, then its subOptions are columns
      for (ExtractedOption row : gridQuestion.getOptions()) {
        if (row.isRow() && rowTitle.equalsIgnoreCase(row.getText().trim())) {
          for (ExtractedOption col : row.getSubOptions()) {
            String key = col.getText() == null ? "" : col.getText().trim().toLowerCase();
            columnIndexByText.put(key, col.getPosition() + 1);
          }
          return;
        }
      }
      // If not found, still allow encoding with no options -> will keep value
    }

    @Override
    public String encode(String value) {
      if (value == null || value.isEmpty())
        return "";
      if (columnIndexByText.isEmpty())
        return value; // cannot map without known columns

      if (multiSelect) {
        List<String> tokens = tokenizeMultiValues(value);
        List<Integer> encoded = new ArrayList<>();
        for (String token : tokens) {
          if (token.isBlank())
            continue;
          Integer idx = columnIndexByText.get(token.trim().toLowerCase());
          if (idx == null) {
            throw new IllegalArgumentException("Đáp án '" + token + "' không tồn tại!");
          }
          encoded.add(idx);
        }
        return encoded.stream().map(String::valueOf).collect(Collectors.joining("|"));
      } else {
        // single-choice grid row. Still accept comma or | but pick first matched
        List<String> tokens = tokenizeMultiValues(value);
        for (String token : tokens) {
          Integer idx = columnIndexByText.get(token.trim().toLowerCase());
          if (idx != null)
            return String.valueOf(idx);
        }
        throw new IllegalArgumentException("Đáp án '" + value + "' không tồn tại!");
      }
    }

    @Override
    public String getQuestionTitle() {
      return questionTitle;
    }

    @Override
    public String getGridRowTitle() {
      return rowTitle;
    }

    private List<String> tokenizeMultiValues(String raw) {
      String input = raw.trim();
      if (input.contains("|")) {
        String[] parts = input.split("\\|", -1);
        List<String> list = new ArrayList<>();
        for (String p : parts)
          list.add(p.trim());
        return list;
      }
      String[] segs = input.split(",");
      List<String> tokens = new ArrayList<>();
      int i = 0;
      while (i < segs.length) {
        String bestMatch = null;
        int bestJ = i;
        StringBuilder sb = new StringBuilder();
        for (int j = i; j < segs.length; j++) {
          if (j > i)
            sb.append(",");
          sb.append(segs[j]);
          String candidate = sb.toString().trim().toLowerCase();
          if (columnIndexByText.containsKey(candidate)) {
            bestMatch = candidate;
            bestJ = j;
          }
        }
        if (bestMatch != null) {
          tokens.add(bestMatch);
          i = bestJ + 1;
        } else {
          tokens.add(segs[i].trim());
          i++;
        }
      }
      return tokens;
    }
  }

  private final GoogleSheetsService sheetsService;

  private final GoogleFormService formService;
  private final GoogleFormParser googleFormParser;

  private final FormRepository formRepository;

  @Override
  public EncodeResult encodeSheetData(EncodeDataRequest request) throws Exception {
    // 1) Validate and read sheet
    SheetAccessibilityInfo access =
        sheetsService.validateAndCheckAccessibility(request.getSheetLink());
    if (!access.isAccessible()) {
      throw new IllegalArgumentException("Link Google Sheet không hợp lệ hoặc không truy cập được");
    }
    List<String> headers = sheetsService.getSheetColumns(request.getSheetLink());
    List<Map<String, Object>> rows = sheetsService.getSheetData(request.getSheetLink());

    // 2) Load form questions
    String formUrl = resolveFormUrl(request);
    List<ExtractedQuestion> questions = formService.readGoogleForm(formUrl);
    if (questions == null || questions.isEmpty()) {
      throw new IllegalArgumentException(
          "Không thể đọc cấu trúc Google Form từ đường dẫn cung cấp");
    }

    // Build column -> encoder mapping
    Map<String, QuestionEncoder> columnEncoders = buildEncoders(headers, questions);

    // 3) Encode data into new workbook
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      XSSFSheet sheet = workbook.createSheet("Encoded");
      // header row: same as input
      Row headerRow = sheet.createRow(0);
      for (int i = 0; i < headers.size(); i++) {
        Cell c = headerRow.createCell(i);
        c.setCellValue(headers.get(i));
      }

      int r = 1;
      List<String> errorList = new ArrayList<>();
      List<RowError> rowErrors = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        Row outRow = sheet.createRow(r);
        RowError currentRowError = RowError.builder().rowNumber(r + 1).build();
        for (int cIdx = 0; cIdx < headers.size(); cIdx++) {
          String col = headers.get(cIdx);
          Object rawVal = row.getOrDefault(col, "");
          String val = rawVal == null ? "" : String.valueOf(rawVal).trim();
          String encoded = "";
          try {
            QuestionEncoder encoder = columnEncoders.get(col);
            if (encoder != null) {
              encoded = encoder.encode(val);
            } else {
              // not mapped to any question => keep original
              encoded = val; // text/date/time or unmapped
            }
          } catch (Exception ex) {
            QuestionEncoder encoder = columnEncoders.get(col);
            String questionTitle = encoder != null ? encoder.getQuestionTitle() : null;
            String gridRowTitle = encoder != null ? encoder.getGridRowTitle() : null;
            String msg = String.format("Lỗi mã hóa tại row %d, column '%s'%s%s: %s", r + 1, col,
                questionTitle != null ? ", Câu hỏi: '" + questionTitle + "'" : "",
                gridRowTitle != null ? ", Đáp án: '" + gridRowTitle + "'" : "", ex.getMessage());
            errorList.add(msg);
            currentRowError.getCellErrors()
                .add(com.dienform.tool.mahoa.service.model.EncodeResult.CellError.builder()
                    .columnName(col).questionTitle(questionTitle).gridRowTitle(gridRowTitle)
                    .providedValue(val).message(ex.getMessage()).build());
            encoded = val; // keep original so user can inspect
          }
          outRow.createCell(cIdx).setCellValue(encoded);
        }
        if (!currentRowError.getCellErrors().isEmpty()) {
          rowErrors.add(currentRowError);
        }
        r++;
      }

      if (!errorList.isEmpty()) {
        // Append an extra sheet with errors for user visibility
        XSSFSheet errSheet = workbook.createSheet("Errors");
        int i = 0;
        for (String err : errorList) {
          Row er = errSheet.createRow(i++);
          er.createCell(0).setCellValue(err);
        }
      }

      workbook.write(bos);
      return EncodeResult.builder().excelBytes(bos.toByteArray()).rowErrors(rowErrors).build();
    }
  }

  private String resolveFormUrl(EncodeDataRequest request) {
    if (request.getFormLink() != null && !request.getFormLink().trim().isEmpty()) {
      String link = request.getFormLink().trim();
      // normalize edit link to public view to ensure parser-friendly HTML
      if (link.contains("/forms/d/") && link.contains("/edit")) {
        try {
          return googleFormParser.convertToPublicUrl(link);
        } catch (Exception ignore) {
        }
      }
      return link;
    }
    UUID id = request.getFormId();
    Form form = formRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Form không tồn tại"));
    if (form.getEditLink() == null || form.getEditLink().isEmpty()) {
      throw new IllegalArgumentException("Form không có đường dẫn editLink");
    }
    String link = form.getEditLink();
    if (link.contains("/forms/d/") && link.contains("/edit")) {
      try {
        return googleFormParser.convertToPublicUrl(link);
      } catch (Exception ignore) {
      }
    }
    return link;
  }

  private Map<String, QuestionEncoder> buildEncoders(List<String> headers,
      List<ExtractedQuestion> questions) {
    Map<String, QuestionEncoder> map = new HashMap<>();

    // Normalize helper
    java.util.function.Function<String, String> norm =
        s -> s == null ? "" : s.replace("*", "").trim();

    // map by exact column title and by grid syntax: "Question [Row]"
    Map<String, ExtractedQuestion> titleToQuestion = new HashMap<>();
    for (ExtractedQuestion q : questions) {
      titleToQuestion.put(norm.apply(q.getTitle()), q);
    }

    for (String header : headers) {
      String hNorm = norm.apply(header);

      // Detect grid syntax: Title [Row]
      if (hNorm.matches(".*\\[.*\\]$")) {
        int lb = hNorm.lastIndexOf('[');
        int rb = hNorm.lastIndexOf(']');
        String qTitle = hNorm.substring(0, lb).trim();
        String rowTitle = hNorm.substring(lb + 1, rb).trim();
        ExtractedQuestion q = titleToQuestion.get(qTitle);
        if (q != null && isGrid(q)) {
          map.put(header, new GridEncoder(q, rowTitle));
          continue;
        }
      }

      ExtractedQuestion q = titleToQuestion.get(hNorm);
      if (q != null) {
        map.put(header, new SimpleEncoder(q));
      }
    }
    return map;
  }

  private boolean isGrid(ExtractedQuestion q) {
    String t = q.getType() == null ? "" : q.getType().toLowerCase();
    return t.contains("grid");
  }
}


