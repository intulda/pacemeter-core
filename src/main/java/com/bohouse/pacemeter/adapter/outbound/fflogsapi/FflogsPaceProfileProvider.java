package com.bohouse.pacemeter.adapter.outbound.fflogsapi;

import com.bohouse.pacemeter.application.port.outbound.PaceProfileProvider;
import com.bohouse.pacemeter.core.estimator.PaceProfile;
import com.bohouse.pacemeter.core.estimator.TimelinePaceProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FFLogs API를 통해 해당 존의 rDPS 1위 타임라인을 PaceProfile로 제공.
 *
 * 동작 흐름:
 *   1. ACT territory ID → fflogs-zones.json → FFLogs zone ID + encounter index
 *   2. FFLogs API로 zone encounters 조회 → index로 특정 encounter 선택
 *   3. encounter ID → top ranking → DPS 타임라인 → TimelinePaceProfile
 *
 * 새 레이드 시즌: fflogs-zones.json에 territory ID → zone ID 한 줄 추가하면 끝.
 * territory ID는 cactbot zone_id.ts에서 확인 가능.
 */
@Component
public class FflogsPaceProfileProvider implements PaceProfileProvider {

    private static final Logger log = LoggerFactory.getLogger(FflogsPaceProfileProvider.class);

    private final FflogsZoneLookup zoneLookup;
    private final FflogsApiClient apiClient;

    // ACT territory ID → PaceProfile 캐시
    private final ConcurrentHashMap<Integer, PaceProfile> cache = new ConcurrentHashMap<>();

    public FflogsPaceProfileProvider(FflogsZoneLookup zoneLookup, FflogsApiClient apiClient) {
        this.zoneLookup = zoneLookup;
        this.apiClient = apiClient;
    }

    @Override
    public Optional<PaceProfile> findProfile(String fightName, int actTerritoryId) {
        return findProfile(fightName, actTerritoryId, 0);  // jobId=0 (전체 직업)
    }

    @Override
    public Optional<PaceProfile> findProfile(String fightName, int actTerritoryId, int playerJobId) {
        PaceProfile cached = cache.get(actTerritoryId);
        if (cached != null) {
            log.info("[FFLogs] cache hit for territory={} ({})", actTerritoryId, fightName);
            return Optional.of(cached);
        }

        Optional<FflogsZoneLookup.ZoneLookupResult> resolved = zoneLookup.resolve(actTerritoryId);
        if (resolved.isEmpty()) {
            log.info("[FFLogs] no mapping for territory={} ({}) → PaceProfile.NONE", actTerritoryId, fightName);
            cache.put(actTerritoryId, PaceProfile.NONE);
            return Optional.of(PaceProfile.NONE);
        }

        FflogsZoneLookup.ZoneLookupResult zone = resolved.get();
        log.info("[FFLogs] territory={} → fflogsZone={} encounterIndex={} ({})",
                actTerritoryId, zone.fflogsZoneId(), zone.encounterIndex(), fightName);

        List<FflogsApiClient.EncounterInfo> encounters = apiClient.fetchZoneEncounters(zone.fflogsZoneId());
        if (encounters.isEmpty()) {
            log.warn("[FFLogs] no encounters for fflogsZone={} → PaceProfile.NONE", zone.fflogsZoneId());
            cache.put(actTerritoryId, PaceProfile.NONE);
            return Optional.of(PaceProfile.NONE);
        }

        int idx = Math.min(zone.encounterIndex(), encounters.size() - 1);
        if (zone.encounterIndex() >= encounters.size()) {
            log.warn("[FFLogs] encounterIndex={} >= size={}, using last", zone.encounterIndex(), encounters.size());
        }
        FflogsApiClient.EncounterInfo encounter = encounters.get(idx);
        log.info("[FFLogs] encounter: id={} name='{}'", encounter.id(), encounter.name());

        // 직업 필터 적용
        String className = (playerJobId > 0)
                ? FfxivJobMapper.toClassName(playerJobId).orElse(null)
                : null;

        if (className != null) {
            log.info("[FFLogs] fetching TOP ranking for job: {} ({})",
                    className, FfxivJobMapper.toKoreanName(playerJobId));
        } else {
            log.info("[FFLogs] fetching TOP ranking for ALL jobs (playerJobId={})", playerJobId);
        }

        Optional<FflogsApiClient.TopRanking> ranking = apiClient.fetchTopRanking(encounter.id(), className);
        if (ranking.isEmpty()) {
            log.warn("[FFLogs] no ranking for encounterId={} → PaceProfile.NONE", encounter.id());
            cache.put(actTerritoryId, PaceProfile.NONE);
            return Optional.of(PaceProfile.NONE);
        }

        FflogsApiClient.TopRanking r = ranking.get();

        // 파티 전체 타임라인 (파티 비교용)
        List<long[]> partyTimeline = apiClient.fetchCumulativeDamageTimeline(
                r.reportCode(), r.reportStartMs(), r.fightStartMs(), r.durationMs());

        if (partyTimeline.size() < 2) {
            log.warn("[FFLogs] party timeline too short ({} points) → PaceProfile.NONE", partyTimeline.size());
            cache.put(actTerritoryId, PaceProfile.NONE);
            return Optional.of(PaceProfile.NONE);
        }

        String label = className != null
                ? "FFLogs #1 " + className + ": " + encounter.name()
                : "FFLogs #1 rDPS: " + encounter.name();

        TimelinePaceProfile profile = buildProfile(label, r.durationMs(), partyTimeline);
        log.info("[FFLogs] PaceProfile built: label='{}' points={} duration={}ms",
                profile.label(), profile.pointCount(), r.durationMs());

        cache.put(actTerritoryId, profile);
        return Optional.of(profile);
    }

