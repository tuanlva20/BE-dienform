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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.RestTemplate;
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
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setOrigin("https://docs.google.com");
            // headers.setReferer(publicFormUrl);

            // Make the submission request
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(formData, headers);
            ResponseEntity<String> response =
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
            if (ObjectUtils.isEmpty(questionTitle)
                    && ObjectUtils.isEmpty(questionTitleElement.select("span strong").text())) {
                log.warn("Skipping empty question title");
                continue;
            }

            // Initialize ExtractedQuestion object with default type
            String type = detectQuestionType(questionElement);
            if (type == null) {
                log.warn("Could not detect question type for element: {}", questionElement);
                continue;
            }

            ExtractedQuestion question = ExtractedQuestion.builder().title(questionTitle).type(type)
                    .required(isRequired(questionElement)).position(questionIndex++)
                    .options(new ArrayList<>()).build();

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
                ExtractedQuestion question =
                        ExtractedQuestion.builder().title(title).description(description).type(type)
                                .required(required).position(position).options(options).build();

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
            if (ObjectUtils.isEmpty(optionText)) {
                log.warn("Skipping empty option radio text");
                continue;
            }

            ExtractedOption option = ExtractedOption.builder().text(optionText).value(optionText)
                    .position(optionIndex++).build();
            question.getOptions().add(option);
        }
    }

    private void extractCheckboxOptions(Element questionElement, ExtractedQuestion question) {
        Elements optionElements = questionElement.select("[role=checkbox]");
        int optionIndex = 0;
        for (Element optionElement : optionElements) {
            String optionText = optionElement.attr("data-answer-value").trim();
            if (ObjectUtils.isEmpty(optionText)) {
                log.warn("Skipping empty option checkbox text");
                continue;
            }

            ExtractedOption option = ExtractedOption.builder().text(optionText).value(optionText)
                    .position(optionIndex++).build();
            question.getOptions().add(option);
        }
    }

    private void extractComboboxOptions(Element questionElement, ExtractedQuestion question) {
        Elements optionElements = questionElement.select("[role=option]");
        int optionIndex = 0;
        for (Element optionElement : optionElements) {
            String optionText = optionElement.attr("data-value").trim();
            if (ObjectUtils.isEmpty(optionText)) {
                log.warn("Skipping empty option combobox text");
                continue;
            }

            ExtractedOption option = ExtractedOption.builder().text(optionText).value(optionText)
                    .position(optionIndex++).build();
            question.getOptions().add(option);
        }
    }

    private void extractMultipleChoiceGridOptions(Element questionElement,
            ExtractedQuestion question) {
        // Extract row titles
        Elements rowElements = questionElement.select(".wzWPxe.OIC90c");
        for (Element rowElement : rowElements) {
            String rowTitle = rowElement.text().trim();
            if (ObjectUtils.isEmpty(rowTitle))
                continue;

            // For each row, find its radio options
            Element radioGroup = rowElement.parent().select("[role=radiogroup]").first();
            if (radioGroup != null) {
                Elements optionElements = radioGroup.select("[role=radio]");
                for (Element optionElement : optionElements) {
                    String optionText = optionElement.attr("data-value").trim();
                    if (!ObjectUtils.isEmpty(optionText)) {
                        // Format: "row:option"
                        ExtractedOption option =
                                ExtractedOption.builder().text(rowTitle + ":" + optionText)
                                        .value(rowTitle + ":" + optionText)
                                        .position(question.getOptions().size()).build();
                        question.getOptions().add(option);
                    }
                }
            }
        }
    }

    private void extractCheckboxGridOptions(Element questionElement, ExtractedQuestion question) {
        try {
            // Extract data-params which contains the grid structure
            Element paramsElement = questionElement.select("[data-params]").first();
            String dataParams = paramsElement != null ? paramsElement.attr("data-params") : "";

            // Parse the data-params to get field IDs and structure
            Map<String, Object> gridData = parseDataParams(dataParams);

            // Get all groups (rows) that contain checkboxes
            Elements rowGroups = questionElement.select("[role=group]");
            List<String> rowLabels = new ArrayList<>();
            List<String> columnLabels = new ArrayList<>();

            // First, extract column labels from the first visible checkbox group
            Element firstGroup = rowGroups.first();
            if (firstGroup != null) {
                // Find all checkbox elements to get their values
                Elements checkboxes = firstGroup.select("[role=checkbox]");
                for (Element checkbox : checkboxes) {
                    String value = checkbox.attr("data-answer-value");
                    if (!value.isEmpty()) {
                        columnLabels.add(value);
                    }
                }
            }

            // Now process each row
            for (Element rowGroup : rowGroups) {
                // Get row title from the preceding text element
                String rowTitle = "";
                Element rowTitleElement = rowGroup.previousElementSibling();
                if (rowTitleElement != null) {
                    rowTitle = rowTitleElement.text().trim();
                }

                if (!rowTitle.isEmpty()) {
                    rowLabels.add(rowTitle);

                    // Get the field ID for this row
                    String fieldId = rowGroup.select("[data-field-id]").attr("data-field-id");

                    ExtractedOption rowOption = ExtractedOption.builder().text(rowTitle)
                            .value(fieldId).position(rowLabels.size() - 1)
                            .subOptions(new ArrayList<>()).build();

                    // Add column options
                    for (int i = 0; i < columnLabels.size(); i++) {
                        ExtractedOption columnOption =
                                ExtractedOption.builder().text(columnLabels.get(i))
                                        .value(columnLabels.get(i)).position(i).build();
                        rowOption.getSubOptions().add(columnOption);
                    }

                    question.getOptions().add(rowOption);
                }
            }

            // Store grid metadata
            Map<String, String> additionalData = new HashMap<>();
            additionalData.put("rowCount", String.valueOf(rowLabels.size()));
            additionalData.put("columnCount", String.valueOf(columnLabels.size()));
            additionalData.put("dataParams", dataParams);
            question.setAdditionalData(additionalData);

        } catch (Exception e) {
            log.error("Error extracting checkbox grid options: {}", e.getMessage());
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

        // Check for radio button (multiple choice)
        if (questionElement.select("[role=radio]").size() > 0) {
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
            Elements optionElements =
                    questionElement.select(".freebirdFormviewerComponentsQuestionRadioChoice");

            for (Element optionElement : optionElements) {
                Element optionTextElement = optionElement.selectFirst(".exportLabel");
                if (optionTextElement != null) {
                    String optionText = optionTextElement.text();
                    String optionValue = extractOptionValue(optionElement);

                    options.add(
                            ExtractedOption.builder().text(optionText).value(optionValue).build());
                }
            }
        } else if ("Checkboxes".equals(type)) {
            Elements optionElements =
                    questionElement.select(".freebirdFormviewerComponentsQuestionCheckboxChoice");

            for (Element optionElement : optionElements) {
                Element optionTextElement = optionElement.selectFirst(".exportLabel");
                if (optionTextElement != null) {
                    String optionText = optionTextElement.text();
                    String optionValue = extractOptionValue(optionElement);

                    options.add(
                            ExtractedOption.builder().text(optionText).value(optionValue).build());
                }
            }
        } else if ("Dropdown".equals(type)) {
            Elements optionElements = questionElement.select("option");

            for (Element optionElement : optionElements) {
                String optionText = optionElement.text().trim();
                String optionValue = optionElement.attr("value");

                // Skip the placeholder option
                if (!optionText.isEmpty() && !optionValue.isEmpty()) {
                    options.add(
                            ExtractedOption.builder().text(optionText).value(optionValue).build());
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
            Element inputElement = optionElement.selectFirst("input");
            if (inputElement != null) {
                value = inputElement.attr("value");
            }
        }

        // If still not found, use the text as a fallback
        if (value.isEmpty()) {
            Element textElement = optionElement.selectFirst(".exportLabel");
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

