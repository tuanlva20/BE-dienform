package com.dienform.tool.dienformtudong.fillrequest.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import com.dienform.tool.dienformtudong.form.entity.Form;
import jakarta.persistence.LockModeType;

@Repository
public interface FillRequestRepository extends JpaRepository<FillRequest, UUID> {

  @EntityGraph(attributePaths = {"answerDistributions"})
  @Query("SELECT fr FROM FillRequest fr WHERE fr.form = ?1")
  List<FillRequest> findByForm(Form form);

  @EntityGraph(attributePaths = {"form"})
  @Query("SELECT fr FROM FillRequest fr WHERE fr.id = ?1")
  Optional<FillRequest> findByIdWithFetchForm(UUID id);

  /**
   * Find FillRequest with all related data loaded to avoid LazyInitializationException
   */
  @EntityGraph(attributePaths = {"form", "answerDistributions", "answerDistributions.question",
      "answerDistributions.option"})
  @Query("SELECT fr FROM FillRequest fr WHERE fr.id = ?1")
  Optional<FillRequest> findByIdWithAllData(UUID id);

  /**
   * Find PENDING campaigns that should start now or in the past
   */
  @EntityGraph(attributePaths = {"form"})
  @Query("SELECT fr FROM FillRequest fr WHERE fr.status = ?1 AND fr.startDate <= ?2")
  List<FillRequest> findByStatusAndStartDateLessThanEqual(String status, LocalDateTime startDate);

  @Query("SELECT fr FROM FillRequest fr WHERE fr.status = ?1 AND fr.startDate < ?2")
  List<FillRequest> findByStatusAndStartDateLessThan(String status, LocalDateTime startDate);

  void deleteByForm(Form form);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Transactional
  @Query("UPDATE FillRequest fr SET fr.completedSurvey = fr.completedSurvey + 1 WHERE fr.id = ?1 AND fr.completedSurvey < fr.surveyCount")
  int incrementCompletedSurvey(UUID id);

  /**
   * Fetch a FillRequest with PESSIMISTIC_WRITE lock for safe concurrent increments.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT fr FROM FillRequest fr WHERE fr.id = ?1")
  Optional<FillRequest> findByIdForUpdate(UUID id);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Transactional
  @Query("UPDATE FillRequest fr SET fr.status = ?2 WHERE fr.id = ?1")
  int updateStatus(UUID id,
      com.dienform.tool.dienformtudong.fillrequest.enums.FillRequestStatusEnum status);
}
