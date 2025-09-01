package com.dienform.tool.dienformtudong.payment.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.dienform.tool.dienformtudong.payment.entity.PaymentOrder;
import com.dienform.tool.dienformtudong.payment.enums.PaymentStatus;

@Repository("sepayPaymentOrderRepository")
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

        Optional<PaymentOrder> findByOrderId(String orderId);

        List<PaymentOrder> findByUserIdAndStatus(String userId, PaymentStatus status);

        @Query("SELECT p FROM PaymentOrder p WHERE p.status = :status AND p.expiresAt < :now")
        List<PaymentOrder> findExpiredOrders(@Param("status") PaymentStatus status,
                        @Param("now") LocalDateTime now);

        @Query("SELECT p FROM PaymentOrder p WHERE p.status = :status AND (p.lastWebhookAttempt IS NULL OR p.lastWebhookAttempt < :threshold)")
        List<PaymentOrder> findPendingOrdersForWebhookCheck(@Param("status") PaymentStatus status,
                        @Param("threshold") LocalDateTime threshold);

        @Query("SELECT COUNT(p) FROM PaymentOrder p WHERE p.userId = :userId AND p.status = :status")
        long countByUserIdAndStatus(@Param("userId") String userId,
                        @Param("status") PaymentStatus status);
}
