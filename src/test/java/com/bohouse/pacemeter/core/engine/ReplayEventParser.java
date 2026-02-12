package com.bohouse.pacemeter.core.engine;

import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.BuffId;
import com.bohouse.pacemeter.core.model.DamageType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * JSONL 리플레이 파일을 읽어서 CombatEvent 리스트로 변환하는 파서.
 *
 * JSONL이란? 한 줄에 JSON 객체 하나씩 들어있는 파일 형식이다.
 * 전투 로그를 이 형식으로 저장해두면 테스트에서 그대로 재생할 수 있다.
 *
 * 파일 형식 예시:
 * <pre>
 * {"type":"FightStart","timestampMs":0,"fightName":"절 미래지"}
 * {"type":"DamageEvent","timestampMs":2500,"sourceId":1,"sourceName":"전사","targetId":100,"actionId":7,"amount":15000,"damageType":"DIRECT"}
 * {"type":"Tick","timestampMs":3250}
 * {"type":"FightEnd","timestampMs":120000,"kill":true}
 * </pre>
 *
 * #이나 //로 시작하는 줄은 주석으로 무시되고, 빈 줄도 건너뛴다.
 */
public final class ReplayEventParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReplayEventParser() {}

    /**
     * 클래스패스에서 JSONL 리플레이 파일을 읽어 파싱한다.
     *
     * @param resourcePath 리소스 경로 (예: "/replay/basic_fight.jsonl")
     * @return 시간순으로 정렬된 전투 이벤트 리스트
     */
    public static List<CombatEvent> parseResource(String resourcePath) throws IOException {
        try (InputStream is = ReplayEventParser.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("리플레이 파일을 찾을 수 없습니다: " + resourcePath);
            }
            return parse(is);
        }
    }

    /**
     * InputStream에서 JSONL을 읽어 파싱한다.
     */
    public static List<CombatEvent> parse(InputStream is) throws IOException {
        List<CombatEvent> events = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                    continue;  // 빈 줄과 주석은 건너뛰기
                }
                try {
                    events.add(parseLine(line));
                } catch (Exception e) {
                    throw new IOException(lineNum + "번째 줄 파싱 실패: " + line, e);
                }
            }
        }
        return events;
    }

    /** JSON 한 줄을 CombatEvent 객체로 변환한다 */
    private static CombatEvent parseLine(String json) throws IOException {
        JsonNode node = MAPPER.readTree(json);
        String type = node.get("type").asText();

        return switch (type) {
            case "FightStart" -> new CombatEvent.FightStart(
                    node.get("timestampMs").asLong(),
                    node.get("fightName").asText()
            );

            case "DamageEvent" -> new CombatEvent.DamageEvent(
                    node.get("timestampMs").asLong(),
                    new ActorId(node.get("sourceId").asLong()),
                    node.get("sourceName").asText(),
                    new ActorId(node.get("targetId").asLong()),
                    node.get("actionId").asInt(),
                    node.get("amount").asLong(),
                    DamageType.valueOf(node.get("damageType").asText())
            );

            case "BuffApply" -> new CombatEvent.BuffApply(
                    node.get("timestampMs").asLong(),
                    new ActorId(node.get("sourceId").asLong()),
                    new ActorId(node.get("targetId").asLong()),
                    new BuffId(node.get("buffId").asInt()),
                    node.get("durationMs").asLong()
            );

            case "BuffRemove" -> new CombatEvent.BuffRemove(
                    node.get("timestampMs").asLong(),
                    new ActorId(node.get("sourceId").asLong()),
                    new ActorId(node.get("targetId").asLong()),
                    new BuffId(node.get("buffId").asInt())
            );

            case "Tick" -> new CombatEvent.Tick(
                    node.get("timestampMs").asLong()
            );

            case "FightEnd" -> new CombatEvent.FightEnd(
                    node.get("timestampMs").asLong(),
                    node.get("kill").asBoolean()
            );

            default -> throw new IllegalArgumentException("알 수 없는 이벤트 타입: " + type);
        };
    }
}
