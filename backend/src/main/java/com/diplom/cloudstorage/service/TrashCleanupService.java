package com.diplom.cloudstorage.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TrashCleanupService {

    private final StorageService storageService;

    public TrashCleanupService(StorageService storageService) {
        this.storageService = storageService;
    }

    @Scheduled(
            initialDelayString = "${app.trash.cleanup.initial-delay-ms:60000}",
            fixedDelayString = "${app.trash.cleanup.fixed-delay-ms:3600000}"
    )
    public void purgeExpiredTrash() {
        storageService.purgeExpiredTrash();
    }
}
