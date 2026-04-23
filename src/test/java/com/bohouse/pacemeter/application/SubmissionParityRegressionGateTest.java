package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsApiClient;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsTokenStore;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Properties;
import java.util.Optional;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionParityRegressionGateTest {

    private static final ParityFixtureCatalog.Config FIXTURES =
            ParityFixtureCatalog.loadDefault(new ObjectMapper());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Properties APPLICATION_YAML = loadApplicationYaml();

    @Test
    void parityQualityRollup_staysWithinRegressionGate() throws Exception {
        SubmissionParityReportService reportService = buildConfiguredService();
        SubmissionParityQualityService qualityService = new SubmissionParityQualityService(reportService);
        SubmissionParityQualityService.SubmissionParityQualityRollup rollup = qualityService.buildRollup();

        assertTrue(
                rollup.p95AbsolutePercentageError() <= FIXTURES.globalGate().maxP95Ape(),
                "p95 APE gate failed: " + rollup.p95AbsolutePercentageError()
        );
        assertTrue(
                rollup.maxAbsolutePercentageError() <= FIXTURES.globalGate().maxActorApe(),
                "max actor APE gate failed: " + rollup.maxAbsolutePercentageError()
        );
        assertTrue(
                rollup.gate().actualOutlierRatio() <= FIXTURES.globalGate().maxOutlierRatio(),
                "outlier ratio gate failed: " + rollup.gate().actualOutlierRatio()
        );

        var bySubmissionId = rollup.submissions().stream()
                .collect(Collectors.toMap(
                        SubmissionParityQualityService.SubmissionQualityEntry::submissionId,
                        Function.identity()
                ));

        for (ParityFixtureCatalog.SubmissionGate gate : FIXTURES.submissionGates()) {
            assertSubmissionQuality(bySubmissionId, gate);
        }
        for (ParityFixtureCatalog.ContentGroupGate gate : FIXTURES.contentGroupGates()) {
            assertContentGroupQuality(bySubmissionId, gate);
        }
    }

    @Test
    void allFightsMeaningfulFights_stayWithinGeneralizationGate() throws Exception {
        SubmissionParityReportService reportService = buildConfiguredService();

        for (ParityFixtureCatalog.AllFightsGate gate : FIXTURES.allFightsGates()) {
            SubmissionParityReport baseline = reportService.buildReport(gate.submissionId());

            for (SubmissionParityReport.FflogsFightSummary fight : baseline.fflogs().fights()) {
                if (fight.encounterId() <= 0 || fight.name() == null || fight.name().isBlank()
                        || "Unknown".equalsIgnoreCase(fight.name())) {
                    continue;
                }
                SubmissionParityReport report = reportService.buildReportForFight(gate.submissionId(), fight.id());
                SubmissionParityReport.ParityQualitySummary quality = report.parityQuality();
                assertTrue(
                        quality.matchedActorCount() >= gate.minMatchedActors(),
                        "all-fights matched actor count failed for " + gate.submissionId()
                                + " fight " + fight.id() + ": " + quality.matchedActorCount()
                );
                assertTrue(
                        quality.p95AbsolutePercentageError() <= gate.maxP95Ape(),
                        "all-fights p95 gate failed for " + gate.submissionId()
                                + " fight " + fight.id() + ": " + quality.p95AbsolutePercentageError()
                );
                assertTrue(
                        quality.maxAbsolutePercentageError() <= gate.maxActorApe(),
                        "all-fights max gate failed for " + gate.submissionId()
                                + " fight " + fight.id() + ": " + quality.maxAbsolutePercentageError()
                );
            }
        }
    }

    @Test
    void fixtureSubmissionContentGroup_matchesSubmissionMetadataDifficulty() throws Exception {
        for (ParityFixtureCatalog.SubmissionGate gate : FIXTURES.submissionGates()) {
            String difficulty = loadSubmissionDifficulty(gate.submissionId());
            assertTrue(
                    gate.contentGroup().equalsIgnoreCase(difficulty),
                    "fixture contentGroup mismatch for " + gate.submissionId()
                            + ": fixture=" + gate.contentGroup()
                            + " metadataDifficulty=" + difficulty
            );
        }
    }

    @Test
    void fixtureCoverage_includesAllLocalFflogsSubmissionsByDifficulty() throws Exception {
        Set<String> fixtureSubmissionIds = new HashSet<>();
        fixtureSubmissionIds.addAll(FIXTURES.submissionGates().stream()
                .map(ParityFixtureCatalog.SubmissionGate::submissionId)
                .toList());
        fixtureSubmissionIds.addAll(FIXTURES.allFightsGates().stream()
                .map(ParityFixtureCatalog.AllFightsGate::submissionId)
                .toList());
        Set<String> trackedGroups = FIXTURES.contentGroupGates().stream()
                .map(gate -> gate.contentGroup().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<LocalSubmissionMetadata> localCandidates = loadLocalSubmissionMetadata().stream()
                .filter(metadata -> trackedGroups.contains(metadata.difficulty().toLowerCase(Locale.ROOT)))
                .filter(metadata -> metadata.hasFflogsReportUrl())
                .toList();

        List<String> missing = localCandidates.stream()
                .map(LocalSubmissionMetadata::submissionId)
                .filter(submissionId -> !fixtureSubmissionIds.contains(submissionId))
                .sorted()
                .toList();
        assertTrue(
                missing.isEmpty(),
                "fixture submissionGates is missing local FFLogs submissions: " + missing
        );
    }

    @Test
    void optionalContentGroupGate_isNotLeftOptionalWhenLocalFflogsDataExists() throws Exception {
        List<LocalSubmissionMetadata> localCandidates = loadLocalSubmissionMetadata();
        Set<String> fixtureGroups = FIXTURES.submissionGates().stream()
                .map(ParityFixtureCatalog.SubmissionGate::contentGroup)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        for (ParityFixtureCatalog.ContentGroupGate gate : FIXTURES.contentGroupGates()) {
            if (!gate.optional()) {
                continue;
            }
            String group = gate.contentGroup().toLowerCase(Locale.ROOT);
            boolean hasLocalFflogsData = localCandidates.stream()
                    .anyMatch(metadata ->
                            metadata.difficulty().equalsIgnoreCase(group) && metadata.hasFflogsReportUrl()
                    );
            if (!hasLocalFflogsData) {
                continue;
            }
            assertTrue(
                    fixtureGroups.contains(group),
                    "content group has local FFLogs data but no fixture submission gate: " + gate.contentGroup()
            );
            assertTrue(
                    false,
                    "content group has local FFLogs data but is still optional: "
                            + gate.contentGroup()
                            + " (set optional=false)"
            );
        }
    }

    private static void assertSubmissionQuality(
            Map<String, SubmissionParityQualityService.SubmissionQualityEntry> bySubmissionId,
            ParityFixtureCatalog.SubmissionGate gate
    ) {
        SubmissionParityQualityService.SubmissionQualityEntry entry = bySubmissionId.get(gate.submissionId());
        assertTrue(entry != null, "missing submission quality entry: " + gate.submissionId());
        if (isExternalFflogsFailure(entry)) {
            return;
        }
        assertTrue(
                entry.matchedActorCount() >= gate.minMatchedActors(),
                "matched actor count gate failed for " + gate.submissionId() + ": " + entry.matchedActorCount()
        );
        assertTrue(
                entry.p95AbsolutePercentageError() <= gate.maxP95Ape(),
                "submission p95 gate failed for " + gate.submissionId() + ": " + entry.p95AbsolutePercentageError()
        );
        assertTrue(
                entry.maxAbsolutePercentageError() <= gate.maxActorApe(),
                "submission max actor gate failed for " + gate.submissionId() + ": " + entry.maxAbsolutePercentageError()
        );
    }

    private static boolean isExternalFflogsFailure(SubmissionParityQualityService.SubmissionQualityEntry entry) {
        return "fetch_failed".equalsIgnoreCase(entry.fflogsStatus());
    }

    private static void assertContentGroupQuality(
            Map<String, SubmissionParityQualityService.SubmissionQualityEntry> bySubmissionId,
            ParityFixtureCatalog.ContentGroupGate gate
    ) {
        List<ParityFixtureCatalog.SubmissionGate> submissionsInGroup = FIXTURES.submissionGates().stream()
                .filter(submissionGate -> gate.contentGroup().equals(submissionGate.contentGroup()))
                .toList();
        if (submissionsInGroup.isEmpty() && gate.optional()) {
            return;
        }

        List<SubmissionParityQualityService.SubmissionQualityEntry> evaluated = new ArrayList<>();
        for (ParityFixtureCatalog.SubmissionGate submissionGate : submissionsInGroup) {
            SubmissionParityQualityService.SubmissionQualityEntry entry =
                    bySubmissionId.get(submissionGate.submissionId());
            assertTrue(
                    entry != null,
                    "missing submission quality entry for content group "
                            + gate.contentGroup() + ": " + submissionGate.submissionId()
            );
            if (!isExternalFflogsFailure(entry)) {
                evaluated.add(entry);
            }
        }
        if (evaluated.isEmpty() && gate.optional()) {
            return;
        }

        assertTrue(
                evaluated.size() >= gate.minEvaluatedSubmissions(),
                "content group has insufficient evaluated submissions: group=" + gate.contentGroup()
                        + " evaluated=" + evaluated.size()
                        + " required=" + gate.minEvaluatedSubmissions()
        );

        double worstP95 = 0.0;
        double worstActor = 0.0;
        for (SubmissionParityQualityService.SubmissionQualityEntry entry : evaluated) {
            worstP95 = Math.max(worstP95, entry.p95AbsolutePercentageError());
            worstActor = Math.max(worstActor, entry.maxAbsolutePercentageError());
        }

        assertTrue(
                worstP95 <= gate.maxP95Ape(),
                "content group p95 gate failed: group=" + gate.contentGroup() + " worstP95=" + worstP95
        );
        assertTrue(
                worstActor <= gate.maxActorApe(),
                "content group max actor gate failed: group=" + gate.contentGroup() + " worstActor=" + worstActor
        );
    }

    private SubmissionParityReportService buildConfiguredService() {
        ObjectMapper objectMapper = new ObjectMapper();
        FflogsApiClient apiClient = buildConfiguredApiClient(objectMapper);
        return new SubmissionParityReportService(
                new ActLineParser(),
                objectMapper,
                new FflogsZoneLookup(objectMapper),
                territoryId -> Optional.empty(),
                apiClient
        );
    }

    private FflogsApiClient buildConfiguredApiClient(ObjectMapper objectMapper) {
        String clientId = envOrProperty("PACE_FFLOGS_CLIENT_ID", "pacemeter.fflogs.client-id", "pace.fflogs.clientId");
        String clientSecret = envOrProperty("PACE_FFLOGS_CLIENT_SECRET", "pacemeter.fflogs.client-secret", "pace.fflogs.clientSecret");
        Assumptions.assumeTrue(
                clientId != null && !clientId.isBlank()
                        && clientSecret != null && !clientSecret.isBlank(),
                "FFLogs credentials are required for parity regression gate test"
        );
        FflogsTokenStore tokenStore = new FflogsTokenStore(objectMapper);
        setField(tokenStore, "clientId", clientId);
        setField(tokenStore, "clientSecret", clientSecret);
        FflogsApiClient apiClient = new FflogsApiClient(tokenStore, objectMapper);
        setField(apiClient, "defaultPartition", envOrProperty("PACE_FFLOGS_PARTITION", "pacemeter.fflogs.partition", "pace.fflogs.partition"));
        return apiClient;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to set field " + fieldName + " on " + target.getClass().getSimpleName(), e);
        }
    }

    private static String envOrProperty(String envKey, String... propertyKeys) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        for (String propertyKey : propertyKeys) {
            String propertyValue = System.getProperty(propertyKey);
            if (propertyValue != null && !propertyValue.isBlank()) {
                return propertyValue;
            }
            String yamlValue = APPLICATION_YAML.getProperty(propertyKey);
            if (yamlValue != null && !yamlValue.isBlank()) {
                return yamlValue;
            }
        }
        return "";
    }

    private static Properties loadApplicationYaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(Path.of("src", "main", "resources", "application.yml")));
        Properties properties = factory.getObject();
        return properties == null ? new Properties() : properties;
    }

    private static String loadSubmissionDifficulty(String submissionId) throws Exception {
        Path metadataPath = Path.of("data", "submissions", submissionId, "metadata.json");
        assertTrue(Files.exists(metadataPath), "missing metadata file for fixture submission: " + submissionId);
        var root = OBJECT_MAPPER.readTree(metadataPath.toFile());
        String difficulty = root.path("difficulty").asText("");
        assertTrue(!difficulty.isBlank(), "missing difficulty in metadata for fixture submission: " + submissionId);
        return difficulty;
    }

    private static List<LocalSubmissionMetadata> loadLocalSubmissionMetadata() throws Exception {
        Path submissionsRoot = Path.of("data", "submissions");
        if (!Files.exists(submissionsRoot)) {
            return List.of();
        }
        List<LocalSubmissionMetadata> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(submissionsRoot)) {
            for (Path submissionDir : stream.filter(Files::isDirectory).toList()) {
                Path metadataPath = submissionDir.resolve("metadata.json");
                if (!Files.exists(metadataPath)) {
                    continue;
                }
                var root = OBJECT_MAPPER.readTree(metadataPath.toFile());
                String submissionId = root.path("submissionId").asText(submissionDir.getFileName().toString());
                String difficulty = root.path("difficulty").asText("");
                String fflogsReportUrl = root.path("fflogsReportUrl").asText("");
                if (difficulty.isBlank()) {
                    continue;
                }
                result.add(new LocalSubmissionMetadata(submissionId, difficulty, fflogsReportUrl));
            }
        }
        return result;
    }

    private record LocalSubmissionMetadata(
            String submissionId,
            String difficulty,
            String fflogsReportUrl
    ) {
        private boolean hasFflogsReportUrl() {
            return fflogsReportUrl != null && !fflogsReportUrl.isBlank();
        }
    }
}
