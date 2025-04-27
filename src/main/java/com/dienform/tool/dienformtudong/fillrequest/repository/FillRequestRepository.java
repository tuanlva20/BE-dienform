package com.dienform.tool.dienformtudong.fillrequest.repository;

import com.dienform.tool.dienformtudong.form.entity.Form;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FillRequestRepository extends JpaRepository<FillRequest, UUID> {

  @EntityGraph(attributePaths = {"answerDistributions"})
  @Query("SELECT fr FROM FillRequest fr WHERE fr.form = ?1")
  List<FillRequest> findByForm(Form form);

  @EntityGraph(attributePaths = {"form"})
  @Query("SELECT fr FROM FillRequest fr WHERE fr.id = ?1")
  Optional<FillRequest> findByIdWithFetchForm(UUID id);
}
