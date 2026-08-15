package org.bgm.catalogservice.dto;

import org.springframework.data.domain.Page;

import java.util.List;

// A lean wrapper instead of serializing Spring Data's Page directly —
// Page's default JSON shape carries a lot of internal Pageable/Sort
// fields no client here needs; this exposes only what the UI actually
// renders (items + enough to know whether there's a next page).
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
