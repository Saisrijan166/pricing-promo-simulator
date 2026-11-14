package com.pricingsim.api.dto;

import java.util.List;

/** One page of results; {@code page} is zero-based. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long total) {
        return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
    }
}
