package com.diplom.cloudstorage.mapper;

import com.diplom.cloudstorage.dto.FileResponse;
import com.diplom.cloudstorage.model.StoredFile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface FileMapper {

    @Mapping(target = "folderId", expression = "java(file.getFolder() == null ? null : file.getFolder().getId())")
    @Mapping(target = "hasThumbnail", expression = "java(file.getThumbnailPath() != null && !file.getThumbnailPath().isBlank())")
    @Mapping(target = "index", ignore = true)
    FileResponse toResponse(StoredFile file);
}
