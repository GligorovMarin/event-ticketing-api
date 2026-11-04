package com.portfolio.ticketing.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * One page of a longer list, in a shape that is stable for API clients.
 *
 * <p>We do not return Spring's own {@code Page} object: its JSON shape is an implementation detail of the
 * library and has changed between versions. This record is ours.
 *
 * @param page       zero-based page number
 * @param size       requested page size
 * @param totalItems how many items exist in total (across all pages)
 * @param totalPages how many pages that makes
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    /** Converts a database page of entities into a page of response records. */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
