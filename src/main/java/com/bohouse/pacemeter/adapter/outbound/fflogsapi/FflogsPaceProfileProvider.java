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

/**
 * FFLogs API를 통해 해당 존의 rDPS 1위 타임라인을 PaceProfile로 제공.
 *
 * 동작 흐름:
 *   1. zoneName → FFLogs encounter ID (FflogsEncounterLookup)
 *   2. encounter ID → top ranking (report code, fight 시간) (FflogsApiClient)
 *   3. report + fight 시간 → DPS 타임라인 → TimelinePaceProfile
 *
 * encounter ID가 0(미설정)이거나 API 오류 시 PaceProfile.NONE 반환.
 */
@Component
public class FflogsPaceProfileProvider implements PaceProfileProvider {

    private static final Logger log = LoggerFactory.getLogger(FflogsPaceProfileProvider.class);

    private final FflogsEncounterLookup encounterLookup;
    private final FflogsApiClient apiClient;

    public FflogsPaceProfileProvider(FflogsEncounterLookup encounterLookup, FflogsApiClient apiClient) {
        this.encounterLookup = encounterLookup;
        this.apiClient = apiClient;
    }

    @Override
    public Optional<PaceProfile> findProfile(String zoneName) {
        Optional<Integer> encounterId = encounterLookup.findEncounterId(zoneName);
        if (encounterId.isEmpty()) {
            log.info("[FFLogs] no encounter mapping for zone='{}' → PaceProfile.NONE", zoneName);
            return Optional.of(PaceProfile.NONE);
        }

        int id = encounterId.get();
        log.info("[FFLogs] fetching top ranking for zone='{}' encounterId={}", zoneName, id);

        Optional<FflogsApiClient.TopRanking> ranking = apiClient.fetchTopRanking(id);
        if (ranking.isEmpty()) {
            log.warn("[FFLogs] no ranking found → PaceProfile.NONE");
            return Optional.of(PaceProfile.NONE);
        }

        FflogsApiClient.TopRanking r = ranking.get();
        List<long[]> timeline = apiClient.fetchCumulativeDamageTimeline(
                r.reportCode(), r.reportStartMs(), r.fightStartMs(), r.durationMs());

        if (timeline.size() < 2) {
            log.warn("[FFLogs] timeline too short ({} points) → PaceProfile.NONE", timeline.size());
            return Optional.of(PaceProfile.NONE);
        }

        TimelinePaceProfile profile = buildProfile(zoneName, r.durationMs(), timeline);
        log.info("[FFLogs] PaceProfile built: label='{}' points={} duration={}ms",
                profile.label(), profile.pointCount(), r.durationMs());
        return Optional.of(profile);
    }

    private TimelinePaceProfile buildProfile(String zoneName, long durationMs, List<long[]> timeline) {
        long[] timePoints = new long[timeline.size()];
        long[] cumulativeDamage = new long[timeline.size()];
        for (int i = 0; i < timeline.size(); i++) {
            timePoints[i] = timeline.get(i)[0];
            cumulativeDamage[i] = timeline.get(i)[1];
        }
        // 시간 순 정렬 보장
        if (!isSorted(timePoints)) {
            log.warn("[FFLogs] timeline not sorted, sorting now");
            sortByTime(timePoints, cumulativeDamage);
        }
        String label = "FFLogs #1 rDPS: " + zoneName;
        return new TimelinePaceProfile(label, durationMs, timePoints, cumulativeDamage);
    }

    private boolean isSorted(long[] arr) {
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] < arr[i - 1]) return false;
        }
        return true;
    }

    private void sortByTime(long[] times, long[] damage) {
        // 인덱스 정렬 후 적용
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