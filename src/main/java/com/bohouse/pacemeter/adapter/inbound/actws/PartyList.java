package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;
import java.util.List;

/** ACT 11번 로그: 파티원 목록 */
public record PartyList(Instant ts, List<Long> partyMemberIds) implements ParsedLine {}