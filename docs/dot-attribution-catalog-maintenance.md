# Dot Attribution Catalog Maintenance

## 목적
- `statusId=0` DoT attribution 규칙(`dot-attribution-catalog.json`)을 수동 하드코딩이 아니라 재생성 가능한 방식으로 관리한다.

## 재생성
프로젝트 루트에서:

```bash
python3 scripts/build_dot_attribution_catalog.py
python3 scripts/build_action_name_catalog.py
```

출력:
- `src/main/resources/dot-attribution-catalog.json`
- `src/main/resources/action-name-catalog.json`

## 검증
1. 카탈로그 단위 테스트:
```bash
./gradlew test --tests com.bohouse.pacemeter.core.model.DotAttributionCatalogTest
```
2. ingestion/resolver 회귀:
```bash
./gradlew test --tests com.bohouse.pacemeter.application.UnknownStatusDotAttributionResolverTest --tests com.bohouse.pacemeter.application.ActIngestionServiceTest
```
3. parity 기준선:
```bash
./scripts/parity_repro_check.sh
```

## 주의사항
- `STATUS_TO_ACTION_OVERRIDES`는 ACT Definitions만으로 1:1 매칭이 안 되는 케이스를 위한 최소 예외다.
- 신규 예외를 추가할 때는 raw line 근거(ability/status pair)를 `docs/parity-patch-notes.md`에 함께 기록한다.
