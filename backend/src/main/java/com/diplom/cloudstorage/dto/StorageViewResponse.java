package com.diplom.cloudstorage.dto;

import java.util.List;

public record StorageViewResponse(
        List<FolderResponse> folders,
        List<FileResponse> files
) {
}
