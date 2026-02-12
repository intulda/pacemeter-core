package com.bohouse.pacemeter.core.estimator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * rDPS 추정치를 얼마나 신뢰할 수 있는지 나타내는 "신뢰도" 정보.
 *
 * score는 0.0 ~ 1.0 사이의 값이다:
 *   - 1.0 = 높은 신뢰도 (전투가 충분히 길고, DPS가 안정적이고, 데이터가 많음)
 *   - 0.0 = 신뢰 불가 (전투가 방금 시작됐거나 데이터가 부족함)
 *
 * reasons에는 신뢰도가 깎인 이유가 담긴다.
 * 오버레이에서 툴팁으로 보여줄 수 있다.
 * 예: ["전투 30초 미만", "데이터 부족 (3회 타격)"]
 */
public record Confidence(double score, List<String> reasons) {

    public Confidence {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("신뢰도 점수는 0.0~1.0 사이여야 합니다. 입력값: " + score);
        }
        reasons = List.copyOf(reasons);
    }

    /** 데이터가 전혀 없을 때 사용하는 "신뢰도 0" 객체. */
    public static Confidence none() {
        return new Confidence(0.0, List.of("No data"));
    }

    /**
     * 신뢰도를 조립하는 빌더.
     * 1.0에서 시작해서 조건에 따라 감점(penalize)하는 방식으로 동작한다.
     */
    public static final class Builder {
        private double score = 1.0;
        private final List<String> reasons = new ArrayList<>();

        /**
         * 신뢰도를 깎는다.
         * @param amount 깎을 점수 (예: 0.4이면 1.0에서 0.6이 됨)
         * @param reason 깎는 이유 (예: "전투 30초 미만")
         */
        public Builder penalize(double amount, String reason) {
            this.score -= amount;
            this.reasons.add(reason);
            return this;
        }

        /** 최종 Confidence 객체를 만든다. 점수는 0.0~1.0 범위로 보정된다. */
        public Confidence build() {
            double clamped = Math.max(0.0, Math.min(1.0, score));
            return new Confidence(clamped, Collections.unmodifiableList(new ArrayList<>(reasons)));
        }
    }
}
