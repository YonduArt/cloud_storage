package com.diplom.cloudstorage.mapper;

import com.diplom.cloudstorage.dto.FolderResponse;
import com.diplom.cloudstorage.model.Folder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface FolderMapper {

    @Mapping(target = "parentId", expression = "java(folder.getParent() == null ? null : folder.getParent().getId())")
    FolderResponse toResponse(Folder folder);
}
