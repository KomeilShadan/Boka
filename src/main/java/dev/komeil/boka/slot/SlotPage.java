package dev.komeil.boka.slot;

import java.util.List;

public record SlotPage(
    List<SlotResponse> items, int page, int size, long totalElements, int totalPages) {}
