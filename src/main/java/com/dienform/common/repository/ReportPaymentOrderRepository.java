package com.dienform.common.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.dienform.common.entity.ReportPaymentOrder;

@Repository
public interface ReportPaymentOrderRepository extends JpaRepository<ReportPaymentOrder, UUID> {

        @Query("SELECT COALESCE(SUM(p.amount), 0) FROM ReportPaymentOrder p "
                        + "WHERE p.paymentType = 'DEPOSIT' AND p.status = 'COMPLETED'")
        BigDecimal getTotalDeposited();

        @Query("SELECT COALESCE(SUM(p.amount), 0) FROM ReportPaymentOrder p "
                        + "WHERE p.paymentType = 'WITHDRAWAL' AND p.status = 'COMPLETED'")
        BigDecimal getTotalSpent();

        @Query("SELECT COALESCE(SUM(p.amount), 0) FROM ReportPaymentOrder p "
                        + "WHERE p.paymentType = 'PROMOTIONAL' AND p.status = 'COMPLETED'")
        BigDecimal getTotalPromotional();

        @Query("SELECT COALESCE(SUM(p.amount), 0) FROM ReportPaymentOrder p "
                        + "WHERE p.paymentType = 'PROMOTIONAL' AND p.status = 'COMPLETED' AND p.user.id = :userId")
        BigDecimal getUserTotalPromotional(@Param("userId") UUID userId);

        @Query("SELECT COUNT(p) > 0 FROM ReportPaymentOrder p "
                        + "WHERE p.user.id = :userId AND p.paymentType = 'PROMOTIONAL' "
                        + "AND p.description LIKE '%Tài khoản mới%'")
        boolean hasUserReceivedNewAccountPromotion(@Param("userId") UUID userId);

        @EntityGraph(attributePaths = {"user"})
        @Query("SELECT p FROM ReportPaymentOrder p "
                        + "WHERE p.user.id = :userId AND p.paymentType = 'PROMOTIONAL' "
                        + "AND p.description LIKE '%Tài khoản mới%' " + "ORDER BY p.createdAt DESC")
        Optional<ReportPaymentOrder> findLatestNewAccountPromotion(@Param("userId") UUID userId);

        @EntityGraph(attributePaths = {"user"})
        @Query("SELECT p FROM ReportPaymentOrder p " + "LEFT JOIN p.user u "
                        + "WHERE (:paymentType IS NULL OR p.paymentType = :paymentType) "
                        + "AND (:status IS NULL OR p.status = :status) "
                        + "AND (:userName IS NULL OR LOWER(u.name) LIKE LOWER(CONCAT('%', :userName, '%'))) "
                        + "AND (:userEmail IS NULL OR LOWER(u.email) LIKE LOWER(CONCAT('%', :userEmail, '%'))) "
                        + "AND (:isPromotional IS NULL OR p.isPromotional = :isPromotional) "
                        + "AND (:isReported IS NULL OR p.isReported = :isReported) "
                        + "AND (:fromDate IS NULL OR p.createdAt >= :fromDate) "
                        + "AND (:toDate IS NULL OR p.createdAt <= :toDate)")
        Page<ReportPaymentOrder> findPaymentOrdersWithFilters(
                        @Param("paymentType") ReportPaymentOrder.PaymentType paymentType,
                        @Param("status") ReportPaymentOrder.PaymentStatus status,
                        @Param("userName") String userName, @Param("userEmail") String userEmail,
                        @Param("isPromotional") Boolean isPromotional,
                        @Param("isReported") Boolean isReported,
                        @Param("fromDate") LocalDateTime fromDate,
                        @Param("toDate") LocalDateTime toDate, Pageable pageable);
}
