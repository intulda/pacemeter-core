package com.bohouse.pacemeter.core.engine;

import com.bohouse.pacemeter.core.snapshot.OverlaySnapshot;

import java.util.Optional;

/**
 * 이벤트 하나를 엔진에 넣었을 때 돌아오는 결과.
 *
 * - Tick이나 FightEnd 이벤트였으면: 스냅샷이 들어있다 (snapshot에 값이 있음)
 * - 그 외 이벤트였으면: 스냅샷이 비어있다 (snapshot이 empty)
 *
 * @param snapshot 생성된 스냅샷. 없으면 Optional.empty()
 */
public record EngineResult(Optional<OverlaySnapshot> snapshot) {

    /** 스냅샷이 없는 빈 결과를 만든다. */
    public static EngineResult empty() {
        return new EngineResult(Optional.empty());
    }

    /** 스냅샷이 포함된 결과를 만든다. */
    public static EngineResult withSnapshot(OverlaySnapshot snapshot) {
        return new EngineResult(Optional.of(snapshot));
    }

    /** 스냅샷이 있는지 확인한다. */
    public boolean hasSnapshot() {
        return snapshot.isPresent();
    }
}
