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

@Component
public class FflogsPaceProfileProvider implements PaceProfileProvider {

    private static final Logger log = LoggerFactory.getLogger(FflogsPaceProfileProvider.class);
    private static final int TOP_RANKING_CANDIDATE_LIMIT = 10;

    private final FflogsZoneLookup zoneLookup;
    private final FflogsApiClient apiClient;
    private final ConcurrentHashMap<Integer, PaceProfile> cache = new ConcurrentHashMap<>();

    public FflogsPaceProfileProvider(FflogsZoneLookup zoneLookup, FflogsApiClient apiClient) {
        this.zoneLookup = zoneLookup;
        this.apiClient = apiClient;
    }

    @Override
    public Optional<PaceProfile> findProfile(String fightName, int actTerritoryId) {
        return findProfile(fightName, actTerritoryId, 0);
    }

    @Override
    public Optional<PaceProfile> findProfile(String fightName, int actTerritoryId, int playerJobId) {
        PaceProfile cached = cache.get(actTerritoryId);
        if (cached != null) {
            if (cached == PaceProfile.NONE) {
                log.info("[FFLogs] cache hit NONE for territory={} ({})", actTerritoryId, fightName);
            } else {
                log.info("[FFLogs] cache hit for territory={} ({})", actTerritoryId, fightName);
            }
            return Optional.of(cached);
        }

        Optional<FflogsZoneLookup.ZoneLookupResult> resolved = zoneLookup.resolve(actTerritoryId);
        if (resolved.isEmpty()) {
            log.info("[FFLogs] no mapping for territory={} ({}) -> PaceProfile.NONE", actTerritoryId, fightName);
            return Optional.of(PaceProfile.NONE);
        }

        FflogsZoneLookup.ZoneLookupResult zone = resolved.get();
        log.info("[FFLogs] territory={} -> fflogsZone={} encounterIndex={} ({})",
                actTerritoryId, zone.fflogsZoneId(), zone.encounterIndex(), fightName);

        List<FflogsApiClient.EncounterInfo> encounters = apiClient.fetchZoneEncounters(zone.fflogsZoneId());
        if (encounters.isEmpty()) {
            log.warn("[FFLogs] no encounters for fflogsZone={} -> PaceProfile.NONE", zone.fflogsZoneId());
            return Optional.of(PaceProfile.NONE);
        }

        int idx = Math.min(zone.encounterIndex(), encounters.size() - 1);
        if (zone.encounterIndex() >= encounters.size()) {
            log.warn("[FFLogs] encounterIndex={} >= size={}, using last", zone.encounterIndex(), encounters.size());
        }

        FflogsApiClient.EncounterInfo encounter = encounters.get(idx);
        log.info("[FFLogs] encounter: id={} name='{}'", encounter.id(), encounter.name());

        String className = playerJobId > 0
                ? FfxivJobMapper.toClassName(playerJobId).orElse(null)
                : null;

        if (className != null) {
            log.info("[FFLogs] fetching TOP ranking for job: {} ({})",
                    className, FfxivJobMapper.toKoreanName(playerJobId));
        } else {
            log.info("[FFLogs] fetching TOP ranking for ALL jobs (playerJobId={})", playerJobId);
        }

        PartyProfileCandidate candidate = findUsablePartyCandidate(encounter.id(), className);
        if (candidate == null) {
            log.warn("[FFLogs] no usable ranking timeline for encounterId={} -> PaceProfile.NONE", encounter.id());
            return Optional.of(PaceProfile.NONE);
        }

        String label = className != null
                ? "FFLogs #1 " + className + ": " + encounter.name()
                : "FFLogs #1 rDPS: " + encounter.name();

        TimelinePaceProfile profile = buildProfile(label, candidate.ranking().durationMs(), candidate.timeline());
        log.info("[FFLogs] PaceProfile built: label='{}' points={} duration={}ms code={}",
                profile.label(), profile.pointCount(), candidate.ranking().durationMs(), candidate.ranking().reportCode());

        cache.put(actTerritoryId, profile);
        return Optional.of(profile);
    }

