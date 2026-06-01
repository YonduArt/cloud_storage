package com.diplom.cloudstorage.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class FileIndexingEventListener {

    private final FileIndexingService indexingService;

    public FileIndexingEventListener(FileIndexingService indexingService) {
        this.indexingService = indexingService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFileUploaded(FileIndexRequestedEvent event) {
        indexingService.indexFile(event.fileId());
    }
}
