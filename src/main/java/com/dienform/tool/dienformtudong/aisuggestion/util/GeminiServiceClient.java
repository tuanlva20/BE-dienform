package com.dienform.tool.dienformtudong.aisuggestion.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import com.dienform.tool.dienformtudong.aisuggestion.dto.request.AnswerAttributesRequest;
import com.dienform.tool.dienformtudong.aisuggestion.dto.response.AnswerAttributesResponse;
import com.dienform.tool.dienformtudong.aisuggestion.exception.AISuggestionException;
import com.dienform.tool.dienformtudong.aisuggestion.exception.TokenLimitExceededException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gemini AI Service Client Implementation Handles communication with Google Gemini AI service for
 * generating answer attributes
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiServiceClient implements AIServiceClient {

  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;

  @Value("${ai.suggestion.gemini.api-key}")
  private String apiKey;

  @Value("${ai.suggestion.gemini.base-url}")
  private String baseUrl;

  @Value("${ai.suggestion.gemini.model}")
  private String model;

  @Value("${ai.suggestion.gemini.max-tokens}")
  private Integer maxTokens;

  @Value("${ai.suggestion.gemini.timeout:30000}")
  private Integer timeout;

  @Value("${ai.suggestion.gemini.batch-size:10}")
  private Integer batchSize;

  @Value("${ai.suggestion.gemini.parallelism:3}")
  private Integer parallelism;

  @Override
  public boolean isServiceAvailable() {
    try {
      String url = baseUrl + "/" + model;
      HttpHeaders headers = new HttpHeaders();
      headers.set("Authorization", "Bearer " + apiKey);

      HttpEntity<Void> entity = new HttpEntity<>(headers);
      ResponseEntity<String> response =
          restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

      return response.getStatusCode() == HttpStatus.OK;
    } catch (Exception e) {
      log.warn("AI service availability check failed: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public Long getRemainingTokens() {
    // For Gemini, this would require checking quota API
    // For now, return a default value
    return (long) maxTokens;
  }

  @Override
  public Map<String, Object> getHealthStatus() {
    Map<String, Object> status = new HashMap<>();
    status.put("service", "Gemini AI");
    status.put("available", isServiceAvailable());
    status.put("model", model);
    status.put("maxTokens", maxTokens);
    status.put("remainingTokens", getRemainingTokens());
    return status;
  }

  @Override
  public Map<String, Object> getServiceConfiguration() {
    Map<String, Object> config = new HashMap<>();
    config.put("provider", "Gemini");
    config.put("model", model);
    config.put("maxTokens", maxTokens);
    config.put("timeout", timeout);
    return config;
  }

  @Override
  public AnswerAttributesResponse generateAnswerAttributes(AnswerAttributesRequest request,
      Map<String, Object> formData) {
    log.info(
        "Generating answer attributes (batched) for form: {}, samples: {}, batchSize={}, parallelism={}",
        request.getFormId(), request.getSampleCount(), batchSize, parallelism);

    try {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> questions =
          (List<Map<String, Object>>) formData.getOrDefault("questions", List.of());

      if (questions.isEmpty()) {
        return AnswerAttributesResponse.builder().formId(request.getFormId())
            .formTitle((String) formData.getOrDefault("name", null))
            .sampleCount(request.getSampleCount()).questionAnswerAttributes(new ArrayList<>())
            .generatedAt(java.time.LocalDateTime.now().toString())
            .requestId(UUID.randomUUID().toString()).build();
      }

      List<List<Map<String, Object>>> batches = chunkQuestions(questions, Math.max(1, batchSize));
      Semaphore semaphore = new Semaphore(Math.max(1, parallelism));

      List<CompletableFuture<AnswerAttributesResponse>> futures = new ArrayList<>();
      for (int idx = 0; idx < batches.size(); idx++) {
        final int batchIndex = idx + 1;
        final List<Map<String, Object>> batch = batches.get(idx);

        futures.add(CompletableFuture.supplyAsync(() -> {
          try {
            semaphore.acquire();
            long start = System.currentTimeMillis();
            Map<String, Object> partialFormData = buildPartialFormData(formData, batch);
            log.info("Processing batch {}/{} with {} questions", batchIndex, batches.size(),
                batch.size());
            AnswerAttributesResponse partial = invokeGeminiOnce(request, partialFormData);
            log.info("Completed batch {}/{} in {}ms", batchIndex, batches.size(),
                System.currentTimeMillis() - start);
            return partial;
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AISuggestionException("Batch " + batchIndex + " interrupted");
          } catch (AISuggestionException | JsonProcessingException ex) {
            throw new AISuggestionException("Batch " + batchIndex + " failed: " + ex.getMessage(),
                ex);
          } finally {
            semaphore.release();
          }
        }));
      }

      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      List<AnswerAttributesResponse.QuestionAnswerAttribute> all = new ArrayList<>();
      String formTitle = (String) formData.getOrDefault("name", null);

      for (CompletableFuture<AnswerAttributesResponse> f : futures) {
        AnswerAttributesResponse partial = f.join();
        if (formTitle == null && partial.getFormTitle() != null) {
          formTitle = partial.getFormTitle();
        }
        mergeQuestionAttributes(all, partial.getQuestionAnswerAttributes());
      }

      return AnswerAttributesResponse.builder().formId(request.getFormId()).formTitle(formTitle)
          .sampleCount(request.getSampleCount()).questionAnswerAttributes(all)
          .generatedAt(java.time.LocalDateTime.now().toString())
          .requestId(UUID.randomUUID().toString()).build();

    } catch (CompletionException ce) {
      Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
      if (cause instanceof TokenLimitExceededException) {
        throw (TokenLimitExceededException) cause;
      }
      log.error("Error generating answer attributes (batched) for form {}: {}", request.getFormId(),
          cause.getMessage(), cause);
      throw new AISuggestionException("Failed to generate answer attributes: " + cause.getMessage(),
          cause);
    } catch (TokenLimitExceededException e) {
      throw e;
    } catch (AISuggestionException e) {
      throw e;
    } catch (Exception e) {
      log.error("Error generating answer attributes: {}", e.getMessage(), e);
      throw new AISuggestionException("Failed to generate answer attributes: " + e.getMessage());
    }
  }

  private Map<String, Object> createRequestPayload(String prompt) {
    Map<String, Object> payload = new HashMap<>();

    List<Map<String, Object>> contents = new ArrayList<>();
    Map<String, Object> content = new HashMap<>();

    List<Map<String, String>> parts = new ArrayList<>();
    Map<String, String> part = new HashMap<>();
    part.put("text", prompt);
    parts.add(part);

    content.put("parts", parts);
    contents.add(content);

    payload.put("contents", contents);

    // Add generation config
    Map<String, Object> generationConfig = new HashMap<>();
    generationConfig.put("temperature", 0.7);
    generationConfig.put("topP", 0.8);
    generationConfig.put("topK", 40);
    generationConfig.put("maxOutputTokens", maxTokens);

    payload.put("generationConfig", generationConfig);

    return payload;
  }

  private String extractJsonFromText(String text) {
    // Find JSON content between ```json and ``` or just look for { }
    int startIndex = text.indexOf("{");
    int endIndex = text.lastIndexOf("}");

    if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) {
      throw new AISuggestionException("No valid JSON found in AI response");
    }

    return text.substring(startIndex, endIndex + 1);
  }

  private String buildAnswerAttributesPrompt(AnswerAttributesRequest request,
      Map<String, Object> formData) {
    StringBuilder prompt = new StringBuilder();

    prompt.append(
        "Bạn là một chuyên gia phân tích dữ liệu khảo sát. Hãy tạo ra các thuộc tính đáp án (answer attributes) cho form khảo sát dựa trên yêu cầu sau:\n\n");

    // Form information
    prompt.append("THÔNG TIN FORM:\n");
    prompt.append("Form ID: ").append(request.getFormId()).append("\n");
    prompt.append("Số lượng mẫu: ").append(request.getSampleCount()).append("\n\n");

    // Form structure
    try {
      prompt.append("CẤU TRÚC FORM:\n");
      prompt.append(objectMapper.writeValueAsString(formData)).append("\n\n");

      // Requirements
      if (request.getRequirements() != null) {
        prompt.append("YÊU CẦU:\n");
        prompt.append(objectMapper.writeValueAsString(request.getRequirements())).append("\n\n");
      }
    } catch (JsonProcessingException e) {
      log.error("Error serializing form data or requirements: {}", e.getMessage(), e);
      throw new AISuggestionException("Failed to serialize form data: " + e.getMessage());
    }

    prompt.append("HƯỚNG DẪN:\n");
    prompt.append(
        "Chỉ xử lý các câu hỏi có trong mảng 'questions' của CẤU TRÚC FORM ở trên. Không tạo thêm câu hỏi không có trong dữ liệu.\n");
    prompt.append("1. Phân tích từng câu hỏi trong form\n");
    prompt.append("2. Tạo ra tỉ lệ phân bố cho các đáp án (tổng = 100%)\n");
    prompt.append("3. Với câu hỏi text:\n"
        + "   - Tạo ra câu trả lời tự nhiên, đa dạng, giống người thật\n"
        + "   - Tránh tên kiểu 'Nguyễn Văn A', thay vào đó dùng tên thật, hoặc tên 2 chữ, 1 chữ'\n"
        + "   - Một số câu trả lời ngắn gọn (5-10%), một số dài hơn (20-30%), phần còn lại vừa phải\n"
        + "   - Thỉnh thoảng có lỗi chính tả nhẹ (2-3% để thể hiện tính người thật)\n"
        + "   - Có thể để trống (2-5% để mô phỏng việc bỏ qua câu hỏi)\n"
        + "   - Đảm bảo tính logic với các câu trả lời khác trong form\n"
        + "   - Với email: dùng domain thật như gmail.com, outlook.com, yahoo.com và đuôi *.edu.vn – trường đại học/giáo dục (ví dụ: @hcmut.edu.vn, @hust.edu.vn)\n"
        + "   - Với ghi chú/comment: thể hiện cảm xúc, ý kiến cá nhân\n");
    prompt.append("4. Với câu hỏi 'Khác' (Other options):\n"
        + "   - Tạo ra các giá trị mẫu thực tế, phù hợp với context của câu hỏi\n"
        + "   - Đảm bảo sampleValues đa dạng và có ý nghĩa\n"
        + "   - Thể hiện những lựa chọn mà người dùng thường viết thêm\n"
        + "   - Ví dụ: với câu hỏi về phương tiện di chuyển, 'Khác' có thể là 'Đi bộ', 'Xe đạp điện'\n"
        + "   - Với sự kiện tham dự: 'Bận việc gia đình', 'Kẹt lịch công tác', 'Sức khỏe không cho phép'\n");
    prompt.append("5. Đảm bảo dữ liệu như người thật và tự nhiên\n");
    prompt.append(
        "6. Tính logic: Các câu trả lời phải có sự liên kết. Ví dụ, nếu tên là 'Nguyễn Thị Mai', giới tính phải là 'Nữ' và email có thể là 'mainguyen@gmail.com'. Thỉnh thoảng, tên có thể là nam nhưng chọn nữ, và ngược lại.\n");
    prompt.append("7. QUAN TRỌNG: Với câu hỏi grid (multiple_choice_grid, checkbox_grid):\n");
    prompt.append("   - Cấu trúc grid: Có các hàng (rows) và cột (columns)\n");
    prompt.append("   - Mỗi hàng (row) có phân bổ riêng cho các cột (columns)\n");
    prompt.append("   - Tổng phần trăm của các lựa chọn cột trong mỗi hàng phải = 100%\n");
    prompt.append("   - Ví dụ: Với grid 'Phương tiện di chuyển':\n");
    prompt.append(
        "     * Row 1 'Ngày đầu sự kiện': 'Xe công ty đưa đón'(30%), 'Tự túc'(40%), 'Taxi'(20%), 'Xe máy'(10%)\n");
    prompt.append(
        "     * Row 2 'Ngày kết thúc': 'Xe công ty đưa đón'(25%), 'Tự túc'(45%), 'Taxi'(20%), 'Xe máy'(10%)\n");
    prompt.append(
        "   - Với checkbox_grid: Mỗi người có thể chọn nhiều lựa chọn, nhưng tổng % vẫn = 100%\n");
    prompt.append("   - Cách phân tích grid:\n");
    prompt.append("     * Bước 1: Xác định các hàng (rows) trong grid\n");
    prompt.append("     * Bước 2: Xác định các cột (columns) trong grid\n");
    prompt.append(
        "     * Bước 3: Phân bổ % cho các column options trong mỗi row sao cho tổng = 100%\n");
    prompt.append("     * Bước 4: Tạo sampleValues cho mỗi option\n");
    prompt.append("   - Phân bổ % hợp lý: Ưu tiên các lựa chọn phổ biến, thực tế\n");

    prompt.append("8. Trả về kết quả dưới dạng JSON với cấu trúc:\n");
    prompt.append("{\n");
    prompt.append("  \"formId\": \"string\",\n");
    prompt.append("  \"formTitle\": \"string\",\n");
    prompt.append("  \"sampleCount\": number,\n");
    prompt.append("  \"questionAnswerAttributes\": [\n");
    prompt.append("    {\n");
    prompt.append("      \"questionId\": \"string\",\n");
    prompt.append("      \"questionTitle\": \"string\",\n");
    prompt.append("      \"questionType\": \"string\",\n");
    prompt.append("      \"isRequired\": boolean,\n");
    prompt.append("      \"optionDistributions\": [\n");
    prompt.append("        {\n");
    prompt.append("          \"optionId\": \"string\",\n");
    prompt.append("          \"optionText\": \"string\",\n");
    prompt.append("          \"optionValue\": \"string\",\n");
    prompt.append("          \"percentage\": number,\n");
    prompt.append("          \"sampleValues\": [\"string\"]\n");
    prompt.append("        }\n");
    prompt.append("      ],\n");
    prompt.append("      \"sampleAnswers\": [\"string\"],\n");
    prompt.append("      \"gridRowDistributions\": [\n");
    prompt.append("        {\n");
    prompt.append("          \"rowId\": \"string\",\n");
    prompt.append("          \"rowLabel\": \"string\",\n");
    prompt.append("          \"columnDistributions\": [\n");
    prompt.append("            {\n");
    prompt.append("              \"optionId\": \"string\",\n");
    prompt.append("              \"optionText\": \"string\",\n");
    prompt.append("              \"optionValue\": \"string\",\n");
    prompt.append("              \"percentage\": number,\n");
    prompt.append("              \"sampleValues\": [\"string\"]\n");
    prompt.append("            }\n");
    prompt.append("          ]\n");
    prompt.append("        }\n");
    prompt.append("      ]\n");
    prompt.append("    }\n");
    prompt.append("  ]\n");
    prompt.append("}\n\n");

    prompt.append("LƯU Ý QUAN TRỌNG:\n");
    prompt.append("- Với grid questions (multiple_choice_grid, checkbox_grid):\n");
    prompt.append("  * Sử dụng cấu trúc gridRowDistributions thay vì optionDistributions\n");
    prompt.append("  * Không tạo tỉ lệ cho các option_value:'row_[index]' chỉ tạo tỉ lệ cho option của row\n");
    prompt.append("  * Mỗi row có phân bổ riêng cho các column options\n");
    prompt.append("  * Tổng percentage của các column options trong mỗi row phải = 100%\n");
    prompt.append("  * Ví dụ: Row 'Ngày đầu sự kiện' có 4 columns với tổng % = 100%\n");
    prompt.append("- Với câu hỏi thường: Sử dụng optionDistributions\n");
    prompt.append("- sampleValues: Tạo các giá trị mẫu thực tế cho mỗi option\n");
    prompt.append("- sampleAnswers: CHỈ tạo cho các loại câu hỏi sau:\n");
    prompt.append("  * text: Câu hỏi tự luận\n");
    prompt.append("  * date: Câu hỏi ngày tháng\n");
    prompt.append("  * time: Câu hỏi thời gian\n");
    prompt.append("  * Các option có text chứa 'Khác' hoặc 'Other'\n");
    prompt.append("  * KHÔNG tạo sampleAnswers cho radio, checkbox, grid questions\n");
    prompt.append(
        "- Với checkbox_grid: sampleAnswers có thể chứa nhiều lựa chọn (ví dụ: 'Workshop, Teambuilding')\n\n");

    prompt.append("Hãy tạo ra answer attributes hoàn chỉnh cho tất cả câu hỏi trong form.");

    return prompt.toString();
  }

  private AnswerAttributesResponse invokeGeminiOnce(AnswerAttributesRequest request,
      Map<String, Object> partialFormData) throws JsonProcessingException {

    // Build the prompt for answer attributes
    String prompt = buildAnswerAttributesPrompt(request, partialFormData);
    log.debug("Generated answer attributes prompt length: {} characters", prompt.length());

    // Create request payload
    Map<String, Object> requestPayload = createRequestPayload(prompt);

    // Set headers (no Authorization header needed, use API key in URL)
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(requestPayload, headers);

    // Make API call with API key as query parameter
    String url = baseUrl + "/" + model + ":generateContent?key=" + apiKey;
    log.debug("Making request to Gemini API for answer attributes: {}", url);

    ResponseEntity<String> response =
        restTemplate.exchange(url, HttpMethod.POST, httpEntity, String.class);

    if (response.getStatusCode() == HttpStatus.OK) {
      return parseAnswerAttributesResponse(response.getBody(), request, partialFormData);
    } else {
      throw new AISuggestionException(
          "API request failed with status: " + response.getStatusCode());
    }
  }

  private List<List<Map<String, Object>>> chunkQuestions(List<Map<String, Object>> questions,
      int size) {
    List<List<Map<String, Object>>> chunks = new ArrayList<>();
    if (questions == null || questions.isEmpty()) {
      return chunks;
    }
    int chunkSize = Math.max(1, size);
    for (int i = 0; i < questions.size(); i += chunkSize) {
      chunks.add(questions.subList(i, Math.min(i + chunkSize, questions.size())));
    }
    return chunks;
  }

  private Map<String, Object> buildPartialFormData(Map<String, Object> formData,
      List<Map<String, Object>> questionBatch) {
    Map<String, Object> partial = new HashMap<>(formData);
    partial.put("questions", new ArrayList<>(questionBatch));
    return partial;
  }

  private void mergeQuestionAttributes(List<AnswerAttributesResponse.QuestionAnswerAttribute> total,
      List<AnswerAttributesResponse.QuestionAnswerAttribute> part) {
    if (part == null || part.isEmpty()) {
      return;
    }
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (AnswerAttributesResponse.QuestionAnswerAttribute qa : total) {
      if (qa.getQuestionId() != null) {
        seen.add(qa.getQuestionId());
      }
    }
    for (AnswerAttributesResponse.QuestionAnswerAttribute qa : part) {
      String qid = qa.getQuestionId();
      if (qid == null || !seen.contains(qid)) {
        total.add(qa);
        if (qid != null) {
          seen.add(qid);
        }
      }
    }
  }

  private AnswerAttributesResponse parseAnswerAttributesResponse(String responseBody,
      AnswerAttributesRequest request, Map<String, Object> formData)
      throws JsonProcessingException {

    JsonNode rootNode = objectMapper.readTree(responseBody);
    JsonNode candidatesNode = rootNode.path("candidates");

    if (candidatesNode.isEmpty() || !candidatesNode.isArray()) {
      throw new AISuggestionException("Invalid response format from AI service");
    }

    JsonNode contentNode = candidatesNode.get(0).path("content").path("parts").get(0).path("text");
    String textContent = contentNode.asText();

    // Extract JSON from the text content
    String jsonContent = extractJsonFromText(textContent);
    JsonNode answerAttributesData = objectMapper.readTree(jsonContent);

    // Parse the response
    AnswerAttributesResponse.AnswerAttributesResponseBuilder responseBuilder =
        AnswerAttributesResponse.builder().formId(request.getFormId())
            .sampleCount(request.getSampleCount())
            .generatedAt(java.time.LocalDateTime.now().toString())
            .requestId(UUID.randomUUID().toString());

    // Parse form title
    if (answerAttributesData.has("formTitle")) {
      responseBuilder.formTitle(answerAttributesData.get("formTitle").asText());
    }

    // Parse question answer attributes
    List<AnswerAttributesResponse.QuestionAnswerAttribute> questionAttributes = new ArrayList<>();
    JsonNode questionAttributesNode = answerAttributesData.path("questionAnswerAttributes");

    for (JsonNode questionNode : questionAttributesNode) {
      AnswerAttributesResponse.QuestionAnswerAttribute questionAttribute =
          parseQuestionAnswerAttribute(questionNode);
      questionAttributes.add(questionAttribute);
    }

    responseBuilder.questionAnswerAttributes(questionAttributes);

    return responseBuilder.build();
  }

  private AnswerAttributesResponse.QuestionAnswerAttribute parseQuestionAnswerAttribute(
      JsonNode questionNode) {
    AnswerAttributesResponse.QuestionAnswerAttribute.QuestionAnswerAttributeBuilder builder =
        AnswerAttributesResponse.QuestionAnswerAttribute.builder()
            .questionId(questionNode.path("questionId").asText())
            .questionTitle(questionNode.path("questionTitle").asText())
            .questionType(questionNode.path("questionType").asText())
            .isRequired(questionNode.path("isRequired").asBoolean());

    // Parse option distributions
    List<AnswerAttributesResponse.OptionDistribution> optionDistributions = new ArrayList<>();
    JsonNode optionDistributionsNode = questionNode.path("optionDistributions");

    for (JsonNode optionNode : optionDistributionsNode) {
      AnswerAttributesResponse.OptionDistribution optionDistribution =
          parseOptionDistribution(optionNode);
      optionDistributions.add(optionDistribution);
    }

    builder.optionDistributions(optionDistributions);

    // Parse sample answers only for question types that need them
    String questionType = questionNode.path("questionType").asText();
    if (needsSampleAnswers(questionType, optionDistributions)) {
      List<String> sampleAnswers = new ArrayList<>();
      JsonNode sampleAnswersNode = questionNode.path("sampleAnswers");

      for (JsonNode sampleNode : sampleAnswersNode) {
        sampleAnswers.add(sampleNode.asText());
      }

      builder.sampleAnswers(sampleAnswers);
    }

    // Parse grid row distributions for grid questions
    if (isGridQuestion(questionNode.path("questionType").asText())) {
      List<AnswerAttributesResponse.GridRowDistribution> gridRowDistributions = new ArrayList<>();
      JsonNode gridRowDistributionsNode = questionNode.path("gridRowDistributions");

      for (JsonNode gridRowNode : gridRowDistributionsNode) {
        AnswerAttributesResponse.GridRowDistribution gridRowDistribution =
            parseGridRowDistribution(gridRowNode);
        gridRowDistributions.add(gridRowDistribution);
      }

      builder.gridRowDistributions(gridRowDistributions);

      // For grid questions, check if any column contains "Khác" or "Other" options
      if (needsSampleAnswersForGrid(gridRowDistributions)) {
        List<String> sampleAnswers = new ArrayList<>();
        JsonNode sampleAnswersNode = questionNode.path("sampleAnswers");

        for (JsonNode sampleNode : sampleAnswersNode) {
          sampleAnswers.add(sampleNode.asText());
        }

        builder.sampleAnswers(sampleAnswers);
      }
    }

    return builder.build();
  }

  private AnswerAttributesResponse.OptionDistribution parseOptionDistribution(JsonNode optionNode) {
    AnswerAttributesResponse.OptionDistribution.OptionDistributionBuilder builder =
        AnswerAttributesResponse.OptionDistribution.builder()
            .optionId(optionNode.path("optionId").asText())
            .optionText(optionNode.path("optionText").asText())
            .optionValue(optionNode.path("optionValue").asText())
            .percentage(optionNode.path("percentage").asInt());

    // Parse sample values
    List<String> sampleValues = new ArrayList<>();
    JsonNode sampleValuesNode = optionNode.path("sampleValues");

    for (JsonNode sampleNode : sampleValuesNode) {
      sampleValues.add(sampleNode.asText());
    }

    builder.sampleValues(sampleValues);

    return builder.build();
  }

  private AnswerAttributesResponse.GridRowDistribution parseGridRowDistribution(
      JsonNode gridRowNode) {
    AnswerAttributesResponse.GridRowDistribution.GridRowDistributionBuilder builder =
        AnswerAttributesResponse.GridRowDistribution.builder()
            .rowId(gridRowNode.path("rowId").asText())
            .rowLabel(gridRowNode.path("rowLabel").asText());

    // Parse column distributions
    List<AnswerAttributesResponse.OptionDistribution> columnDistributions = new ArrayList<>();
    JsonNode columnDistributionsNode = gridRowNode.path("columnDistributions");

    for (JsonNode columnNode : columnDistributionsNode) {
      AnswerAttributesResponse.OptionDistribution columnDistribution =
          parseOptionDistribution(columnNode);
      columnDistributions.add(columnDistribution);
    }

    builder.columnDistributions(columnDistributions);

    return builder.build();
  }

  private boolean isGridQuestion(String questionType) {
    return "multiple_choice_grid".equals(questionType) || "checkbox_grid".equals(questionType);
  }

  /**
   * Check if a question type needs sample answers. Only text, date, time questions and options with
   * "Khác"/"Other" need sample answers.
   */
  private boolean needsSampleAnswers(String questionType,
      List<AnswerAttributesResponse.OptionDistribution> optionDistributions) {

    // Text, date, time questions always need sample answers
    if ("text".equals(questionType) || "date".equals(questionType) || "time".equals(questionType)) {
      return true;
    }

    // Check if any option contains "Khác" or "Other"
    if (optionDistributions != null) {
      for (AnswerAttributesResponse.OptionDistribution option : optionDistributions) {
        String optionText = option.getOptionText();
        if (optionText != null && (optionText.toLowerCase().contains("khác")
            || optionText.toLowerCase().contains("other"))) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Check if a grid question needs sample answers. Only if any column contains "Khác" or "Other"
   * options.
   */
  private boolean needsSampleAnswersForGrid(
      List<AnswerAttributesResponse.GridRowDistribution> gridRowDistributions) {

    if (gridRowDistributions == null) {
      return false;
    }

    for (AnswerAttributesResponse.GridRowDistribution rowDistribution : gridRowDistributions) {
      List<AnswerAttributesResponse.OptionDistribution> columnDistributions =
          rowDistribution.getColumnDistributions();

      if (columnDistributions != null) {
        for (AnswerAttributesResponse.OptionDistribution column : columnDistributions) {
          String columnText = column.getOptionText();
          if (columnText != null && (columnText.toLowerCase().contains("khác")
              || columnText.toLowerCase().contains("other"))) {
            return true;
          }
        }
      }
    }

    return false;
  }
}
