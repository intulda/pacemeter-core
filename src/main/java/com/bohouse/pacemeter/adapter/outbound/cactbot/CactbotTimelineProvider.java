package com.bohouse.pacemeter.adapter.outbound.cactbot;

import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * cactbot 타임라인 파일에서 엔레이지 시간을 파싱한다.
 */
@Component
public class CactbotTimelineProvider implements EnrageTimeProvider {

    private static final Logger log = LoggerFactory.getLogger(CactbotTimelineProvider.class);
    private static final String RAW_BASE_URL = "https://raw.githubusercontent.com/OverlayPlugin/cactbot/main/";
    private static final Pattern ENRAGE_PATTERN =
            Pattern.compile("(?m)^(\\d+(?:\\.\\d+)?)\\s+\".*\\(enrage\\)\".*$", Pattern.CASE_INSENSITIVE);

    private final RestTemplate restTemplate;
    private final CactbotFileMapping fileMapping;
    private final ConcurrentHashMap<Integer, Optional<EnrageInfo>> cache = new ConcurrentHashMap<>();

    public CactbotTimelineProvider(RestTemplate restTemplate, CactbotFileMapping fileMapping) {
        this.restTemplate = restTemplate;
        this.fileMapping = fileMapping;
    }

    @Override
    public Optional<EnrageInfo> getEnrageTime(int territoryId) {
        return cache.computeIfAbsent(territoryId, this::loadEnrageTime);
    }

    private Optional<EnrageInfo> loadEnrageTime(int territoryId) {
        Optional<String> path = fileMapping.resolveTimelinePath(territoryId);
        if (path.isEmpty()) {
            log.info("[Cactbot] no timeline mapping for territory={}", territoryId);
            return Optional.empty();
        }

        String url = RAW_BASE_URL + path.get();
        try {
            String content = restTemplate.getForObject(url, String.class);
            if (content == null || content.isBlank()) {
                log.warn("[Cactbot] empty timeline content for territory={} url={}", territoryId, url);
                return Optional.empty();
            }

            Optional<Double> seconds = parseEnrageSeconds(content);
            if (seconds.isEmpty()) {
                log.info("[Cactbot] no enrage line found for territory={} url={}", territoryId, url);
                return Optional.empty();
            }

            ConfidenceLevel confidence = classifyConfidence(seconds.get());
            return Optional.of(new EnrageInfo(seconds.get(), confidence, url));
        } catch (RestClientException e) {
            log.warn("[Cactbot] failed to load timeline for territory={} url={} error={}",
                    territoryId, url, e.getMessage());
            return Optional.empty();
        }
    }

    static Optional<Double> parseEnrageSeconds(String timelineContent) {
        Matcher matcher = ENRAGE_PATTERN.matcher(timelineContent);
        if (!matcher.find()) {
            return Optional.empty();
        }

        try {
            return Optional.of(Double.parseDouble(matcher.group(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static ConfidenceLevel classifyConfidence(double seconds) {
        if (seconds < 60.0) return ConfidenceLevel.LOW;
        if (seconds < 180.0) return ConfidenceLevel.MEDIUM;
        return ConfidenceLevel.HIGH;
    }
}
