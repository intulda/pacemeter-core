package com.bohouse.pacemeter.application.port.outbound;

import java.util.Optional;

/**
 * ACT territory ID 기준으로 엔레이지 시간 정보를 제공하는 아웃바운드 포트.
 */
public interface EnrageTimeProvider {

    Optional<EnrageInfo> getEnrageTime(int territoryId);

    record EnrageInfo(double seconds, ConfidenceLevel confidence, String source) {
    }

    enum ConfidenceLevel {
        LOW,
        MEDIUM,
        HIGH
    }
}
