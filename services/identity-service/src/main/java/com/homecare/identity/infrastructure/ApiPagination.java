package com.homecare.identity.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.util.MultiValueMap;

/** Endpoint must supply its approved sort/filter allowlist; no SQL field expressions. */
public record ApiPagination(int page, int size, List<Sort> sort) {
    public record Sort(String field, String direction) {}
    public record Result<T>(List<T> items, int page, int size, long totalElements, long totalPages) {}

    public static ApiPagination parse(MultiValueMap<String, String> query, Set<String> sortFields,
            Set<String> filters, String defaultSort) {
        if (query.keySet().stream().anyMatch(k -> !Set.of("page", "size", "sort").contains(k) && !filters.contains(k)))
            throw new ApiErrors.InvalidInput();
        int page = integer(query, "page", 0);
        int size = integer(query, "size", 20);
        if (page < 0 || size < 1 || size > 100) throw new ApiErrors.InvalidInput();
        var sorts = new ArrayList<Sort>();
        for (String value : query.getOrDefault("sort", List.of(defaultSort))) {
            String[] parts = value.split(",", -1);
            if (parts.length != 2 || !sortFields.contains(parts[0])
                    || !Set.of("asc", "desc").contains(parts[1])
                    || sorts.stream().anyMatch(s -> s.field().equals(parts[0]))) throw new ApiErrors.InvalidInput();
            sorts.add(new Sort(parts[0], parts[1]));
        }
        if (sorts.isEmpty()) throw new ApiErrors.InvalidInput();
        if (sorts.stream().noneMatch(s -> s.field().equals("id"))) sorts.add(new Sort("id", sorts.getFirst().direction()));
        return new ApiPagination(page, size, List.copyOf(sorts));
    }

    private static int integer(MultiValueMap<String, String> query, String key, int fallback) {
        if (!query.containsKey(key)) return fallback;
        var values = query.get(key);
        if (values.size() != 1 || !values.getFirst().matches("[0-9]+")) throw new ApiErrors.InvalidInput();
        try { return Integer.parseInt(values.getFirst()); }
        catch (NumberFormatException e) { throw new ApiErrors.InvalidInput(); }
    }
}