    /**
     * 개인 직업별 TOP의 개인 DPS 타임라인 프로필을 찾는다.
     *
     * @param fightName      보스 이름
     * @param actTerritoryId 존 ID
     * @param playerJobId    플레이어 직업 ID
     * @return 개인 타임라인 프로필 (없으면 NONE)
     */
    public Optional<PaceProfile> findIndividualProfile(String fightName, int actTerritoryId, int playerJobId) {
        if (playerJobId == 0) {
            return Optional.of(PaceProfile.NONE);
        }

        // 캐시 키: territoryId + jobId
        int cacheKey = actTerritoryId * 1000 + playerJobId;
        PaceProfile cached = cache.get(cacheKey);
        if (cached != null) {
            log.info("[FFLogs] individual cache hit for territory={} jobId={}", actTerritoryId, playerJobId);
            return Optional.of(cached);
        }

        Optional<FflogsZoneLookup.ZoneLookupResult> resolved = zoneLookup.resolve(actTerritoryId);
        if (resolved.isEmpty()) {
            cache.put(cacheKey, PaceProfile.NONE);
            return Optional.of(PaceProfile.NONE);
        }

        FflogsZoneLookup.ZoneLookupResult zone = resolved.get();
        List<FflogsApiClient.EncounterInfo> encounters = apiClient.fetchZoneEncounters(zone.fflogsZoneId());
        if (encounters.isEmpty()) {
            cache.put(cacheKey, PaceProfile.NONE);
            return Optional.of(PaceProfile.NONE);
        }

        int idx = Math.min(zone.encounterIndex(), encounters.size() - 1);
        FflogsApiClient.EncounterInfo encounter = encounters.get(idx);

        String className = FfxivJobMapper.toClassName(playerJobId).orElse(null);
        if (className == null) {
            cache.put(cacheKey, PaceProfile.NONE);
            return Optional.of(PaceProfile.NONE);
        }

        log.info("[FFLogs] fetching individual profile for {} ({})", className, FfxivJobMapper.toKoreanName(playerJobId));

        Optional<FflogsApiClient.TopRanking> ranking = apiClient.fetchTopRanking(encounter.id(), className);
        if (ranking.isEmpty()) {
            cache.put(cacheKey, PaceProfile.NONE);
            return Optional.of(PaceProfile.NONE);
        }

        FflogsApiClient.TopRanking r = ranking.get();

        // 개인 타임라인 (sourceID 필터)
        List<long[]> individualTimeline = apiClient.fetchIndividualDamageTimeline(
                r.reportCode(), r.reportStartMs(), r.fightStartMs(), r.durationMs(), r.sourceId());

        if (individualTimeline.size() < 2) {
            log.warn("[FFLogs] individual timeline too short ({} points)", individualTimeline.size());
            cache.put(cacheKey, PaceProfile.NONE);
            return Optional.of(PaceProfile.NONE);
        }

        String label = "Individual " + className + " TOP";
        TimelinePaceProfile profile = buildProfile(label, r.durationMs(), individualTimeline);
        log.info("[FFLogs] Individual PaceProfile built: {} points", profile.pointCount());

        cache.put(cacheKey, profile);
        return Optional.of(profile);
    }

    private TimelinePaceProfile buildProfile(String label, long durationMs, List<long[]> timeline) {
        long[] timePoints = new long[timeline.size()];
        long[] cumulativeDamage = new long[timeline.size()];
        for (int i = 0; i < timeline.size(); i++) {
            timePoints[i] = timeline.get(i)[0];
            cumulativeDamage[i] = timeline.get(i)[1];
        }
        if (!isSorted(timePoints)) {
            log.warn("[FFLogs] timeline not sorted, sorting now");
            sortByTime(timePoints, cumulativeDamage);
        }
        return new TimelinePaceProfile(label, durationMs, timePoints, cumulativeDamage);
    }

    private boolean isSorted(long[] arr) {
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] < arr[i - 1]) return false;
        }
        return true;
    }

    private void sortByTime(long[] times, long[] damage) {
        Integer[] idx = new Integer[times.length];
        Arrays.setAll(idx, i -> i);
        Arrays.sort(idx, (a, b) -> Long.compare(times[a], times[b]));
        long[] tmpT = Arrays.copyOf(times, times.length);
        long[] tmpD = Arrays.copyOf(damage, damage.length);
        for (int i = 0; i < idx.length; i++) {
            times[i] = tmpT[idx[i]];
            damage[i] = tmpD[idx[i]];
        }
    }
}