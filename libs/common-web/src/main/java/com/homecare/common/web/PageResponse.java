package com.homecare.common.web;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        Pagination pagination) {

    public static final String DEFAULT_PAGE_STRING = "1";
    public static final String DEFAULT_SIZE_STRING = "20";
    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int totalPages = (int) Math.ceil((double) totalItems / safeSize);
        return new PageResponse<>(items, new Pagination(page, safeSize, totalItems, totalPages));
    }

    public record Pagination(
            int page,
            int size,
            long totalItems,
            int totalPages) {
    }
}
