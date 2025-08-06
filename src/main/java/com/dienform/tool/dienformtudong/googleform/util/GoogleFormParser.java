package com.dienform.tool.dienformtudong.googleform.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for parsing Google Forms and extracting their structure
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleFormParser {

    /**
     * Represents an extracted question from a Google Form
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractedQuestion {
        private String title;
        private String description;
        private String type;
        private boolean required;
        private Integer position;
        @Builder.Default
        private List<ExtractedOption> options = new ArrayList<>();
        @Builder.Default
        private Map<String, String> additionalData = new HashMap<>();
    }

    /**
     * Represents an extracted option from a Google Form question
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractedOption {
        private String text;
        private String value;
        private Integer position;
        @Builder.Default
        private List<ExtractedOption> subOptions = new ArrayList<>();
        private boolean isRow;
    }

    private final RestTemplate restTemplate;

    /**
     * Parse a Google Form and extract its questions and options
     * 
     * @param formEditUrl The edit URL of the Google Form
     * @return List of extracted questions with their options
     * @throws IOException If there's an error parsing the form
     */
    public List<ExtractedQuestion> parseForm(String formEditUrl) throws IOException {
        // Convert edit URL to public URL
        String publicFormUrl = convertToPublicUrl(formEditUrl);

        // Fetch the form HTML content
        // Document document = Jsoup.connect(publicFormUrl).get();
        Document document = null;

        // Extract form structure
        // List<ExtractedQuestion> questions = extractQuestionsFromHtml(document);

        return null;
    }

    /**
     * Submit answers to a Google Form
     * 
     * @param formEditUrl The edit URL of the form
     * @param answers Map of question IDs to answer values
     * @return Response data from the submission
     */
    public String submitForm(String formEditUrl, Map<UUID, Object> answers) {
        try {
            // Convert edit URL to public URL
            String publicFormUrl = convertToPublicUrl(formEditUrl);

            // Fetch the form first to get form ID and other necessary parameters
            // Document document = Jsoup.connect(publicFormUrl).get();
            Document document = null;

            // Extract form submission URL and parameters
            String formId = extractFormId(document);
            String submitUrl = "https://docs.google.com/forms/d/e/" + formId + "/formResponse";

            // Prepare form submission data
            Map<String, Object> formData = prepareFormData(document, answers);

            // Set headers
            org.springframework.http.HttpHeaders headers =
                    new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
            headers.setOrigin("https://docs.google.com");
            // headers.setReferer(publicFormUrl);

            // Make the submission request
            org.springframework.http.HttpEntity<Map<String, Object>> request =
                    new org.springframework.http.HttpEntity<>(formData, headers);
            org.springframework.http.ResponseEntity<String> response =
                    restTemplate.postForEntity(submitUrl, request, String.class);

            return response.getBody();
        } catch (Exception e) {
            log.error("Error submitting form: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to submit Google Form", e);
        }
    }

    public List<ExtractedQuestion> extractQuestionsFromHtml(String htmlContent) {
        List<ExtractedQuestion> questions = new ArrayList<>();
        // Parse the HTML content using Jsoup
        Document document = Jsoup.parse(htmlContent);

        // Select all question headings based on class or unique identifiers
        Elements questionElements = document.select("[role=listitem]");

        int questionIndex = 0;
        for (Element questionElement : questionElements) {
            Elements questionTitleElement = questionElement.select("[role=heading]");
            String questionTitle = questionTitleElement.text().trim();
            if (org.springframework.util.ObjectUtils.isEmpty(questionTitle)
                    && org.springframework.util.ObjectUtils
                            .isEmpty(questionTitleElement.select("span strong").text())) {
                log.warn("Skipping empty question title");
                continue;
            }

            // Initialize ExtractedQuestion object with default type
            String type = detectQuestionType(questionElement);
            if (type == null) {
                log.warn("Could not detect question type for element: {}", questionElement);
                continue;
            }

            ExtractedQuestion question = new ExtractedQuestion();
            question.setTitle(questionTitle);
            question.setType(type);
            question.setRequired(isRequired(questionElement));
            question.setPosition(questionIndex++);
            question.setOptions(new ArrayList<>());

            // Extract options based on question type
            switch (type.toLowerCase()) {
                case "radio":
                    extractRadioOptions(questionElement, question);
                    break;
                case "checkbox":
                    extractCheckboxOptions(questionElement, question);
                    break;
                case "combobox":
                case "select":
                    extractComboboxOptions(questionElement, question);
                    break;
                case "multiple_choice_grid":
                    extractMultipleChoiceGridOptions(questionElement, question);
                    break;
                case "checkbox_grid":
                    extractCheckboxGridOptions(questionElement, question);
                    break;
                case "date":
                    extractDateOptions(questionElement, question);
                    break;
                case "time":
                    // Time questions don't have options, but might have constraints
                    break;
                default:
                    log.warn("Unsupported question type for options extraction: {}", type);
            }

            questions.add(question);
        }
        return questions;
    }

    /**
     * Extract questions from HTML content
     * 
     * @param htmlContent The HTML content of the Google Form
     * @return List of extracted questions
     */
    public List<ExtractedQuestion> e(String htmlContent) {
        try {
            log.info("Parsing HTML content to extract questions");
            Document document = Jsoup.parse(htmlContent);

            List<ExtractedQuestion> questions = new ArrayList<>();

            // Select all question containers
            Elements questionElements = document.select("div[role=listitem]");
            log.info("Found {} potential question elements", questionElements.size());

            int position = 0;
            for (Element questionElement : questionElements) {
                // Extract question title
                Element titleElement = questionElement
                        .selectFirst(".freebirdFormviewerComponentsQuestionBaseTitle");
                if (titleElement == null) {
                    log.debug("Skipping element without title");
                    continue;
                }

                String title = titleElement.text();
                position++;

                // Extract question description (if any)
                Element descElement = questionElement
                        .selectFirst(".freebirdFormviewerComponentsQuestionBaseDescription");
                String description = descElement != null ? descElement.text() : "";

                // Determine question type
                String type = determineQuestionType(questionElement);

                // Check if question is required
                boolean required = !questionElement
                        .select(".freebirdFormviewerComponentsQuestionBaseRequiredAsterisk")
                        .isEmpty();

                // Extract options for multiple choice, checkbox, or dropdown questions
                List<ExtractedOption> options = extractOptions(questionElement, type);

                // Create and add the extracted question
                ExtractedQuestion question = new ExtractedQuestion();
                question.setTitle(title);
                question.setDescription(description);
                question.setType(type);
                question.setRequired(required);
                question.setPosition(position);
                question.setOptions(options);

                questions.add(question);
                log.debug("Added question: {}", question.getTitle());
            }

            log.info("Successfully extracted {} questions", questions.size());
            return questions;
        } catch (Exception e) {
            log.error("Error extracting questions from HTML content: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Convert a Google Form edit URL to its public URL
     * 
     * @param editUrl The edit URL
     * @return The public view URL
     */
    public String convertToPublicUrl(String editUrl) {
        // Example edit URL: https://docs.google.com/forms/d/{form-id}/edit
        // Public URL: https://docs.google.com/forms/d/e/{form-id}/viewform

        String formId = extractFormIdFromEditUrl(editUrl);
        return "https://docs.google.com/forms/d/e/" + formId + "/viewform";
    }

    private void extractRadioOptions(Element questionElement, ExtractedQuestion question) {
        Elements optionElements = questionElement.select("[role=radio]");
        int optionIndex = 0;
        for (Element optionElement : optionElements) {
            String optionText = optionElement.attr("data-value").trim();
            if (org.springframework.util.ObjectUtils.isEmpty(optionText)) {
                log.warn("Skipping empty option radio text");
                continue;
            }

            ExtractedOption option = new ExtractedOption();
            option.setText(optionText);
            option.setValue(optionText);
            option.setPosition(optionIndex++);
            question.getOptions().add(option);
        }
    }

    private void extractCheckboxOptions(Element questionElement, ExtractedQuestion question) {
        Elements optionElements = questionElement.select("[role=checkbox]");
        int optionIndex = 0;
        for (Element optionElement : optionElements) {
            String optionText = optionElement.attr("data-answer-value").trim();
            if (org.springframework.util.ObjectUtils.isEmpty(optionText)) {
                log.warn("Skipping empty option checkbox text");
                continue;
            }

            ExtractedOption option = new ExtractedOption();
            option.setText(optionText);
            option.setValue(optionText);
            option.setPosition(optionIndex++);
            question.getOptions().add(option);
        }
    }

    private void extractComboboxOptions(Element questionElement, ExtractedQuestion question) {
        Elements optionElements = questionElement.select("[role=option]");
        int optionIndex = 0;
        for (Element optionElement : optionElements) {
            String optionText = optionElement.attr("data-value").trim();
            if (org.springframework.util.ObjectUtils.isEmpty(optionText)) {
                log.warn("Skipping empty option combobox text");
                continue;
            }

            ExtractedOption option = new ExtractedOption();
            option.setText(optionText);
            option.setValue(optionText);
            option.setPosition(optionIndex++);
            question.getOptions().add(option);
        }
    }

    private void extractMultipleChoiceGridOptions(Element questionElement,
            ExtractedQuestion question) {
        try {
            // Get data-params attribute
            Element dataParamsElement = questionElement.select("[data-params]").first();
            if (dataParamsElement == null) {
                log.warn("No data-params found for multiple choice grid question");
                return;
            }

            String dataParams = dataParamsElement.attr("data-params");
            if (dataParams.isEmpty()) {
                log.warn("Empty data-params for multiple choice grid question");
                return;
            }

            // Preprocess the data-params string
            dataParams = DataProcessingUtils.preprocessDataParams(dataParams);

            // Try to parse with lenient mode
            JsonArray outer = null;
            try {
                JsonReader reader = new JsonReader(new java.io.StringReader(dataParams));
                reader.setLenient(true);
                outer = JsonParser.parseReader(reader).getAsJsonArray();
            } catch (Exception e) {
                log.warn("Failed to parse data-params as JSON, trying alternative parsing: {}",
                        e.getMessage());
                // Fallback: try to extract rows and columns from HTML structure
                extractGridFromHtmlStructure(questionElement, question);
                return;
            }

            // Safely access array elements
            if (outer == null || outer.size() < 5) {
                log.warn("Invalid data-params structure, size: {}",
                        outer != null ? outer.size() : "null");
                extractGridFromHtmlStructure(questionElement, question);
                return;
            }

            JsonArray rows = null;
            try {
                rows = outer.get(4).getAsJsonArray();
            } catch (Exception e) {
                log.warn("Failed to get rows from position 4: {}", e.getMessage());
                extractGridFromHtmlStructure(questionElement, question);
                return;
            }

            List<ExtractedOption> rowOptions = new ArrayList<>();
            List<String> allColumns = new ArrayList<>();

            // First pass: collect all unique columns from all rows
            for (int i = 0; i < rows.size(); i++) {
                try {
                    JsonArray row = rows.get(i).getAsJsonArray();
                    if (row.size() < 5)
                        continue;
                    JsonArray colArr = row.get(1).getAsJsonArray();
                    for (int j = 0; j < colArr.size(); j++) {
                        try {
                            JsonArray colElement = colArr.get(j).getAsJsonArray();
                            if (colElement.size() > 0) {
                                String colText = colElement.get(0).getAsString();
                                if (!allColumns.contains(colText)) {
                                    allColumns.add(colText);
                                    log.debug("Found column: {}", colText);
                                }
                            }
                        } catch (Exception e) {
                        }
                    }
                } catch (Exception e) {
                }
            }

            // Second pass: build rows with subOptions
            for (int i = 0; i < rows.size(); i++) {
                try {
                    JsonArray row = rows.get(i).getAsJsonArray();
                    if (row.size() < 5)
                        continue;
                    String rowId = row.get(0).getAsString();

                    String rowTitle = "";
                    try {
                        JsonArray titleArray = row.get(4).getAsJsonArray();
                        if (titleArray.size() > 0) {
                            rowTitle = titleArray.get(0).getAsString();
                            log.debug("Found row: {}", rowTitle);
                        }
                    } catch (Exception e) {
                        rowTitle = "";
                    }

                    if (rowTitle != null && !rowTitle.trim().isEmpty()
                            && !rowTitle.matches("Row \\d+")) {
                        ExtractedOption rowOption = new ExtractedOption();
                        rowOption.setText(rowTitle);
                        rowOption.setValue(rowId);
                        rowOption.setPosition(i);
                        rowOption.setRow(true); // This is a row option
                        rowOption.setSubOptions(allColumns.stream().map(opt -> {
                            ExtractedOption subOpt = new ExtractedOption();
                            subOpt.setText(opt);
                            subOpt.setValue(opt);
                            subOpt.setRow(false); // These are column subOptions
                            return subOpt;
                        }).collect(Collectors.toList()));
                        rowOptions.add(rowOption);
                        log.info(
                                "=== JSON PARSING: Created row option: '{}' with isRow={} and {} subOptions",
                                rowTitle, rowOption.isRow(), allColumns.size());
                        for (ExtractedOption subOpt : rowOption.getSubOptions()) {
                            log.info("  - SubOption: '{}' with isRow={}", subOpt.getText(),
                                    subOpt.isRow());
                        }
                    }
                } catch (Exception e) {
                }
            }

            if (!rowOptions.isEmpty()) {
                question.setOptions(rowOptions);
                Map<String, String> additionalData = new HashMap<>();
                additionalData.put("rowCount", String.valueOf(rowOptions.size()));
                additionalData.put("columnCount", String.valueOf(allColumns.size()));
                additionalData.put("dataParams", dataParams);
                additionalData.put("parsingMethod", "json");
                question.setAdditionalData(additionalData);
                log.info("Successfully parsed {} rows with {} columns from JSON", rowOptions.size(),
                        allColumns.size());
            } else {
                log.warn("No valid rows extracted from JSON, falling back to HTML structure");
                extractGridFromHtmlStructure(questionElement, question);
            }

        } catch (Exception e) {
            log.error("Error extracting multiple choice grid options: {}", e.getMessage());
            // Fallback to HTML structure
            extractGridFromHtmlStructure(questionElement, question);
        }
    }

    private void extractGridFromHtmlStructure(Element questionElement, ExtractedQuestion question) {
        try {
            // Get all radio groups
            Elements radioGroups = questionElement.select("[role=radiogroup]");
            List<String> columnLabels = new ArrayList<>();

            // Only get columns from the first group to avoid duplicates
            if (!radioGroups.isEmpty()) {
                Element firstGroup = radioGroups.first();
                Elements radios = firstGroup.select("[role=radio][data-value]");
                for (Element radio : radios) {
                    String colText = radio.attr("data-value").trim();
                    if (!colText.isEmpty() && !columnLabels.contains(colText)) {
                        columnLabels.add(colText);
                        log.debug("Found column from HTML: {}", colText);
                    }
                }
            }

            // If still empty, try to get from any [data-value] under questionElement
            if (columnLabels.isEmpty()) {
                Elements allDataValue = questionElement.select("[data-value]");
                for (Element col : allDataValue) {
                    String colText = col.attr("data-value").trim();
                    if (!colText.isEmpty() && !columnLabels.contains(colText)) {
                        columnLabels.add(colText);
                        log.debug("Found column from fallback: {}", colText);
                    }
                }
            }

            // Get rows from [role=radiogroup][aria-label]
            List<ExtractedOption> rowOptions = new ArrayList<>();
            int rowIndex = 0;
            for (Element group : radioGroups) {
                String rowTitle = group.attr("aria-label").trim();
                if (!rowTitle.isEmpty() && !rowTitle.matches("Row \\d+")) {
                    log.debug("Found row from HTML: {}", rowTitle);
                    ExtractedOption rowOption = new ExtractedOption();
                    rowOption.setText(rowTitle);
                    rowOption.setValue("row_" + rowIndex);
                    rowOption.setPosition(rowIndex);
                    rowOption.setRow(true); // This is a
                                            // row option
                    rowOption.setSubOptions(columnLabels.stream().map(opt -> {
                        ExtractedOption subOpt = new ExtractedOption();
                        subOpt.setText(opt);
                        subOpt.setValue(opt);
                        subOpt.setRow(false); // These are column subOptions
                        return subOpt;
                    }).collect(Collectors.toList()));
                    rowOptions.add(rowOption);
                    log.info(
                            "=== HTML PARSING: Created row option: '{}' with isRow={} and {} subOptions",
                            rowTitle, rowOption.isRow(), columnLabels.size());
                    for (ExtractedOption subOpt : rowOption.getSubOptions()) {
                        log.info("  - SubOption: '{}' with isRow={}", subOpt.getText(),
                                subOpt.isRow());
                    }
                    rowIndex++;
                }
            }

            if (!rowOptions.isEmpty()) {
                question.setOptions(rowOptions);
                Map<String, String> additionalData = new HashMap<>();
                additionalData.put("rowCount", String.valueOf(rowOptions.size()));
                additionalData.put("columnCount", String.valueOf(columnLabels.size()));
                additionalData.put("parsingMethod", "html_structure");
                question.setAdditionalData(additionalData);
                log.info("Successfully parsed {} rows with {} columns from HTML", rowOptions.size(),
                        columnLabels.size());
            } else {
                log.warn("No rows or columns found in HTML structure");
            }
        } catch (Exception e) {
            log.error("Error extracting grid from HTML structure: {}", e.getMessage());
        }
    }

    private void extractCheckboxGridOptions(Element questionElement, ExtractedQuestion question) {
        try {
            // Extract data-params which contains the grid structure
            Element paramsElement = questionElement.select("[data-params]").first();
            String dataParams = paramsElement != null ? paramsElement.attr("data-params") : "";

            boolean parsed = false;
            if (dataParams != null && !dataParams.isEmpty()) {
                try {
                    String cleanParams = DataProcessingUtils.preprocessDataParams(dataParams);
                    JsonReader reader = new JsonReader(new java.io.StringReader(cleanParams));
                    reader.setLenient(true);
                    JsonArray outer = JsonParser.parseReader(reader).getAsJsonArray();
                    if (outer != null && outer.size() >= 5) {
                        JsonArray rows = outer.get(4).getAsJsonArray();
                        List<String> columnLabels = new ArrayList<>();
                        // Extract columns from the first row that has columns (row[1])
                        for (int i = 0; i < rows.size(); i++) {
                            JsonArray row = rows.get(i).getAsJsonArray();
                            if (row.size() > 1 && columnLabels.isEmpty()) {
                                JsonArray colArr = row.get(1).getAsJsonArray();
                                for (int j = 0; j < colArr.size(); j++) {
                                    JsonArray colElement = colArr.get(j).getAsJsonArray();
                                    if (colElement.size() > 0) {
                                        String colText = colElement.get(0).getAsString();
                                        columnLabels.add(colText);
                                    }
                                }
                            }
                        }
                        // Build row options (each row is a QuestionOption with subOptions as
                        // columns)
                        List<ExtractedOption> rowOptions = new ArrayList<>();
                        for (int i = 0; i < rows.size(); i++) {
                            JsonArray row = rows.get(i).getAsJsonArray();
                            String rowLabel = null;
                            // Ưu tiên lấy row label từ row[3][0]
                            if (row.size() > 3 && row.get(3).isJsonArray()) {
                                JsonArray rowLabelArr = row.get(3).getAsJsonArray();
                                if (rowLabelArr.size() > 0 && rowLabelArr.get(0).isJsonPrimitive()
                                        && rowLabelArr.get(0).getAsJsonPrimitive().isString()) {
                                    rowLabel = rowLabelArr.get(0).getAsString();
                                }
                            }
                            // Nếu không có, thử lấy từ row[4][0]
                            if ((rowLabel == null || rowLabel.trim().isEmpty()) && row.size() > 4
                                    && row.get(4).isJsonArray()) {
                                JsonArray rowLabelArr = row.get(4).getAsJsonArray();
                                if (rowLabelArr.size() > 0 && rowLabelArr.get(0).isJsonPrimitive()
                                        && rowLabelArr.get(0).getAsJsonPrimitive().isString()) {
                                    rowLabel = rowLabelArr.get(0).getAsString();
                                }
                            }
                            if (rowLabel == null || rowLabel.trim().isEmpty()) {
                                rowLabel = "row_" + (i + 1);
                            }
                            // Build subOptions (columns) with isRow=false
                            List<ExtractedOption> subOptions = columnLabels.stream().map(opt -> {
                                ExtractedOption subOpt = new ExtractedOption();
                                subOpt.setText(opt);
                                subOpt.setValue(opt);
                                subOpt.setPosition(columnLabels.indexOf(opt));
                                subOpt.setRow(false);
                                return subOpt;
                            }).collect(java.util.stream.Collectors.toList());
                            // Build row option with isRow=true, value=row_1, row_2, ...
                            ExtractedOption rowOption = new ExtractedOption();
                            rowOption.setText(rowLabel);
                            rowOption.setValue("row_" + (i + 1));
                            rowOption.setPosition(i);
                            rowOption.setRow(true);
                            rowOption.setSubOptions(subOptions);
                            rowOptions.add(rowOption);
                        }
                        if (!rowOptions.isEmpty()) {
                            question.setOptions(rowOptions);
                            Map<String, String> additionalData = new HashMap<>();
                            additionalData.put("rowCount", String.valueOf(rowOptions.size()));
                            additionalData.put("columnCount", String.valueOf(columnLabels.size()));
                            additionalData.put("dataParams", dataParams);
                            additionalData.put("parsingMethod", "json");
                            question.setAdditionalData(additionalData);
                            parsed = true;
                        }
                    }
                } catch (Exception e) {
                    // fallback below
                }
            }
            if (!parsed) {
                // fallback: HTML structure logic KHÔNG dùng class động
                Elements rowGroups = questionElement.select("div[role=group]");
                List<String> columnLabels = new ArrayList<>();

                // Lấy column label từ checkbox đầu tiên của group đầu tiên
                if (!rowGroups.isEmpty()) {
                    Element firstGroup = rowGroups.first();
                    Elements checkboxes =
                            firstGroup.select("div[role=checkbox][data-answer-value]");
                    for (Element checkbox : checkboxes) {
                        String colLabel = checkbox.attr("data-answer-value").trim();
                        if (!colLabel.isEmpty())
                            columnLabels.add(colLabel);
                    }
                }

                int rowIndex = 0;
                for (Element rowGroup : rowGroups) {
                    // Row label: lấy text của phần tử con đầu tiên không phải <label>
                    String rowLabel = "";
                    for (Element child : rowGroup.children()) {
                        if (!child.tagName().equals("label")) {
                            rowLabel = child.text().trim();
                            if (!rowLabel.isEmpty())
                                break;
                        }
                    }
                    // Nếu không có, thử lấy aria-label
                    if (rowLabel.isEmpty()) {
                        rowLabel =
                                rowGroup.hasAttr("aria-label") ? rowGroup.attr("aria-label").trim()
                                        : "row_" + (rowIndex + 1);
                    }

                    ExtractedOption rowOption = new ExtractedOption();
                    rowOption.setText(rowLabel);
                    rowOption.setValue(rowLabel);
                    rowOption.setPosition(rowIndex);
                    rowOption.setRow(true);
                    rowOption.setSubOptions(new ArrayList<>());

                    // Thêm column cho row
                    for (int i = 0; i < columnLabels.size(); i++) {
                        ExtractedOption colOption = new ExtractedOption();
                        colOption.setText(columnLabels.get(i));
                        colOption.setValue(columnLabels.get(i));
                        colOption.setPosition(i);
                        colOption.setRow(false);
                        rowOption.getSubOptions().add(colOption);
                    }
                    question.getOptions().add(rowOption);
                    rowIndex++;
                }

                Map<String, String> additionalData = new HashMap<>();
                additionalData.put("rowCount", String.valueOf(rowGroups.size()));
                additionalData.put("columnCount", String.valueOf(columnLabels.size()));
                additionalData.put("parsingMethod", "html_structure_no_class");
                question.setAdditionalData(additionalData);
            }
        } catch (Exception e) {
            log.error("Error extracting checkbox grid options (no class): {}", e.getMessage());
        }
    }

    private Map<String, Object> parseDataParams(String dataParams) {
        Map<String, Object> result = new HashMap<>();
        try {
            // Remove the %.@. prefix if present
            dataParams = dataParams.replaceFirst("^%\\.@\\.", "");

            // Parse the JSON-like structure
            // This is a simplified parser - you might need a more robust solution
            Pattern pattern = Pattern.compile("\\[(.*?)\\]");
            Matcher matcher = pattern.matcher(dataParams);

            while (matcher.find()) {
                String match = matcher.group(1);
                if (match.contains("data-field-id")) {
                    result.put("fieldId", match);
                }
                // Add more parsing logic as needed
            }
        } catch (Exception e) {
            log.error("Error parsing data-params: {}", e.getMessage());
        }
        return result;
    }

    private void extractDateOptions(Element questionElement, ExtractedQuestion question) {
        try {
            Map<String, String> additionalData = new HashMap<>();

            // Get date input element
            Element dateInput = questionElement.select("input[type=date]").first();
            if (dateInput != null) {
                // Extract date constraints
                String minDate = dateInput.attr("min");
                String maxDate = dateInput.attr("max");
                String pattern = dateInput.attr("pattern");

                if (!minDate.isEmpty()) {
                    additionalData.put("minDate", minDate);
                }
                if (!maxDate.isEmpty()) {
                    additionalData.put("maxDate", maxDate);
                }
                if (!pattern.isEmpty()) {
                    additionalData.put("pattern", pattern);
                }

                // Check if date is required
                boolean required = dateInput.hasAttr("required");
                additionalData.put("required", String.valueOf(required));
            }

            // Get data-params for additional configuration
            String dataParams = questionElement.select("[data-params]").attr("data-params");
            if (!dataParams.isEmpty()) {
                additionalData.put("dataParams", dataParams);
            }

            question.setAdditionalData(additionalData);
        } catch (Exception e) {
            log.error("Error extracting date options: {}", e.getMessage());
        }
    }

    /**
     * Extract form ID from an edit URL
     * 
     * @param editUrl The edit URL
     * @return The form ID
     */
    private String extractFormIdFromEditUrl(String editUrl) {
        Pattern pattern = Pattern.compile("https://docs\\.google\\.com/forms/d/([\\w-]+)");
        Matcher matcher = pattern.matcher(editUrl);

        if (matcher.find()) {
            return matcher.group(1);
        }

        throw new IllegalArgumentException("Invalid Google Form edit URL format");
    }

    /**
     * Extract form ID from the form document
     * 
     * @param document The parsed HTML document
     * @return The form ID
     */
    private String extractFormId(Document document) {
        // Look for the form ID in meta tags or script elements
        // Elements scripts = document.select("script");

        // for (Element script : scripts) {
        // String scriptContent = script.html();
        // Pattern pattern = Pattern.compile("'?\\[\\[\\"?(.*?)\\\"?,");
        // Matcher matcher = pattern.matcher(scriptContent);

        // if (matcher.find()) {
        // return matcher.group(1);
        // }
        // }

        throw new IllegalArgumentException("Could not extract form ID from the document");
    }

    // Method to dynamically detect the question type
    private String detectQuestionType(Element questionElement) {
        // Get data-params to check question type
        String dataParams = questionElement.select("[data-params]").attr("data-params");

        // Kiểm tra số lượng radiogroup
        int radioGroupCount = questionElement.select("[role=radiogroup]").size();
        // Kiểm tra số lượng data-field-index khác nhau trong các radio
        java.util.Set<String> dataFieldIndexes = new java.util.HashSet<>();
        org.jsoup.select.Elements radios = questionElement.select("[role=radio]");
        for (org.jsoup.nodes.Element radio : radios) {
            String idx = radio.hasAttr("data-field-index") ? radio.attr("data-field-index") : null;
            if (idx != null && !idx.isEmpty()) {
                dataFieldIndexes.add(idx);
            }
        }

        // Nếu có nhiều radiogroup hoặc nhiều data-field-index khác nhau => multiple_choice_grid
        if (radioGroupCount > 1 || dataFieldIndexes.size() > 1) {
            return "multiple_choice_grid";
        }

        // Check for checkbox grid by data structure
        if (dataParams.contains("checkbox_grid")
                || (questionElement.select("[data-field-id]").size() > 0
                        && questionElement.select("[role=group]").size() > 1)) {
            return "checkbox_grid";
        }

        // Check for date input
        if (questionElement.select("input[type=date]").size() > 0
                || dataParams.contains("\"date\"")) {
            return "date";
        }

        // Nếu chỉ có 1 radiogroup và không có đặc điểm của grid => radio
        if (radioGroupCount == 1) {
            return "radio";
        }

        // Check for checkbox
        if (questionElement.select("[role=checkbox]").size() > 0) {
            return "checkbox";
        }

        // Check for text input
        if (questionElement.select(
                "textarea[aria-label], input[type=text], input[type=email], input[type=number]")
                .size() > 0) {
            return "text";
        }

        // Check for select dropdown
        if (questionElement.select("select, [role=listbox]").size() > 0) {
            return "select";
        }

        // Default to null if no type is detected
        return null;
    }

    // Method to check if the question is required
    private boolean isRequired(Element questionElement) {
        // Check if the question is marked as required (i.e., contains asterisks or
        // aria-required="true")
        return questionElement.select(".vnumgf").text().contains("*")
                || questionElement.hasAttr("aria-required");
    }

    /**
     * Determine the type of a question based on its HTML structure
     * 
     * @param questionElement The question element
     * @return The question type
     */
    private String determineQuestionType(Element questionElement) {
        if (!questionElement.select(".freebirdFormviewerComponentsQuestionRadioRoot").isEmpty()) {
            return "Multiple Choice";
        } else if (!questionElement.select(".freebirdFormviewerComponentsQuestionCheckboxRoot")
                .isEmpty()) {
            return "Checkboxes";
        } else if (!questionElement.select(".freebirdFormviewerComponentsQuestionSelectRoot")
                .isEmpty()) {
            return "Dropdown";
        } else if (!questionElement.select(".freebirdFormviewerComponentsQuestionTextRoot")
                .isEmpty()) {
            if (!questionElement.select("textarea").isEmpty()) {
                return "Paragraph";
            } else {
                return "Short Answer";
            }
        } else if (!questionElement.select(".freebirdFormviewerComponentsQuestionScaleRoot")
                .isEmpty()) {
            return "Linear Scale";
        } else if (!questionElement.select(".freebirdFormviewerComponentsQuestionDateRoot")
                .isEmpty()) {
            return "Date";
        } else if (!questionElement.select(".freebirdFormviewerComponentsQuestionTimeRoot")
                .isEmpty()) {
            return "Time";
        }

        return "Other";
    }

    /**
     * Extract options from multiple choice, checkbox, or dropdown questions
     * 
     * @param questionElement The question element
     * @param type The question type
     * @return List of extracted options
     */
    private List<ExtractedOption> extractOptions(Element questionElement, String type) {
        List<ExtractedOption> options = new ArrayList<>();

        if ("Multiple Choice".equals(type)) {
            org.jsoup.select.Elements optionElements =
                    questionElement.select(".freebirdFormviewerComponentsQuestionRadioChoice");

            for (org.jsoup.nodes.Element optionElement : optionElements) {
                org.jsoup.nodes.Element optionTextElement =
                        optionElement.selectFirst(".exportLabel");
                if (optionTextElement != null) {
                    String optionText = optionTextElement.text();
                    String optionValue = extractOptionValue(optionElement);

                    ExtractedOption option = new ExtractedOption();
                    option.setText(optionText);
                    option.setValue(optionValue);
                    options.add(option);
                }
            }
        } else if ("Checkboxes".equals(type)) {
            org.jsoup.select.Elements optionElements =
                    questionElement.select(".freebirdFormviewerComponentsQuestionCheckboxChoice");

            for (org.jsoup.nodes.Element optionElement : optionElements) {
                org.jsoup.nodes.Element optionTextElement =
                        optionElement.selectFirst(".exportLabel");
                if (optionTextElement != null) {
                    String optionText = optionTextElement.text();
                    String optionValue = extractOptionValue(optionElement);

                    ExtractedOption option = new ExtractedOption();
                    option.setText(optionText);
                    option.setValue(optionValue);
                    options.add(option);
                }
            }
        } else if ("Dropdown".equals(type)) {
            org.jsoup.select.Elements optionElements = questionElement.select("option");

            for (org.jsoup.nodes.Element optionElement : optionElements) {
                String optionText = optionElement.text().trim();
                String optionValue = optionElement.attr("value");

                // Skip the placeholder option
                if (!optionText.isEmpty() && !optionValue.isEmpty()) {
                    ExtractedOption option = new ExtractedOption();
                    option.setText(optionText);
                    option.setValue(optionValue);
                    options.add(option);
                }
            }
        }

        return options;
    }

    /**
     * Extract the value of an option from its HTML element
     * 
     * @param optionElement The option element
     * @return The option value
     */
    private String extractOptionValue(Element optionElement) {
        // In many cases, the value is stored in a data attribute
        String value = optionElement.attr("data-value");

        // If not found, try to extract from an input element
        if (value.isEmpty()) {
            org.jsoup.nodes.Element inputElement = optionElement.selectFirst("input");
            if (inputElement != null) {
                value = inputElement.attr("value");
            }
        }

        // If still not found, use the text as a fallback
        if (value.isEmpty()) {
            org.jsoup.nodes.Element textElement = optionElement.selectFirst(".exportLabel");
            if (textElement != null) {
                value = textElement.text();
            }
        }

        return value.isEmpty() ? "unknown" : value;
    }

    /**
     * Prepare form data for submission
     * 
     * @param document The form document
     * @param answers Map of question IDs to answer values
     * @return Map of form field names to values
     */
    private Map<String, Object> prepareFormData(Document document, Map<UUID, Object> answers) {
        Map<String, Object> formData = new HashMap<>();

        // Extract the mapping between our question IDs and Google Form field names
        Map<UUID, String> questionIdToFieldNameMap = extractQuestionIdToFieldNameMap(document);

        // Map our answers to Google Form field names
        for (Map.Entry<UUID, Object> entry : answers.entrySet()) {
            UUID questionId = entry.getKey();
            Object answerValue = entry.getValue();

            String fieldName = questionIdToFieldNameMap.get(questionId);
            if (fieldName != null) {
                formData.put(fieldName, answerValue);
            }
        }

        // Add any additional required form parameters
        formData.put("pageHistory", "0");
        formData.put("fbzx", extractFbzxToken(document));

        return formData;
    }

    /**
     * Extract the mapping between our question IDs and Google Form field names
     *
     * @param document The form document
     * @return Map of question IDs to field names
     */
    private Map<UUID, String> extractQuestionIdToFieldNameMap(Document document) {
        // This is a placeholder implementation
        // In a real implementation, you would need to store this mapping when extracting questions
        // For now, we'll return an empty map
        return new HashMap<>();
    }

    /**
     * Extract the fbzx token from the form document
     * 
     * @param document The form document
     * @return The fbzx token
     */
    private String extractFbzxToken(Document document) {
        // Elements inputElements = document.select("input[name=fbzx]");

        // if (!inputElements.isEmpty()) {
        // return inputElements.first().attr("value");
        // }

        return "";
    }
}

