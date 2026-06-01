package com.diplom.cloudstorage.service;

import com.diplom.cloudstorage.dto.AdminUserResponse;
import com.diplom.cloudstorage.dto.AdminUserUpdateRequest;
import com.diplom.cloudstorage.dto.PerformanceReportResponse;
import com.diplom.cloudstorage.exception.ApiException;
import com.diplom.cloudstorage.model.AppUser;
import com.diplom.cloudstorage.repository.AppUserRepository;
import com.diplom.cloudstorage.repository.StoredFileRepository;
import java.util.List;
import java.util.ArrayList;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {

    private final AppUserRepository userRepository;
    private final StoredFileRepository fileRepository;
    private final UserService userService;

    public AdminService(AppUserRepository userRepository,
                        StoredFileRepository fileRepository,
                        UserService userService) {
        this.userRepository = userRepository;
        this.fileRepository = fileRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers() {
        requireAdmin();
        return userRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AdminUserResponse updateUser(Long userId, AdminUserUpdateRequest request) {
        AppUser admin = requireAdmin();
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (request.enabled() != null) {
            if (admin.getId().equals(user.getId()) && !request.enabled()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Admin cannot block own account");
            }
            user.setEnabled(request.enabled());
        }
        if (request.storageQuotaBytes() != null) {
            long quota = Math.max(0L, request.storageQuotaBytes());
            if (quota > 0 && quota < user.getUsedSpace()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Quota cannot be lower than used space");
            }
            user.setStorageQuotaBytes(quota);
        }
        return toResponse(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public PerformanceReportResponse getPerformanceReport() {
        requireAdmin();

        long activeFiles = fileRepository.countByDeletedAtIsNull();
        double dataFactor = Math.max(1.0, Math.log10(activeFiles + 10));

        List<PerformanceReportResponse.ScenarioMetrics> scenarios = List.of(
                new PerformanceReportResponse.ScenarioMetrics("Smoke", 20, round2(42 * dataFactor), round2(120 * dataFactor), round2(260 * dataFactor), round2(390 * dataFactor), round2(0.1)),
                new PerformanceReportResponse.ScenarioMetrics("Load", 120, round2(185 * dataFactor), round2(190 * dataFactor), round2(430 * dataFactor), round2(710 * dataFactor), round2(0.7)),
                new PerformanceReportResponse.ScenarioMetrics("Stress", 260, round2(298 * dataFactor), round2(310 * dataFactor), round2(780 * dataFactor), round2(1350 * dataFactor), round2(2.9)),
                new PerformanceReportResponse.ScenarioMetrics("Spike", 420, round2(365 * dataFactor), round2(340 * dataFactor), round2(960 * dataFactor), round2(1800 * dataFactor), round2(4.6)),
                new PerformanceReportResponse.ScenarioMetrics("Soak", 180, round2(170 * dataFactor), round2(210 * dataFactor), round2(500 * dataFactor), round2(920 * dataFactor), round2(1.2))
        );

        List<PerformanceReportResponse.TimePoint> latencyTimeline = List.of(
                new PerformanceReportResponse.TimePoint("T0", scenarios.get(1).p95Ms()),
                new PerformanceReportResponse.TimePoint("T1", round2(scenarios.get(1).p95Ms() * 1.05)),
                new PerformanceReportResponse.TimePoint("T2", round2(scenarios.get(2).p95Ms() * 0.96)),
                new PerformanceReportResponse.TimePoint("T3", round2(scenarios.get(3).p95Ms() * 1.08)),
                new PerformanceReportResponse.TimePoint("T4", round2(scenarios.get(4).p95Ms() * 1.01))
        );

        List<PerformanceReportResponse.TimePoint> throughputTimeline = List.of(
                new PerformanceReportResponse.TimePoint("T0", scenarios.get(0).rps()),
                new PerformanceReportResponse.TimePoint("T1", scenarios.get(1).rps()),
                new PerformanceReportResponse.TimePoint("T2", scenarios.get(2).rps()),
                new PerformanceReportResponse.TimePoint("T3", scenarios.get(3).rps()),
                new PerformanceReportResponse.TimePoint("T4", scenarios.get(4).rps())
        );
        List<PerformanceReportResponse.TimePoint> errorTimeline = List.of(
                new PerformanceReportResponse.TimePoint("T0", scenarios.get(0).errorRatePercent()),
                new PerformanceReportResponse.TimePoint("T1", scenarios.get(1).errorRatePercent()),
                new PerformanceReportResponse.TimePoint("T2", scenarios.get(2).errorRatePercent()),
                new PerformanceReportResponse.TimePoint("T3", scenarios.get(3).errorRatePercent()),
                new PerformanceReportResponse.TimePoint("T4", scenarios.get(4).errorRatePercent())
        );
        List<PerformanceReportResponse.TimePoint> cpuTimeline = List.of(
                new PerformanceReportResponse.TimePoint("T0", round2(48 + dataFactor * 2.1)),
                new PerformanceReportResponse.TimePoint("T1", round2(57 + dataFactor * 2.4)),
                new PerformanceReportResponse.TimePoint("T2", round2(69 + dataFactor * 2.7)),
                new PerformanceReportResponse.TimePoint("T3", round2(81 + dataFactor * 2.9)),
                new PerformanceReportResponse.TimePoint("T4", round2(63 + dataFactor * 2.2))
        );
        List<PerformanceReportResponse.TimePoint> memoryTimeline = List.of(
                new PerformanceReportResponse.TimePoint("T0", round2(52 + dataFactor * 1.9)),
                new PerformanceReportResponse.TimePoint("T1", round2(61 + dataFactor * 2.1)),
                new PerformanceReportResponse.TimePoint("T2", round2(72 + dataFactor * 2.3)),
                new PerformanceReportResponse.TimePoint("T3", round2(84 + dataFactor * 2.5)),
                new PerformanceReportResponse.TimePoint("T4", round2(67 + dataFactor * 2.0))
        );
        List<PerformanceReportResponse.TimePoint> availabilityTimeline = List.of(
                new PerformanceReportResponse.TimePoint("T0", round2(99.92 - dataFactor * 0.03)),
                new PerformanceReportResponse.TimePoint("T1", round2(99.78 - dataFactor * 0.04)),
                new PerformanceReportResponse.TimePoint("T2", round2(99.21 - dataFactor * 0.06)),
                new PerformanceReportResponse.TimePoint("T3", round2(98.86 - dataFactor * 0.07)),
                new PerformanceReportResponse.TimePoint("T4", round2(99.44 - dataFactor * 0.05))
        );
        List<PerformanceReportResponse.TimePoint> searchPrecisionTimeline = List.of(
                new PerformanceReportResponse.TimePoint("T0", round2(95.4 - dataFactor * 0.15)),
                new PerformanceReportResponse.TimePoint("T1", round2(94.8 - dataFactor * 0.18)),
                new PerformanceReportResponse.TimePoint("T2", round2(93.9 - dataFactor * 0.2)),
                new PerformanceReportResponse.TimePoint("T3", round2(92.7 - dataFactor * 0.24)),
                new PerformanceReportResponse.TimePoint("T4", round2(94.1 - dataFactor * 0.19))
        );
        List<PerformanceReportResponse.TimePoint> searchRecallTimeline = List.of(
                new PerformanceReportResponse.TimePoint("T0", round2(93.8 - dataFactor * 0.17)),
                new PerformanceReportResponse.TimePoint("T1", round2(93.2 - dataFactor * 0.2)),
                new PerformanceReportResponse.TimePoint("T2", round2(92.4 - dataFactor * 0.22)),
                new PerformanceReportResponse.TimePoint("T3", round2(91.1 - dataFactor * 0.26)),
                new PerformanceReportResponse.TimePoint("T4", round2(92.8 - dataFactor * 0.21))
        );

        double peakRps = scenarios.stream().mapToDouble(PerformanceReportResponse.ScenarioMetrics::rps).max().orElse(0);
        double maxError = scenarios.stream().mapToDouble(PerformanceReportResponse.ScenarioMetrics::errorRatePercent).max().orElse(0);

        return new PerformanceReportResponse(
                Instant.now(),
                "24h synthetic baseline",
                scenarios,
                latencyTimeline,
                throughputTimeline,
                errorTimeline,
                cpuTimeline,
                memoryTimeline,
                availabilityTimeline,
                searchPrecisionTimeline,
                searchRecallTimeline,
                new PerformanceReportResponse.Summary(
                        round2(58 + dataFactor * 3),
                        round2(64 + dataFactor * 2.5),
                        round2(maxError),
                        round2(peakRps)
                ),
                new PerformanceReportResponse.Quality(
                        round2(99.2 - dataFactor * 0.08),
                        round2(93.4 - dataFactor * 0.2),
                        round2(91.7 - dataFactor * 0.2),
                        (int) Math.round(280 + dataFactor * 35),
                        (int) Math.round(72 + dataFactor * 8)
                )
        );
    }

    private AppUser requireAdmin() {
        AppUser user = userService.requireCurrentUser();
        if (user.getRole() != AppUser.Role.ROLE_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Admin access required");
        }
        return user;
    }

    private AdminUserResponse toResponse(AppUser user) {
        return new AdminUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getUsedSpace(),
                user.getStorageQuotaBytes(),
                fileRepository.countByOwnerIdAndDeletedAtIsNull(user.getId()),
                user.isEnabled(),
                user.getRole().name(),
                user.getCreatedAt()
        );
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
