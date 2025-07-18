package com.dienform.tool.dienformtudong.fillrequest.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.fillrequest.entity.FillRequestMapping;

@Repository
public interface FillRequestMappingRepository extends JpaRepository<FillRequestMapping, UUID> {

  /**
   * Find all mappings for a specific fill request
   */
  List<FillRequestMapping> findByFillRequestId(UUID fillRequestId);

  /**
   * Delete all mappings for a specific fill request
   */
  void deleteByFillRequestId(UUID fillRequestId);
}
