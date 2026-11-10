package com.homecare.commerce.exception;

import org.springframework.http.HttpStatus;

import com.homecare.common.error.ApiErrorCodeContract;

public enum CommerceErrorCode implements ApiErrorCodeContract {
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "error.order.not-found", "Order not found."),
    ORDER_LINE_NOT_FOUND(HttpStatus.NOT_FOUND, "error.order-line.not-found", "Order line not found."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "error.product.not-found", "Product not found."),
    ORDER_RETURN_NOT_FOUND(HttpStatus.NOT_FOUND, "error.order-return.not-found", "Order return request not found."),
    RETURN_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "error.return-request.not-found", "Return request not found."),
    MIXED_MERCHANT_ORDER(HttpStatus.CONFLICT, "error.order.mixed-merchant", "All order lines in a single order must belong to the same merchant."),
    STOCK_INSUFFICIENT(HttpStatus.CONFLICT, "error.order.insufficient-stock", "Insufficient stock for product."),
    ORDER_INVALID_STATE(HttpStatus.CONFLICT, "error.order.invalid-state", "Order is in an invalid state for this operation."),
    ORDER_QUOTE_EXPIRED(HttpStatus.CONFLICT, "error.order.quote-expired", "Order quote has expired."),
    ORDER_LINE_NOT_DELIVERED(HttpStatus.CONFLICT, "error.order-line.not-delivered", "Only DELIVERED order lines can request a return or exchange."),
    RETURN_QUANTITY_EXCEEDED(HttpStatus.CONFLICT, "error.return.quantity-exceeded", "Requested return quantity exceeds purchased line quantity."),
    RETURN_WINDOW_EXPIRED(HttpStatus.CONFLICT, "error.return.window-expired", "Return window has expired."),
    RETURN_ALREADY_RESOLVED(HttpStatus.CONFLICT, "error.return.already-resolved", "Return request is already resolved.");

    private final HttpStatus status;
    private final String messageKey;
    private final String defaultMessage;

    CommerceErrorCode(HttpStatus status, String messageKey, String defaultMessage) {
        this.status = status;
        this.messageKey = messageKey;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String messageKey() {
        return messageKey;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
