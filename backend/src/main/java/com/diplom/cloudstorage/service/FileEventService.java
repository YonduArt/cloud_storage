package com.diplom.cloudstorage.service;

import com.diplom.cloudstorage.dto.FileEventResponse;
import com.diplom.cloudstorage.model.AppUser;
import com.diplom.cloudstorage.model.FileEvent;
import com.diplom.cloudstorage.repository.FileEventRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileEventService {

    private final FileEventRepository eventRepository;
    private final UserService userService;

    public FileEventService(FileEventRepository eventRepository, UserService userService) {
        this.eventRepository = eventRepository;
        this.userService = userService;
    }

    @Transactional
    public void log(AppUser owner, String action, String targetType, Long targetId, String targetName, String details) {
        FileEvent event = new FileEvent();
        event.setOwner(owner);
        event.setAction(action);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setTargetName(targetName);
        event.setDetails(details);
        eventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<FileEventResponse> listCurrentUserEvents() {
        AppUser owner = userService.requireCurrentUser();
        return eventRepository.findTop100ByOwnerIdOrderByCreatedAtDesc(owner.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private FileEventResponse toResponse(FileEvent event) {
        return new FileEventResponse(
                event.getId(),
                event.getAction(),
                event.getTargetType(),
                event.getTargetId(),
                event.getTargetName(),
                event.getDetails(),
                event.getCreatedAt()
        );
    }
}