    @Override
    public Optional<PaceProfile> findIndividualProfile(String fightName, int actTerritoryId, int playerJobId) {
        if (playerJobId == 0) {
            return Optional.of(PaceProfile.NONE);
        }

        int cacheKey = actTerritoryId * 1000 + playerJobId;
        PaceProfile cached = cache.get(cacheKey);
        if (cached != null) {
            if (cached == PaceProfile.NONE) {
                log.info("[FFLogs] individual cache hit NONE for territory={} jobId={}", actTerritoryId, playerJobId);
            } else {
                log.info("[FFLogs] individual cache hit for territory={} jobId={}", actTerritoryId, playerJobId);
            }
            return Optional.of(cached);
        }

        Optional<FflogsZoneLookup.ZoneLookupResult> resolved = zoneLookup.resolve(actTerritoryId);
        if (resolved.isEmpty()) {
            return Optional.of(PaceProfile.NONE);
        }

        FflogsZoneLookup.ZoneLookupResult zone = resolved.get();
        List<FflogsApiClient.EncounterInfo> encounters = apiClient.fetchZoneEncounters(zone.fflogsZoneId());
        if (encounters.isEmpty()) {
            return Optional.of(PaceProfile.NONE);
        }

        int idx = Math.min(zone.encounterIndex(), encounters.size() - 1);
        FflogsApiClient.EncounterInfo encounter = encounters.get(idx);

        String className = FfxivJobMapper.toClassName(playerJobId).orElse(null);
        if (className == null) {
            return Optional.of(PaceProfile.NONE);
        }

        log.info("[FFLogs] fetching individual profile for {} ({})", className, FfxivJobMapper.toKoreanName(playerJobId));

        IndividualProfileCandidate candidate = findUsableIndividualCandidate(encounter.id(), className);
        if (candidate == null) {
            log.warn("[FFLogs] no usable individual ranking timeline for encounterId={} class={}", encounter.id(), className);
            return Optional.of(PaceProfile.NONE);
        }

        String label = "Individual " + className + " TOP";
        TimelinePaceProfile profile = buildProfile(label, candidate.ranking().durationMs(), candidate.timeline());
        log.info("[FFLogs] Individual PaceProfile built: {} points code={}",
                profile.pointCount(), candidate.ranking().reportCode());

        cache.put(cacheKey, profile);
        return Optional.of(profile);
    }

    private PartyProfileCandidate findUsablePartyCandidate(int encounterId, String className) {
        List<FflogsApiClient.TopRanking> rankings = apiClient.fetchTopRankings(
                encounterId, className, TOP_RANKING_CANDIDATE_LIMIT);
        if (rankings.isEmpty()) {
            return null;
        }

        for (FflogsApiClient.TopRanking ranking : rankings) {
            List<long[]> timeline = apiClient.fetchCumulativeDamageTimeline(
                    ranking.reportCode(), ranking.reportStartMs(), ranking.fightStartMs(), ranking.durationMs());
            if (timeline.size() >= 2) {
                return new PartyProfileCandidate(ranking, timeline);
            }
            log.info("[FFLogs] skipping archived/inaccessible ranking for encounterId={} code={}",
                    encounterId, ranking.reportCode());
        }
        return null;
    }

    private IndividualProfileCandidate findUsableIndividualCandidate(int encounterId, String className) {
        List<FflogsApiClient.TopRanking> rankings = apiClient.fetchTopRankings(
                encounterId, className, TOP_RANKING_CANDIDATE_LIMIT);
        if (rankings.isEmpty()) {
            return null;
        }

        for (FflogsApiClient.TopRanking ranking : rankings) {
            int sourceId = ranking.sourceId();
            if (sourceId == 0 && !ranking.playerName().isBlank()) {
                sourceId = apiClient.fetchPlayerSourceId(ranking.reportCode(), ranking.playerName());
            }
            if (sourceId == 0) {
                log.info("[FFLogs] skipping ranking without resolvable sourceId code={} player='{}'",
                        ranking.reportCode(), ranking.playerName());
                continue;
            }

            List<long[]> timeline = apiClient.fetchIndividualDamageTimeline(
                    ranking.reportCode(), ranking.reportStartMs(), ranking.fightStartMs(), ranking.durationMs(), sourceId);
            if (timeline.size() >= 2) {
                return new IndividualProfileCandidate(ranking, timeline);
            }
            log.info("[FFLogs] skipping archived/inaccessible individual ranking code={}", ranking.reportCode());
        }
        return null;
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
            if (arr[i] < arr[i - 1]) {
                return false;
            }
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

    private record PartyProfileCandidate(FflogsApiClient.TopRanking ranking, List<long[]> timeline) {}

    private record IndividualProfileCandidate(FflogsApiClient.TopRanking ranking, List<long[]> timeline) {}
}
