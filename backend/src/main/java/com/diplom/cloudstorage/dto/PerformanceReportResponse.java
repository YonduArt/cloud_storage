package com.diplom.cloudstorage.dto;

import java.time.Instant;
import java.util.List;

public record PerformanceReportResponse(
        Instant generatedAt,
        String testWindow,
        List<ScenarioMetrics> scenarios,
        List<TimePoint> latencyTimeline,
        List<TimePoint> throughputTimeline,
        List<TimePoint> errorTimeline,
        List<TimePoint> cpuTimeline,
        List<TimePoint> memoryTimeline,
        List<TimePoint> availabilityTimeline,
        List<TimePoint> searchPrecisionTimeline,
        List<TimePoint> searchRecallTimeline,
        Summary summary,
        Quality quality
) {
    public record ScenarioMetrics(
            String scenario,
            int users,
            double rps,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double errorRatePercent
    ) {
    }

    public record TimePoint(
            String label,
            double value
    ) {
    }

    public record Summary(
            double avgCpuPercent,
            double avgMemoryPercent,
            double maxErrorRatePercent,
            double peakRps
    ) {
    }

    public record Quality(
            double availabilityPercent,
            double searchPrecisionPercent,
            double searchRecallPercent,
            int maxConcurrentUsersStable,
            int recoveryAfterSpikeSeconds
    ) {
    }
}
