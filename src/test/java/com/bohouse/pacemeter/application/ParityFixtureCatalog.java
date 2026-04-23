package com.bohouse.pacemeter.application;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

final class ParityFixtureCatalog {
    private static final String RESOURCE = "parity-fixtures.json";

    private ParityFixtureCatalog() {
    }

    static Config loadDefault(ObjectMapper objectMapper) {
        try (InputStream input = ParityFixtureCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing test fixture resource: " + RESOURCE);
            }
            Config config = objectMapper.readValue(input, Config.class);
            if (config == null || config.globalGate() == null) {
                throw new IllegalStateException("invalid fixture config: " + RESOURCE);
            }
            return new Config(
                    config.globalGate(),
                    nullSafe(config.submissionGates()),
                    nullSafe(config.allFightsGates()),
                    nullSafe(config.contentGroupGates())
            );
        } catch (Exception e) {
            throw new IllegalStateException("failed to load fixture config: " + RESOURCE, e);
        }
    }

    private static <T> List<T> nullSafe(List<T> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    record Config(
            GlobalGate globalGate,
            List<SubmissionGate> submissionGates,
            List<AllFightsGate> allFightsGates,
            List<ContentGroupGate> contentGroupGates
    ) {
    }

    record GlobalGate(
            double maxP95Ape,
            double maxActorApe,
            double maxOutlierRatio
    ) {
    }

    record SubmissionGate(
            String submissionId,
            String contentGroup,
            int minMatchedActors,
            double maxP95Ape,
            double maxActorApe
    ) {
    }

    record AllFightsGate(
            String submissionId,
            String contentGroup,
            int minMatchedActors,
            double maxP95Ape,
            double maxActorApe
    ) {
    }

    record ContentGroupGate(
            String contentGroup,
            int minEvaluatedSubmissions,
            double maxP95Ape,
            double maxActorApe,
            boolean optional
    ) {
    }
}
