package com.diplom.cloudstorage.mapper;

import com.diplom.cloudstorage.dto.UserResponse;
import com.diplom.cloudstorage.model.AppUser;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "role", expression = "java(user.getRole().name())")
    UserResponse toResponse(AppUser user);
}
