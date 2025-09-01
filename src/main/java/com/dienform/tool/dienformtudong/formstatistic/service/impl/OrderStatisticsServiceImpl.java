package com.dienform.tool.dienformtudong.formstatistic.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import com.dienform.common.util.CurrentUserUtil;
import com.dienform.tool.dienformtudong.formstatistic.dto.OrderStatisticsResponse;
import com.dienform.tool.dienformtudong.formstatistic.repository.OrderStatisticsRepository;
import com.dienform.tool.dienformtudong.formstatistic.service.OrderStatisticsService;
import com.dienform.tool.dienformtudong.payment.entity.PaymentOrder;
import com.dienform.tool.dienformtudong.payment.enums.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderStatisticsServiceImpl implements OrderStatisticsService {

    private final OrderStatisticsRepository orderStatisticsRepository;
    private final CurrentUserUtil currentUserUtil;

    @Override
    public OrderStatisticsResponse getOrderStatistics() {
        String userId = getCurrentUserId();
        log.info("Getting order statistics for current user: {}", userId);

        try {
            // Lấy tất cả orders của user
            List<PaymentOrder> orders =
                    orderStatisticsRepository.findByUserIdOrderByCreatedAtDesc(userId);

            // Tính toán thống kê
            Long totalOrders = orderStatisticsRepository.countByUserId(userId);
            BigDecimal totalAmount = orderStatisticsRepository.sumAmountByUserId(userId);
            BigDecimal totalCompletedAmount =
                    orderStatisticsRepository.sumCompletedAmountByUserId(userId);

            // Đếm theo trạng thái
            Long pendingOrders =
                    orderStatisticsRepository.countByUserIdAndStatus(userId, PaymentStatus.PENDING);
            Long completedOrders = orderStatisticsRepository.countByUserIdAndStatus(userId,
                    PaymentStatus.COMPLETED);
            Long failedOrders =
                    orderStatisticsRepository.countByUserIdAndStatus(userId, PaymentStatus.FAILED);
            Long expiredOrders =
                    orderStatisticsRepository.countByUserIdAndStatus(userId, PaymentStatus.EXPIRED);

            // Chuyển đổi orders thành OrderInfo
            List<OrderStatisticsResponse.OrderInfo> orderInfos =
                    orders.stream().map(this::convertToOrderInfo).collect(Collectors.toList());

            return OrderStatisticsResponse.builder().orders(orderInfos).totalOrders(totalOrders)
                    .totalAmount(totalAmount).totalCompletedAmount(totalCompletedAmount)
                    .pendingOrders(pendingOrders).completedOrders(completedOrders)
                    .failedOrders(failedOrders).expiredOrders(expiredOrders).build();

        } catch (Exception e) {
            log.error("Error getting order statistics for user {}: {}", userId, e.getMessage(), e);
            throw new RuntimeException("Failed to get order statistics", e);
        }
    }

    @Override
    public OrderStatisticsResponse getOrderStatistics(Pageable pageable) {
        String userId = getCurrentUserId();
        log.info("Getting order statistics for current user: {} with pagination", userId);

        try {
            // Lấy orders với pagination
            Page<PaymentOrder> ordersPage =
                    orderStatisticsRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
            List<PaymentOrder> orders = ordersPage.getContent();

            // Tính toán thống kê
            Long totalOrders = orderStatisticsRepository.countByUserId(userId);
            BigDecimal totalAmount = orderStatisticsRepository.sumAmountByUserId(userId);
            BigDecimal totalCompletedAmount =
                    orderStatisticsRepository.sumCompletedAmountByUserId(userId);

            // Đếm theo trạng thái
            Long pendingOrders =
                    orderStatisticsRepository.countByUserIdAndStatus(userId, PaymentStatus.PENDING);
            Long completedOrders = orderStatisticsRepository.countByUserIdAndStatus(userId,
                    PaymentStatus.COMPLETED);
            Long failedOrders =
                    orderStatisticsRepository.countByUserIdAndStatus(userId, PaymentStatus.FAILED);
            Long expiredOrders =
                    orderStatisticsRepository.countByUserIdAndStatus(userId, PaymentStatus.EXPIRED);

            // Chuyển đổi orders thành OrderInfo
            List<OrderStatisticsResponse.OrderInfo> orderInfos =
                    orders.stream().map(this::convertToOrderInfo).collect(Collectors.toList());

            return OrderStatisticsResponse.builder().orders(orderInfos).totalOrders(totalOrders)
                    .totalAmount(totalAmount).totalCompletedAmount(totalCompletedAmount)
                    .pendingOrders(pendingOrders).completedOrders(completedOrders)
                    .failedOrders(failedOrders).expiredOrders(expiredOrders)
                    // Pagination metadata
                    .pageSize(pageable.getPageSize()).pageNumber(pageable.getPageNumber())
                    .totalPages(ordersPage.getTotalPages())
                    .totalElements(ordersPage.getTotalElements()).build();

        } catch (Exception e) {
            log.error("Error getting order statistics for user {}: {}", userId, e.getMessage(), e);
            throw new RuntimeException("Failed to get order statistics", e);
        }
    }

    @Override
    public OrderStatisticsResponse getOrderStatisticsByStatus(String status) {
        String userId = getCurrentUserId();
        log.info("Getting order statistics for current user: {} with status: {}", userId, status);

        try {
            PaymentStatus paymentStatus = PaymentStatus.valueOf(status.toUpperCase());
            List<PaymentOrder> orders = orderStatisticsRepository
                    .findByUserIdAndStatusOrderByCreatedAtDesc(userId, paymentStatus);

            // Tính toán thống kê cho status cụ thể
            Long totalOrders = (long) orders.size();
            BigDecimal totalAmount = orders.stream().map(PaymentOrder::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalCompletedAmount =
                    paymentStatus == PaymentStatus.COMPLETED ? totalAmount : BigDecimal.ZERO;

            // Chuyển đổi orders thành OrderInfo
            List<OrderStatisticsResponse.OrderInfo> orderInfos =
                    orders.stream().map(this::convertToOrderInfo).collect(Collectors.toList());

            return OrderStatisticsResponse.builder().orders(orderInfos).totalOrders(totalOrders)
                    .totalAmount(totalAmount).totalCompletedAmount(totalCompletedAmount)
                    .pendingOrders(paymentStatus == PaymentStatus.PENDING ? totalOrders : 0L)
                    .completedOrders(paymentStatus == PaymentStatus.COMPLETED ? totalOrders : 0L)
                    .failedOrders(paymentStatus == PaymentStatus.FAILED ? totalOrders : 0L)
                    .expiredOrders(paymentStatus == PaymentStatus.EXPIRED ? totalOrders : 0L)
                    .build();

        } catch (IllegalArgumentException e) {
            log.error("Invalid payment status: {}", status);
            throw new RuntimeException("Invalid payment status: " + status, e);
        } catch (Exception e) {
            log.error("Error getting order statistics for user {} with status {}: {}", userId,
                    status, e.getMessage(), e);
            throw new RuntimeException("Failed to get order statistics", e);
        }
    }

    @Override
    public OrderStatisticsResponse getOrderStatisticsByStatus(String status, Pageable pageable) {
        String userId = getCurrentUserId();
        log.info("Getting order statistics for current user: {} with status: {} and pagination",
                userId, status);

        try {
            PaymentStatus paymentStatus = PaymentStatus.valueOf(status.toUpperCase());
            Page<PaymentOrder> ordersPage = orderStatisticsRepository
                    .findByUserIdAndStatusOrderByCreatedAtDesc(userId, paymentStatus, pageable);
            List<PaymentOrder> orders = ordersPage.getContent();

            // Tính toán thống kê cho status cụ thể
            Long totalOrders = (long) orders.size();
            BigDecimal totalAmount = orders.stream().map(PaymentOrder::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalCompletedAmount =
                    paymentStatus == PaymentStatus.COMPLETED ? totalAmount : BigDecimal.ZERO;

            // Chuyển đổi orders thành OrderInfo
            List<OrderStatisticsResponse.OrderInfo> orderInfos =
                    orders.stream().map(this::convertToOrderInfo).collect(Collectors.toList());

            return OrderStatisticsResponse.builder().orders(orderInfos).totalOrders(totalOrders)
                    .totalAmount(totalAmount).totalCompletedAmount(totalCompletedAmount)
                    .pendingOrders(paymentStatus == PaymentStatus.PENDING ? totalOrders : 0L)
                    .completedOrders(paymentStatus == PaymentStatus.COMPLETED ? totalOrders : 0L)
                    .failedOrders(paymentStatus == PaymentStatus.FAILED ? totalOrders : 0L)
                    .expiredOrders(paymentStatus == PaymentStatus.EXPIRED ? totalOrders : 0L)
                    // Pagination metadata
                    .pageSize(pageable.getPageSize()).pageNumber(pageable.getPageNumber())
                    .totalPages(ordersPage.getTotalPages())
                    .totalElements(ordersPage.getTotalElements()).build();

        } catch (IllegalArgumentException e) {
            log.error("Invalid payment status: {}", status);
            throw new RuntimeException("Invalid payment status: " + status, e);
        } catch (Exception e) {
            log.error("Error getting order statistics for user {} with status {}: {}", userId,
                    status, e.getMessage(), e);
            throw new RuntimeException("Failed to get order statistics", e);
        }
    }

    @Override
    public OrderStatisticsResponse getOrderStatisticsByDateRange(String startDate, String endDate) {
        String userId = getCurrentUserId();
        log.info("Getting order statistics for current user: {} from {} to {}", userId, startDate,
                endDate);

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime start = LocalDateTime.parse(startDate, formatter);
            LocalDateTime end = LocalDateTime.parse(endDate, formatter);

            // Lấy tất cả orders và filter theo date range
            List<PaymentOrder> allOrders =
                    orderStatisticsRepository.findByUserIdOrderByCreatedAtDesc(userId);
            List<PaymentOrder> filteredOrders = allOrders.stream().filter(order -> {
                LocalDateTime createdAt = order.getCreatedAt();
                return createdAt != null && !createdAt.isBefore(start) && !createdAt.isAfter(end);
            }).collect(Collectors.toList());

            // Tính toán thống kê
            Long totalOrders = (long) filteredOrders.size();
            BigDecimal totalAmount = filteredOrders.stream().map(PaymentOrder::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalCompletedAmount = filteredOrders.stream()
                    .filter(order -> order.getStatus() == PaymentStatus.COMPLETED)
                    .map(PaymentOrder::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

            // Đếm theo trạng thái
            Long pendingOrders = filteredOrders.stream()
                    .filter(order -> order.getStatus() == PaymentStatus.PENDING).count();
            Long completedOrders = filteredOrders.stream()
                    .filter(order -> order.getStatus() == PaymentStatus.COMPLETED).count();
            Long failedOrders = filteredOrders.stream()
                    .filter(order -> order.getStatus() == PaymentStatus.FAILED).count();
            Long expiredOrders = filteredOrders.stream()
                    .filter(order -> order.getStatus() == PaymentStatus.EXPIRED).count();

            // Chuyển đổi orders thành OrderInfo
            List<OrderStatisticsResponse.OrderInfo> orderInfos = filteredOrders.stream()
                    .map(this::convertToOrderInfo).collect(Collectors.toList());

            return OrderStatisticsResponse.builder().orders(orderInfos).totalOrders(totalOrders)
                    .totalAmount(totalAmount).totalCompletedAmount(totalCompletedAmount)
                    .pendingOrders(pendingOrders).completedOrders(completedOrders)
                    .failedOrders(failedOrders).expiredOrders(expiredOrders).build();

        } catch (Exception e) {
            log.error("Error getting order statistics by date range for user {}: {}", userId,
                    e.getMessage(), e);
            throw new RuntimeException("Failed to get order statistics by date range", e);
        }
    }

    @Override
    public OrderStatisticsResponse getOrderStatisticsByDateRange(String startDate, String endDate,
            Pageable pageable) {
        String userId = getCurrentUserId();
        log.info("Getting order statistics for current user: {} from {} to {} with pagination",
                userId, startDate, endDate);

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime start = LocalDateTime.parse(startDate, formatter);
            LocalDateTime end = LocalDateTime.parse(endDate, formatter);

            // Lấy tất cả orders và filter theo date range
            List<PaymentOrder> allOrders =
                    orderStatisticsRepository.findByUserIdOrderByCreatedAtDesc(userId);
            List<PaymentOrder> filteredOrders = allOrders.stream().filter(order -> {
                LocalDateTime createdAt = order.getCreatedAt();
                return createdAt != null && !createdAt.isBefore(start) && !createdAt.isAfter(end);
            }).collect(Collectors.toList());

            // Áp dụng pagination thủ công cho filtered results
            int startIndex = (int) pageable.getOffset();
            int endIndex = Math.min(startIndex + pageable.getPageSize(), filteredOrders.size());

            if (startIndex >= filteredOrders.size()) {
                // Trang vượt quá số lượng items
                return OrderStatisticsResponse.builder().orders(List.of()).totalOrders(0L)
                        .totalAmount(BigDecimal.ZERO).totalCompletedAmount(BigDecimal.ZERO)
                        .pendingOrders(0L).completedOrders(0L).failedOrders(0L).expiredOrders(0L)
                        .pageSize(pageable.getPageSize()).pageNumber(pageable.getPageNumber())
                        .totalPages(0).totalElements(0L).build();
            }

            List<PaymentOrder> paginatedOrders = filteredOrders.subList(startIndex, endIndex);

            // Tính toán thống kê
            Long totalOrders = (long) filteredOrders.size();
            BigDecimal totalAmount = filteredOrders.stream().map(PaymentOrder::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalCompletedAmount = filteredOrders.stream()
                    .filter(order -> order.getStatus() == PaymentStatus.COMPLETED)
                    .map(PaymentOrder::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

            // Đếm theo trạng thái
            Long pendingOrders = filteredOrders.stream()
                    .filter(order -> order.getStatus() == PaymentStatus.PENDING).count();
            Long completedOrders = filteredOrders.stream()
                    .filter(order -> order.getStatus() == PaymentStatus.COMPLETED).count();
            Long failedOrders = filteredOrders.stream()
                    .filter(order -> order.getStatus() == PaymentStatus.FAILED).count();
            Long expiredOrders = filteredOrders.stream()
                    .filter(order -> order.getStatus() == PaymentStatus.EXPIRED).count();

            // Chuyển đổi orders thành OrderInfo
            List<OrderStatisticsResponse.OrderInfo> orderInfos = paginatedOrders.stream()
                    .map(this::convertToOrderInfo).collect(Collectors.toList());

            // Tính toán total pages
            int totalPages =
                    (int) Math.ceil((double) filteredOrders.size() / pageable.getPageSize());

            return OrderStatisticsResponse.builder().orders(orderInfos).totalOrders(totalOrders)
                    .totalAmount(totalAmount).totalCompletedAmount(totalCompletedAmount)
                    .pendingOrders(pendingOrders).completedOrders(completedOrders)
                    .failedOrders(failedOrders).expiredOrders(expiredOrders)
                    // Pagination metadata
                    .pageSize(pageable.getPageSize()).pageNumber(pageable.getPageNumber())
                    .totalPages(totalPages).totalElements((long) filteredOrders.size()).build();

        } catch (Exception e) {
            log.error("Error getting order statistics by date range for user {}: {}", userId,
                    e.getMessage(), e);
            throw new RuntimeException("Failed to get order statistics by date range", e);
        }
    }

    private String getCurrentUserId() {
        UUID userId = currentUserUtil.requireCurrentUserId();
        return userId.toString();
    }

    private OrderStatisticsResponse.OrderInfo convertToOrderInfo(PaymentOrder order) {
        return OrderStatisticsResponse.OrderInfo.builder().id(order.getId())
                .orderId(order.getOrderId()).amount(order.getAmount())
                .createdAt(order.getCreatedAt()).status(order.getStatus()).build();
    }
}
