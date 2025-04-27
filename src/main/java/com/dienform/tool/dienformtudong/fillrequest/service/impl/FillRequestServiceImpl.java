package com.dienform.tool.dienformtudong.fillrequest.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.common.exception.BadRequestException;
import com.dienform.common.exception.NotFoundException;
import com.dienform.common.exception.ResourceNotFoundException;
import com.dienform.common.util.Constants;
import com.dienform.tool.dienformtudong.answerdistribution.entity.AnswerDistribution;
import com.dienform.tool.dienformtudong.answerdistribution.repository.AnswerDistributionRepository;
import com.dienform.tool.dienformtudong.fillrequest.dto.request.FillRequestDTO;
import com.dienform.tool.dienformtudong.fillrequest.dto.response.FillRequestResponse;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.fillrequest.mapper.FillRequestMapper;
import com.dienform.tool.dienformtudong.fillrequest.repository.FillRequestRepository;
import com.dienform.tool.dienformtudong.fillrequest.service.FillRequestService;
import com.dienform.tool.dienformtudong.form.entity.Form;
import com.dienform.tool.dienformtudong.form.repository.FormRepository;
import com.dienform.tool.dienformtudong.googleform.service.GoogleFormService;
import com.dienform.tool.dienformtudong.question.entity.Question;
import com.dienform.tool.dienformtudong.question.entity.QuestionOption;
import com.dienform.tool.dienformtudong.question.repository.QuestionOptionRepository;
import com.dienform.tool.dienformtudong.question.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FillRequestServiceImpl implements FillRequestService {

        private final FillRequestRepository fillRequestRepository;
        private final AnswerDistributionRepository distributionRepository;
        private final QuestionOptionRepository optionRepository;
        private final FormRepository formRepository;
        private final QuestionRepository questionRepository;
        private final FillRequestMapper fillRequestMapper;
        private final GoogleFormService googleFormService;

        @Override
        @Transactional
        public FillRequestResponse createFillRequest(UUID formId, FillRequestDTO fillRequestDTO) {
                // Validate form exists
                Form form = formRepository.findById(formId).orElseThrow(
                                () -> new NotFoundException("Cannot find form with id: " + formId));
                List<Question> questions = questionRepository.findByForm(form);

                // Validate answer distributions
                validateAnswerDistributions(fillRequestDTO.getAnswerDistributions());

                // Create fill request
                FillRequest fillRequest = FillRequest.builder().form(form)
                                .surveyCount(fillRequestDTO.getSurveyCount())
                                .pricePerSurvey(fillRequestDTO.getPricePerSurvey())
                                .totalPrice(fillRequestDTO.getPricePerSurvey()
                                                .multiply(BigDecimal.valueOf(
                                                                fillRequestDTO.getSurveyCount())))
                                .humanLike(fillRequestDTO.getIsHumanLike())
                                .status(Constants.FILL_REQUEST_STATUS_PENDING).build();

                FillRequest savedRequest = fillRequestRepository.save(fillRequest);

                // Create answer distributions
                List<AnswerDistribution> distributions = new ArrayList<>();
                Map<UUID, List<FillRequestDTO.AnswerDistributionRequest>> groupedByQuestion =
                                fillRequestDTO.getAnswerDistributions().stream().collect(Collectors
                                                .groupingBy(FillRequestDTO.AnswerDistributionRequest::getQuestionId));

                for (Map.Entry<UUID, List<FillRequestDTO.AnswerDistributionRequest>> entry : groupedByQuestion
                                .entrySet()) {
                        UUID questionId = entry.getKey();
                        Question question = questions.stream()
                                        .filter(q -> q.getId().equals(questionId)).findFirst()
                                        .orElseThrow(() -> new ResourceNotFoundException("Question",
                                                        "id", questionId));
                        List<FillRequestDTO.AnswerDistributionRequest> questionDistributions =
                                        entry.getValue();

                        // Validate total percentage for each question is 100%
                        int totalPercentage = questionDistributions.stream().mapToInt(
                                        FillRequestDTO.AnswerDistributionRequest::getPercentage)
                                        .sum();

                        if (totalPercentage > 100) {
                                throw new BadRequestException(String.format(
                                                "Total percentage for question %s <= 100%, but"
                                                                + " was %d%%",
                                                questionId, totalPercentage));
                        }

                        // Calculate count for each option based on percentage
                        for (FillRequestDTO.AnswerDistributionRequest dist : questionDistributions) {
                                int count = (int) Math.round(fillRequestDTO.getSurveyCount()
                                                * (dist.getPercentage() / 100.0));
                                QuestionOption option = question.getOptions().stream()
                                                .filter(o -> o.getId().equals(dist.getOptionId()))
                                                .findAny()
                                                .orElseThrow(() -> new NotFoundException(
                                                                "Cannot find option with id: "
                                                                                + dist.getOptionId()));
                                AnswerDistribution distribution = AnswerDistribution.builder()
                                                .fillRequest(savedRequest).question(question)
                                                .option(option).percentage(dist.getPercentage())
                                                .count(count).build();
                                distributions.add(distribution);
                        }
                }

                distributionRepository.saveAll(distributions);

                // Start the form filling process asynchronously
                CompletableFuture.runAsync(() -> {
                        try {
                                log.info("Starting automated form filling for request ID: {}",
                                                savedRequest.getId());
                                googleFormService.fillForm(savedRequest.getId());
                        } catch (Exception e) {
                                log.error("Error starting form filling process: {}", e.getMessage(),
                                                e);

                                // Update request status to failed if there's an error
                                try {
                                        FillRequest request = fillRequestRepository
                                                        .findById(savedRequest.getId())
                                                        .orElse(null);
                                        if (request != null) {
                                                request.setStatus(
                                                                Constants.FILL_REQUEST_STATUS_FAILED);
                                                fillRequestRepository.save(request);
                                        }
                                } catch (Exception ex) {
                                        log.error("Error updating fill request status: {}",
                                                        ex.getMessage(), ex);
                                }
                        }
                });

                return fillRequestMapper.toReponse(savedRequest);
        }

        @Override
        public FillRequestResponse getFillRequestById(UUID id) {
                FillRequest fillRequest = fillRequestRepository.findById(id).orElseThrow(
                                () -> new ResourceNotFoundException("Fill Request", "id", id));

                List<AnswerDistribution> distributions =
                                distributionRepository.findByFillRequestId(id);

                return mapToFillRequestResponse(fillRequest, distributions);
        }

        @Override
        @Transactional
        public Map<String, Object> startFillRequest(UUID id) {
                FillRequest fillRequest = fillRequestRepository.findById(id).orElseThrow(
                                () -> new ResourceNotFoundException("Fill Request", "id", id));

                // You can add validation here to check if the request is in a valid state to start

                // Update status
                fillRequest.setStatus(Constants.FILL_REQUEST_STATUS_RUNNING);
                fillRequestRepository.save(fillRequest);

                // Start form filling process asynchronously
                CompletableFuture.runAsync(() -> {
                        try {
                                googleFormService.fillForm(id);
                        } catch (Exception e) {
                                log.error("Error in form filling process for request ID {}: {}", id,
                                                e.getMessage(), e);
                        }
                });

                // Return response with status
                Map<String, Object> response = new HashMap<>();
                response.put("id", fillRequest.getId());
                response.put("status", fillRequest.getStatus());
                response.put("message", "Fill request started successfully");

                return response;
        }

        @Override
        @Transactional
        public void deleteFillRequest(UUID id) {
                if (!fillRequestRepository.existsById(id)) {
                        throw new ResourceNotFoundException("Fill Request", "id", id);
                }

                // Due to cascade delete in the database, this will delete related distributions
                fillRequestRepository.deleteById(id);
        }

        private void validateAnswerDistributions(
                        List<FillRequestDTO.AnswerDistributionRequest> distributions) {
                if (distributions == null || distributions.isEmpty()) {
                        throw new BadRequestException("Answer distributions cannot be empty");
                }

                // Check that all question and option IDs exist
                for (FillRequestDTO.AnswerDistributionRequest distribution : distributions) {
                        QuestionOption option = optionRepository
                                        .findById(distribution.getOptionId())
                                        .orElseThrow(() -> new ResourceNotFoundException(
                                                        "Question Option", "id",
                                                        distribution.getOptionId()));

                        // Ensure the option belongs to the specified question
                        if (!option.getQuestion().getId().equals(distribution.getQuestionId())) {
                                throw new BadRequestException(String.format(
                                                "Option %s does not belong to question %s",
                                                distribution.getOptionId(),
                                                distribution.getQuestionId()));
                        }
                }
        }

        private FillRequestResponse mapToFillRequestResponse(FillRequest fillRequest,
                        List<AnswerDistribution> distributions) {
                // Group distributions by question
                Map<UUID, Map<UUID, AnswerDistribution>> distributionMap = new HashMap<>();

                // for (AnswerDistribution distribution : distributions) {
                // distributionMap.computeIfAbsent(distribution.getQuestionId(),
                // k -> new HashMap<>())
                // .put(distribution.getOptionId(), distribution);
                // }

                // Fetch option information
                // Set<UUID> optionIds = distributions.stream().map(AnswerDistribution::getQuestion)
                // .collect(Collectors.toSet());

                Map<UUID, QuestionOption> optionMap = optionRepository.findAllById(null).stream()
                                .collect(Collectors.toMap(QuestionOption::getId, option -> option));

                // Create answer distribution responses
                List<FillRequestResponse.AnswerDistributionResponse> distributionResponses =
                                distributions.stream().map(distribution -> {
                                        QuestionOption option = null;
                                        // QuestionOption option =
                                        // optionMap.get(distribution.getOptionId());

                                        return FillRequestResponse.AnswerDistributionResponse
                                                        .builder()
                                                        // .questionId(distribution.getQuestionId())
                                                        // .optionId(distribution.getOptionId())
                                                        // .percentage(distribution.getPercentage())
                                                        // .count(distribution.getCount())
                                                        .option(FillRequestResponse.AnswerDistributionResponse.OptionInfo
                                                                        .builder()
                                                                        .id(option.getId())
                                                                        .text(option.getText())
                                                                        .build())
                                                        .build();
                                }).collect(Collectors.toList());

                // Create fill request response
                return FillRequestResponse.builder().id(fillRequest.getId())
                                // .formId(fillRequest.getFormId())
                                .surveyCount(fillRequest.getSurveyCount())
                                .pricePerSurvey(fillRequest.getPricePerSurvey())
                                .totalPrice(fillRequest.getTotalPrice())
                                .isHumanLike(fillRequest.isHumanLike())
                                .createdAt(fillRequest.getCreatedAt())
                                .status(fillRequest.getStatus())
                                .answerDistributions(distributionResponses).build();
        }
}
