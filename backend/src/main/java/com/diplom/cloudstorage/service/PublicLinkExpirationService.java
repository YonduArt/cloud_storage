package com.diplom.cloudstorage.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PublicLinkExpirationService {

    private final PublicLinkService publicLinkService;

    public PublicLinkExpirationService(PublicLinkService publicLinkService) {
        this.publicLinkService = publicLinkService;
    }

    @Scheduled(fixedDelayString = "${app.public-links.expiration-scan-ms:600000}")
    public void disableExpiredLinks() {
        publicLinkService.disableExpiredLinks();
    }
}
