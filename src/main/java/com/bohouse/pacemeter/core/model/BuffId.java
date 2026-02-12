package com.bohouse.pacemeter.core.model;

/**
 * 버프/디버프(상태이상)를 구분하는 고유 ID.
 * FF14에서 각 버프마다 고유 번호가 있는데, 그 번호를 감싸서 사용한다.
 *
 * 예: "전투 리트머스" 버프의 ID가 1001이면 -> new BuffId(1001)
 */
public record BuffId(int value) {
}
