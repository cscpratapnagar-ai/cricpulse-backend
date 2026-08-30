package com.cricket.platform.shared.web;

import java.util.List;

public record PageResponse<T>(
        List<T> content,
        long totalElements,
        int page,
        int size,
        int totalPages
) {}
