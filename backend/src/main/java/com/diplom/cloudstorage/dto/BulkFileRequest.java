package com.diplom.cloudstorage.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record BulkFileRequest(
        @NotEmpty List<Long> ids
) {
}
