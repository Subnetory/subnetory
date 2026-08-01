package dev.subnetory.dto;

import java.util.List;

public record BulkUpsertResponse(
        int created,
        int updated,
        int skipped,
        List<BulkUpsertError> errors
) {
    public record BulkUpsertError(
            String address,
            String reason
    ) {}
}
