package com.bohouse.pacemeter.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SubmissionParityQualityService {

    private static final Path DEFAULT_SUBMISSIONS_ROOT = Path.of("data", "submissions");
    private static final double OUTLIER_THRESHOLD = 0.05;
    private static final double TARGET_P95_APE = 0.03;
    private static final double TARGET_MAX_APE = 0.10;
    private static final double TARGET_OUTLIER_RATIO = 0.05;
    private static final int WORST_ACTOR_LIMIT = 20;
    private final SubmissionParityReportService submissionParityReportService;
    private final Path submissionsRoot;

    @Autowired
    public SubmissionParityQualityService(SubmissionParityReportService submissionParityReportService) {
        this(submissionParityReportService, DEFAULT_SUBMISSIONS_ROOT);
    }

    SubmissionParityQualityService(
            SubmissionParityReportService submissionParityReportService,
            Path submissionsRoot
    ) {
        this.submissionParityReportService = submissionParityReportService;
        this.submissionsRoot = submissionsRoot;
    }

    public SubmissionParityQualityRollup buildRollup() throws IOException {
        if (!Files.exists(submissionsRoot)) {
            return new SubmissionParityQualityRollup(
                    Instant.now().toString(),
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    0.0,
                    0.0,
                    0,
                    0.0,
                    0.0,
                    0.0,
                    new ParityGate(
                            TARGET_P95_APE,
                            TARGET_MAX_APE,
                            TARGET_OUTLIER_RATIO,
                            0.0,
                            0.0,
                            0.0,
                            true
                    ),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        List<Path> submissionDirs = Files.list(submissionsRoot)
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();

        List<SubmissionQualityEntry> entries = new ArrayList<>();
        List<SubmissionQualityFailure> failures = new ArrayList<>();
        List<Double> actorApes = new ArrayList<>();
        List<ActorQualityEntry> actorEntries = new ArrayList<>();

        for (Path submissionDir : submissionDirs) {
            String submissionId = submissionDir.getFileName().toString();
            try {
                SubmissionParityReport report = submissionParityReportService.buildReport(submissionId);
                SubmissionParityReport.ParityQualitySummary quality = report.parityQuality();
                entries.add(new SubmissionQualityEntry(
                        submissionId,
                        report.fflogs().status(),
                        report.fflogs().selectedFightId(),
                        quality.matchedActorCount(),
                        quality.meanAbsolutePercentageError(),
                        quality.p95AbsolutePercentageError(),
                        quality.maxAbsolutePercentageError(),
                        quality.outlierActorCount(),
                        quality.withinOnePercentRatio(),
                        quality.withinThreePercentRatio(),
                        quality.withinFivePercentRatio()
                ));

                actorApes.addAll(report.comparisons().stream()
                        .map(comparison -> {
                            double baseline = Math.max(Math.abs(comparison.fflogsRdpsPerSecond()), 1.0);
                            return Math.abs(comparison.rdpsDelta()) / baseline;
                        })
                        .collect(Collectors.toList()));
                actorEntries.addAll(report.comparisons().stream()
                        .map(comparison -> {
                            double baseline = Math.max(Math.abs(comparison.fflogsRdpsPerSecond()), 1.0);
                            double absolutePercentageError = Math.abs(comparison.rdpsDelta()) / baseline;
                            return new ActorQualityEntry(
                                    submissionId,
                                    comparison.localName(),
                                    comparison.fflogsType(),
                                    absolutePercentageError,
                                    comparison.rdpsDelta(),
                                    comparison.fflogsRdpsPerSecond()
                            );
                        })
                        .collect(Collectors.toList()));
            } catch (Exception e) {
                failures.add(new SubmissionQualityFailure(submissionId, e.getClass().getSimpleName(), e.getMessage()));
            }
        }

        actorApes.sort(Double::compareTo);
        int actorCount = actorApes.size();
        double mean = actorCount == 0
                ? 0.0
                : actorApes.stream().mapToDouble(Double::doubleValue).sum() / actorCount;
        double p95 = percentile(actorApes, 0.95);
        double max = actorCount == 0 ? 0.0 : actorApes.get(actorCount - 1);

        int withinOne = 0;
        int withinThree = 0;
        int withinFive = 0;
        int outliers = 0;
        for (double ape : actorApes) {
            if (ape <= 0.01) {
                withinOne++;
            }
            if (ape <= 0.03) {
                withinThree++;
            }
            if (ape <= 0.05) {
                withinFive++;
            } else if (ape > OUTLIER_THRESHOLD) {
                outliers++;
            }
        }

        double outlierRatio = ratio(outliers, actorCount);
        ParityGate gate = new ParityGate(
                TARGET_P95_APE,
                TARGET_MAX_APE,
                TARGET_OUTLIER_RATIO,
                p95,
                max,
                outlierRatio,
                p95 <= TARGET_P95_APE && max <= TARGET_MAX_APE && outlierRatio <= TARGET_OUTLIER_RATIO
        );

        List<ActorQualityEntry> worstActors = actorEntries.stream()
                .sorted(Comparator.comparingDouble(ActorQualityEntry::absolutePercentageError).reversed())
                .limit(WORST_ACTOR_LIMIT)
                .toList();
        List<JobQualityEntry> jobs = buildJobQualityEntries(actorEntries);

        return new SubmissionParityQualityRollup(
                Instant.now().toString(),
                submissionDirs.size(),
                entries.size(),
                failures.size(),
                actorCount,
                mean,
                p95,
                max,
                outliers,
                ratio(withinOne, actorCount),
                ratio(withinThree, actorCount),
                ratio(withinFive, actorCount),
                gate,
                worstActors,
                jobs,
                entries,
                failures
        );
    }

    private List<JobQualityEntry> buildJobQualityEntries(List<ActorQualityEntry> actorEntries) {
        Map<String, List<ActorQualityEntry>> byJob = new HashMap<>();
        for (ActorQualityEntry actorEntry : actorEntries) {
            String job = actorEntry.actorType() == null || actorEntry.actorType().isBlank()
                    ? "UNKNOWN"
                    : actorEntry.actorType();
            byJob.computeIfAbsent(job, ignored -> new ArrayList<>()).add(actorEntry);
        }

        List<JobQualityEntry> jobs = new ArrayList<>();
        for (Map.Entry<String, List<ActorQualityEntry>> jobEntry : byJob.entrySet()) {
            String job = jobEntry.getKey();
            List<ActorQualityEntry> entries = jobEntry.getValue();
            List<Double> apes = entries.stream()
                    .map(ActorQualityEntry::absolutePercentageError)
                    .sorted()
                    .toList();

            int count = apes.size();
            if (count == 0) {
                continue;
            }

            double mean = apes.stream().mapToDouble(Double::doubleValue).sum() / count;
            double p95 = percentile(apes, 0.95);
            double max = apes.get(count - 1);
            int outliers = 0;
            int withinOne = 0;
            int withinThree = 0;
            int withinFive = 0;
            for (double ape : apes) {
                if (ape <= 0.01) {
                    withinOne++;
                }
                if (ape <= 0.03) {
                    withinThree++;
                }
                if (ape <= 0.05) {
                    withinFive++;
                } else if (ape > OUTLIER_THRESHOLD) {
                    outliers++;
                }
            }
            double outlierRatio = ratio(outliers, count);
            jobs.add(new JobQualityEntry(
                    job,
                    count,
                    mean,
                    p95,
                    max,
                    outliers,
                    ratio(withinOne, count),
                    ratio(withinThree, count),
                    ratio(withinFive, count),
                    p95 <= TARGET_P95_APE && max <= TARGET_MAX_APE && outlierRatio <= TARGET_OUTLIER_RATIO
            ));
        }

        jobs.sort(Comparator.comparingDouble(JobQualityEntry::meanAbsolutePercentageError).reversed());
        return jobs;
    }

    private static double ratio(int part, int total) {
        if (total <= 0) {
            return 0.0;
        }
        return (double) part / total;
    }

    private static double percentile(List<Double> sortedValues, double ratio) {
        if (sortedValues.isEmpty()) {
            return 0.0;
        }
        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }

        double boundedRatio = Math.min(Math.max(ratio, 0.0), 1.0);
        double index = boundedRatio * (sortedValues.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sortedValues.get(lower);
        }
        double weight = index - lower;
        return sortedValues.get(lower) * (1.0 - weight) + sortedValues.get(upper) * weight;
    }

    public record SubmissionParityQualityRollup(
            String generatedAt,
            int submissionsScanned,
            int submissionsEvaluated,
            int submissionsFailed,
            int actorComparisons,
            double meanAbsolutePercentageError,
            double p95AbsolutePercentageError,
            double maxAbsolutePercentageError,
            int outlierActorCount,
            double withinOnePercentRatio,
            double withinThreePercentRatio,
            double withinFivePercentRatio,
            ParityGate gate,
            List<ActorQualityEntry> worstActors,
            List<JobQualityEntry> jobs,
            List<SubmissionQualityEntry> submissions,
            List<SubmissionQualityFailure> failures
    ) {
    }

    public record ParityGate(
            double targetP95AbsolutePercentageError,
            double targetMaxAbsolutePercentageError,
            double targetOutlierRatio,
            double actualP95AbsolutePercentageError,
            double actualMaxAbsolutePercentageError,
            double actualOutlierRatio,
            boolean pass
    ) {
    }

    public record ActorQualityEntry(
            String submissionId,
            String actorName,
            String actorType,
            double absolutePercentageError,
            double rdpsDelta,
            double fflogsRdpsPerSecond
    ) {
    }

    public record JobQualityEntry(
            String job,
            int actorCount,
            double meanAbsolutePercentageError,
            double p95AbsolutePercentageError,
            double maxAbsolutePercentageError,
            int outlierActorCount,
            double withinOnePercentRatio,
            double withinThreePercentRatio,
            double withinFivePercentRatio,
            boolean pass
    ) {
    }

    public record SubmissionQualityEntry(
            String submissionId,
            String fflogsStatus,
            Integer selectedFightId,
            int matchedActorCount,
            double meanAbsolutePercentageError,
            double p95AbsolutePercentageError,
            double maxAbsolutePercentageError,
            int outlierActorCount,
            double withinOnePercentRatio,
            double withinThreePercentRatio,
            double withinFivePercentRatio
    ) {
    }

    public record SubmissionQualityFailure(
            String submissionId,
            String errorType,
            String message
    ) {
    }
}
