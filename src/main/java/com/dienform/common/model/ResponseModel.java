package com.dienform.common.model;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * response model for API responses
 * 
 * @param <T> Type of content data
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResponseModel<T> {
    public static <T> ResponseModel<List<T>> success(List<T> data, Integer pageCount, Integer page,
            Integer size, Long total, HttpStatus status) {
        return ResponseModel.<List<T>>builder().status(status).content(data).pageSize(size)
                .pageNumber(page).totalPages(pageCount).totalElements(total).build();
    }

    public static <T> ResponseModel<T> success(T data, HttpStatus status) {
        return ResponseModel.<T>builder().status(status).content(data).build();
    }

    public static <T> ResponseModel<T> error(String message, HttpStatus status) {
        return ResponseModel.<T>builder().status(status).errorCode(500).errorMessage(message)
                .build();
    }

    public static <T> ResponseModel<T> error(String message, HttpStatus status, ErrorCode code) {
        return ResponseModel.<T>builder().status(status)
                .errorCode(code != null ? code.getCode() : null).errorMessage(message).build();
    }

    public static <T> ResponseModel<T> error(String message, HttpStatus status, ErrorCode code,
            Map<String, Object> details) {
        return ResponseModel.<T>builder().status(status)
                .errorCode(code != null ? code.getCode() : null).errorMessage(message)
                .errorDetails(details).build();
    }

    private HttpStatus status;
    private T content;
    private Integer pageSize;
    private Integer pageNumber;

    private Integer totalPages;

    private Long totalElements;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer errorCode;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String errorMessage;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> errorDetails;
}
