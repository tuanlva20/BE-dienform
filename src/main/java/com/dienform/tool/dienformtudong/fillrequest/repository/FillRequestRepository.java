package com.dienform.tool.dienformtudong.fillrequest.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequest;
import java.util.List;
import java.util.UUID;

@Repository
public interface FillRequestRepository extends JpaRepository<FillRequest, UUID> {
    Page<FillRequest> findByFormId(UUID formId, Pageable pageable);
    List<FillRequest> findByFormId(UUID formId);
    List<FillRequest> findByStatus(String status);
}