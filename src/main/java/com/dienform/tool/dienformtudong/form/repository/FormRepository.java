package com.dienform.tool.dienformtudong.form.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.form.entity.Form;

@Repository
public interface FormRepository extends JpaRepository<Form, UUID> {
    Page<Form> findByNameContainingIgnoreCase(String search, Pageable pageable);

    Page<Form> findByCreatedBy_Id(UUID createdById, Pageable pageable);

    Page<Form> findByCreatedBy_IdAndNameContainingIgnoreCase(UUID createdById, String search,
            Pageable pageable);

    long countByCreatedBy_Id(UUID createdById);

    long countByCreatedBy_IdAndNameStartingWithIgnoreCase(UUID createdById, String namePrefix);

    long countByNameStartingWithIgnoreCase(String namePrefix);

    @EntityGraph(attributePaths = {"formStatistic", "questions.options"})
    @Query("SELECT f FROM Form f WHERE f.id = :id")
    Optional<Form> findByIdWithFetch(UUID id);

    /**
     * Find all forms by created by ID, ordered by created date descending
     * 
     * @param createdById The user ID who created the forms
     * @return List of forms ordered by creation date descending
     */
    java.util.List<Form> findByCreatedBy_IdOrderByCreatedAtDesc(UUID createdById);
}
