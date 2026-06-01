package com.diplom.cloudstorage.dto;

import java.util.List;

public record StorageStatsResponse(
        Long quotaBytes,
        Long usedBytes,
        Long freeBytes,
        Double usagePercent,
        Long activeBytes,
        Long trashBytes,
        Integer fileCount,
        List<GroupUsageResponse> groups,
        List<FileResponse> largestFiles
) {
    public record GroupUsageResponse(
            String fileGroup,
            Long bytes,
            Integer count
    ) {
    }
}
