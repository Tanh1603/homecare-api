package com.homecare.common.error;

import org.springframework.http.HttpStatus;

public interface ApiErrorCodeContract {

    HttpStatus status();

    String name();

    String messageKey();

    default String defaultMessage() {
        return messageKey();
    }
}
