package com.dienform.tool.dienformtudong.googleform.util;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.util.Elements;
import javax.swing.text.Element;

/**
 * Utility class for parsing Google Forms and extracting their structure
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleFormParser {

    private final RestTemplate restTemplate;

    /**
     * Parse a Google Form and extract its questions and options
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
        List<ExtractedQuestion> questions = extractQuestions(document);
        
        return questions;
    }

    /**
     * Submit answers to a Google Form
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
            ResponseEntity<String> response = restTemplate.postForEntity(submitUrl, request, String.class);
            
            return response.getBody();
        } catch (Exception e) {
            log.error("Error submitting form: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to submit Google Form", e);
        }
    }

    /**
     * Convert a Google Form edit URL to its public URL
     * @param editUrl The edit URL
     * @return The public view URL
     */
    private String convertToPublicUrl(String editUrl) {
        // Example edit URL: https://docs.google.com/forms/d/{form-id}/edit
        // Public URL: https://docs.google.com/forms/d/e/{form-id}/viewform
        
        String formId = extractFormIdFromEditUrl(editUrl);
        return "https://docs.google.com/forms/d/e/" + formId + "/viewform";
    }

    /**
     * Extract form ID from an edit URL
     * @param editUrl The edit URL
     * @return The form ID
     */
    private String extractFormIdFromEditUrl(String editUrl) {
        Pattern pattern = Pattern.compile("https://docs\\.google\\.com/forms/d/([\\w-]+)/edit");
        Matcher matcher = pattern.matcher(editUrl);
        
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        throw new IllegalArgumentException("Invalid Google Form edit URL format");
    }

    /**
     * Extract form ID from the form document
     * @param document The parsed HTML document
     * @return The form ID
     */
    private String extractFormId(Document document) {
        // Look for the form ID in meta tags or script elements
        // Elements scripts = document.select("script");
        
        // for (Element script : scripts) {
        //     String scriptContent = script.html();
        //     Pattern pattern = Pattern.compile("'?\\[\\[\\"?(.*?)\\\"?,");
        //     Matcher matcher = pattern.matcher(scriptContent);
            
        //     if (matcher.find()) {
        //         return matcher.group(1);
        //     }
        // }
        
        throw new IllegalArgumentException("Could not extract form ID from the document");
    }

    /**
     * Extract questions and their options from the form document
     * @param document The parsed HTML document
     * @return List of extracted questions
     */
    private List<ExtractedQuestion> extractQuestions(Document document) {
        // List<ExtractedQuestion> questions = new ArrayList<>();
        
        // // Select all question containers
        // Elements questionElements = document.select("div[role=listitem]");
        
        // for (Element questionElement : questionElements) {
        //     // Extract question title
        //     Element titleElement = questionElement.selectFirst(".freebirdFormviewerComponentsQuestionBaseTitle");
        //     if (titleElement == null) continue;
            
        //     String title = titleElement.text();
            
        //     // Extract question description (if any)
        //     Element descElement = questionElement.selectFirst(".freebirdFormviewerComponentsQuestionBaseDescription");
        //     String description = descElement != null ? descElement.text() : "";
            
        //     // Determine question type
        //     String type = determineQuestionType(questionElement);
            
        //     // Check if question is required
        //     boolean required = questionElement.select(".freebirdFormviewerComponentsQuestionBaseRequiredAsterisk").size() > 0;
            
        //     // Extract options for multiple choice, checkbox, or dropdown questions
        //     List<ExtractedOption> options = extractOptions(questionElement, type);
            
        //     // Create and add the extracted question
        //     ExtractedQuestion question = ExtractedQuestion.builder()
        //             .title(title)
        //             .description(description)
        //             .type(type)
        //             .required(required)
        //             .options(options)
        //             .build();
            
        //     questions.add(question);
        // }
        
        return null;
    }

    /**
     * Determine the type of a question based on its HTML structure
     * @param questionElement The question element
     * @return The question type
     */
    private String determineQuestionType(Element questionElement) {
        // if (questionElement.select(".freebirdFormviewerComponentsQuestionRadioRoot").size() > 0) {
        //     return "Multiple Choice";
        // } else if (questionElement.select(".freebirdFormviewerComponentsQuestionCheckboxRoot").size() > 0) {
        //     return "Checkboxes";
        // } else if (questionElement.select(".freebirdFormviewerComponentsQuestionSelectRoot").size() > 0) {
        //     return "Dropdown";
        // } else if (questionElement.select(".freebirdFormviewerComponentsQuestionTextRoot").size() > 0) {
        //     if (questionElement.select("textarea").size() > 0) {
        //         return "Paragraph";
        //     } else {
        //         return "Short Answer";
        //     }
        // } else if (questionElement.select(".freebirdFormviewerComponentsQuestionScaleRoot").size() > 0) {
        //     return "Linear Scale";
        // } else if (questionElement.select(".freebirdFormviewerComponentsQuestionDateRoot").size() > 0) {
        //     return "Date";
        // } else if (questionElement.select(".freebirdFormviewerComponentsQuestionTimeRoot").size() > 0) {
        //     return "Time";
        // }
        
        return "Other";
    }

    /**
     * Extract options from multiple choice, checkbox, or dropdown questions
     * @param questionElement The question element
     * @param type The question type
     * @return List of extracted options
     */
    private List<ExtractedOption> extractOptions(Element questionElement, String type) {
        return Collections.emptyList();
        // List<ExtractedOption> options = new ArrayList<>();
        
        // if ("Multiple Choice".equals(type)) {
        //     Elements optionElements = questionElement.select(".freebirdFormviewerComponentsQuestionRadioChoice");
            
        //     for (Element optionElement : optionElements) {
        //         Element optionTextElement = optionElement.selectFirst(".exportLabel");
        //         if (optionTextElement != null) {
        //             String optionText = optionTextElement.text();
        //             String optionValue = extractOptionValue(optionElement);
                    
        //             options.add(ExtractedOption.builder()
        //                     .text(optionText)
        //                     .value(optionValue)
        //                     .build());
        //         }
        //     }
        // } else if ("Checkboxes".equals(type)) {
        //     Elements optionElements = questionElement.select(".freebirdFormviewerComponentsQuestionCheckboxChoice");
            
        //     for (Element optionElement : optionElements) {
        //         Element optionTextElement = optionElement.selectFirst(".exportLabel");
        //         if (optionTextElement != null) {
        //             String optionText = optionTextElement.text();
        //             String optionValue = extractOptionValue(optionElement);
                    
        //             options.add(ExtractedOption.builder()
        //                     .text(optionText)
        //                     .value(optionValue)
        //                     .build());
        //         }
        //     }
        // } else if ("Dropdown".equals(type)) {
        //     Elements optionElements = questionElement.select("option");
            
        //     for (Element optionElement : optionElements) {
        //         String optionText = optionElement.text();
        //         String optionValue = optionElement.attr("value");
                
        //         // Skip the placeholder option
        //         if (!optionText.isEmpty() && !optionValue.isEmpty()) {
        //             options.add(ExtractedOption.builder()
        //                     .text(optionText)
        //                     .value(optionValue)
        //                     .build());
        //         }
        //     }
        // }
        
        // return options;
    }

    /**
     * Extract the value of an option from its HTML element
     * @param optionElement The option element
     * @return The option value
     */
    private String extractOptionValue(Element optionElement) {
        // In many cases, the value is stored in a data attribute
        // String value = optionElement.attr("data-value");
        
        // // If not found, try to extract from an input element
        // if (value.isEmpty()) {
        //     Element inputElement = optionElement.selectFirst("input");
        //     if (inputElement != null) {
        //         value = inputElement.attr("value");
        //     }
        // }
        
        // // If still not found, use the text as a fallback
        // if (value.isEmpty()) {
        //     Element textElement = optionElement.selectFirst(".exportLabel");
        //     if (textElement != null) {
        //         value = textElement.text();
        //     }
        // }
        
        return "value";
    }

    /**
     * Prepare form data for submission
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
     * @param document The form document
     * @return The fbzx token
     */
    private String extractFbzxToken(Document document) {
        // Elements inputElements = document.select("input[name=fbzx]");
        
        // if (!inputElements.isEmpty()) {
        //     return inputElements.first().attr("value");
        // }
        
        return "";
    }

    /**
     * Represents an extracted question from a Google Form
     */
    @Data
    @Builder
    public static class ExtractedQuestion {
        private String title;
        private String description;
        private String type;
        private boolean required;
        private List<ExtractedOption> options;
    }

    /**
     * Represents an extracted option from a Google Form question
     */
    @Data
    @Builder
    public static class ExtractedOption {
        private String text;
        private String value;
    }
}