package com.homecare.common.web;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        Pagination pagination) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int safeSize = Math.max(1, size);
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
