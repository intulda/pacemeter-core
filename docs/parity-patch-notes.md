# Parity Patch Notes

## 2026-04-07

### Live clone+suppress rollback
- `ActIngestionService`??live DoT clone/suppress 洹쒖튃???쒓굅?덈떎.
  - `LIVE_DOT_APPLICATION_CLONE_STATUS_TO_ACTION = {}`
  - `LIVE_DOT_TICK_SUPPRESSED_ACTION_IDS = {}`
- ???
  - SAM `04CC -> 1D41`
  - DRG `0A9F -> 64AC`
- ?댁쑀:
  - 理쒓렐 diagnostics濡??뺤씤???ㅼ젣 蹂묐ぉ? `status=0 attribution` 怨쇰?媛 ?꾨땲??
    live path?먯꽌 `1D41/64AC` DoT tick ?먯껜媛 suppress?섍퀬 ?덉뿀?ㅻ뒗 ?먯씠??
  - 湲곗〈 `dotModeBreakdown`? pre-validation assignment瑜?蹂닿퀬 ?덉뼱??
    emitted live surface? ?ㅻⅨ ?섏튂瑜??⑥? 臾몄젣泥섎읆 蹂댁뿬二쇨퀬 ?덉뿀??

### Diagnostics fix
- `ActIngestionService` debug 吏묎퀎瑜?`assigned`? `emitted`濡?遺꾨━?덈떎.
- `SubmissionParityReportDiagnostics`??`dotModeBreakdown` / `dotModeByTarget`??  ?댁젣 `debugDotAttributionEmittedAmounts()` / `debugDotAttributionEmittedHitCounts()`瑜?蹂몃떎.
- ?섎?:
  - ?욎쑝濡?diagnostics?먯꽌 蹂댁씠??mode total? ?ㅼ젣 live emit 湲곗??대떎.
  - `1D41`??嫄곕???`status0_tracked_target_split` ?붿감??emitted 臾몄젣媛 ?꾨땲??    pre-validation assignment surface??ㅻ뒗 ?먯씠 ?뺣━?먮떎.

### Observed impact
- heavy2 `fight=2`, SAM `1D41`
  - ?댁쟾 live surface:
    - `emittedTotal=553888`
    - `fflogsAbilityTotal=1542816`
    - `delta=-988928`
  - ?꾩옱:
    - `emittedTotal=1755776`
    - `raw21Total=276944`
    - `inferredDotTotal=1478832`
    - `fflogsAbilityTotal=1542816`
    - `delta=+212960`
- 利?`1D41`??吏곸쟾 怨쇱냼 `-988,928`?먯꽌 `+212,960`源뚯? ?뚮났?먮떎.
- heavy2 `fight=2`, DRG `64AC`
  - `localTotal=2203903`
  - `fflogsTotal=1934116`
  - `delta=+269787`
  - `64AC`???ъ쟾???⑥? ?듭떖 ?붿감??

### Verification
- passed:
  - `ActIngestionServiceTest`
  - `SubmissionParityRegressionGateTest`
- note:
  - diagnostics long-run? Windows/Gradle timeout??嫄몃┫ ???덉쑝誘濡?    ?꾩슂??寃쎌슦 ?⑥씪 diagnostic test濡??섏튂瑜??ㅼ떆 戮묐뒗??

### Next step
1. heavy2 `fight=2` DRG `64AC`瑜??곗꽑 蹂몃떎.
2. `64AC` ?⑥? `+269,787`??live target surface 臾몄젣?몄?,
   shared GUID / FFLogs ability semantics 臾몄젣?몄? ?ㅼ떆 遺꾨━?쒕떎.
3. `SubmissionParityReportDiagnostics`?먯꽌
   - `64AC directVsDot`
   - `64AC target parity`
   - `64AC abilityVsEvents`
   瑜???踰덉뿉 ?ㅼ떆 ?뺤씤?쒕떎.
4. `1D41`???밸텇媛?attribution clamp蹂대떎 ?꾩옱 emitted baseline???좎??섍퀬,
   `64AC` 履쎌쓣 ?ㅼ쓬 production 蹂寃???곸쑝濡??쇰뒗??

## 2026-04-01

### Priority reminder
- ?꾩옱 硫붿씤 ?ㅽ듃由쇱? `clearability`媛 ?꾨땲??`live rDPS parity`??
- 紐⑺몴??`pacemeter live rDPS ~= FFLogs companion live rDPS`.
- replay / parity report / diagnostics??production tuning ?꾩뿉 ?먯씤??遺꾨━?섎뒗 寃利??꾧뎄濡??ъ슜?쒕떎.

### DRG `64AC / Chaotic Spring` investigation
- heavy2 selected fight(`fight=6`) 湲곗??쇰줈 DRG actor total? ?ъ쟾??FFLogs蹂대떎 ?믩떎.
  - local `41284.5`
  - fflogs `40149.7`
  - delta `+1134.8`
- 洹몃윴??`64AC` ?먯껜??"紐??〓뒗" 臾몄젣媛 ?꾨땲?덈떎.
  - local full total `1788931`
  - fflogs total `1330078`
  - delta `+458853`
- local skill breakdown?먯꽌 媛숈? GUID `64AC`媛 ???뷀듃由щ줈 遺꾨━?섏뼱 ?덉뿀??
  - `DoT#64AC = 1086661`
  - `chaotic spring (64AC) = 702270`
- 湲곗〈 parity report??local top skills瑜?`merge ?꾩뿉 limit`?섍퀬 ?덉뼱??shared GUID ?ㅽ궗 ?댁꽍???쒓끝?????덉뿀??

### This patch
- `SubmissionParityReportService`?먯꽌 local top skill 吏묎퀎瑜?`GUID/name key濡?癒쇱? merge`????`damage ???뺣젹 + top N limit` ?섎룄濡??섏젙.
- 紐⑹쟻? shared GUID ?ㅽ궗(`64AC` 媛숈? direct+DoT/alias split)??吏꾨떒 ?쒓끝???쒓굅?섎뒗 寃?
- ???섏젙? ?곗꽑 parity diagnostics/report ?댁꽍 ?덉젙?붾? ?꾪븳 寃껋씠怨? live attribution production math瑜?諛붽씔 寃껋? ?꾨땲??

### Current interpretation
- DRG `64AC` ?붿뿬 ?댁뒋??`status=0 DoT瑜?紐??〓뒗媛`蹂대떎 `shared GUID瑜??대뼡 ?섎?濡??⑹궛/鍮꾧탳?댁빞 ?섎뒗媛`??媛源앸떎.
- selected fight 湲곗? DRG??`64AC`媛 遺議깊븳 寃껋씠 ?꾨땲???ㅽ엳??怨쇱쭛怨?履쎌쑝濡?蹂댁씤??
- ?곕씪???ㅼ쓬 production 蹂寃쎌? `64AC瑜????〓뒗 諛⑺뼢`???꾨땲??`64AC direct/DoT/shared GUID semantics`瑜?癒쇱? 遺꾨━????寃곗젙?댁빞 ?쒕떎.

### Next step
1. `64AC`??local split??engine/ingestion/event emit ?대뵒???앷린?붿? 遺꾨━?쒕떎.
2. FFLogs `damageDoneAbilities`媛 shared GUID瑜??대뼡 ?⑥쐞濡??⑹궛?섎뒗吏 parity report 湲곗??쇰줈 留욎텣??
3. DRG ?댄썑?먮뒗 媛숈? shared GUID ?⑦꽩???덈뒗 job/action?????덈뒗吏 ?꾩닔 ?뺤씤?쒕떎.
4. production attribution ?섏젙? diagnostics媛 ?뺣━???ㅼ뿉留??쒕떎.

## 2026-03-30

### Auto-hit attribution guardrail
- `CombatState` auto-hit attribution path is now behind a feature flag.
- default: `off`
- property: `pacemeter.experimental.auto-hit-attribution.enabled=true`
- env: `PACE_EXPERIMENTAL_AUTO_HIT_ATTRIBUTION=true`
- keep the modeling scaffolding, but only enable the math explicitly for replay/parity comparison runs.

### FFLogs auto-hit design record
- `docs/FFLogs Buff Allocation Math.docx` 湲곗??쇰줈 guaranteed crit/direct hit 泥섎━ ?ㅺ퀎瑜?遺꾨━?덈떎.
- ?듭떖 臾몄젣: ?꾩옱 `DamageEvent`??`criticalHit/directHit` 寃곌낵留?媛뽮퀬 ?덉뼱?? ?먯뿰 諛쒖깮 / ?몃? 踰꾪봽 ?좊컻 / job mechanic 蹂댁옣?瑜?援щ텇?????녿떎.
- 寃곗젙:
  - `CombatEvent.DamageEvent`??`HitOutcomeContext(autoCrit, autoDirectHit)` 異붽?
  - 媛??꾨뱶??`YES/NO/UNKNOWN`
  - ingestion 湲곕낯媛믪? ?곗꽑 `UNKNOWN`
- ?댁쑀:
  - ACT ?낅젰留뚯쑝濡?auto-hit ?щ?瑜???긽 利앸챸?????놁쑝誘濡? ?ｋ텋由?boolean?쇰줈 ?뺤젙?섏? ?딅뒗??
  - `UNKNOWN`???좎??섎㈃ ?꾩옱 紐⑤뜽怨??명솚?섎뒗 ?곹깭濡??먯쭊 ?댄뻾?????덈떎.
- ?ㅺ퀎 臾몄꽌: `docs/fflogs-auto-hit-design-2026-03-30.md`
- ?대쾲 ?④퀎 踰붿쐞:
  - ?대깽??紐⑤뜽 ?ㅼ틦?대뵫 異붽?
  - replay parser媛 optional `autoCrit`, `autoDirectHit`瑜??쎈룄濡??뺤옣
  - ?ㅼ젣 6.2 auto-hit multiplier attribution 怨꾩궛? ?ㅼ쓬 ?④퀎

### FFLogs auto-hit ingestion wiring
- `CombatState`??auto-crit / auto-dhit 6.2 multiplier attribution 怨꾩궛??諛섏쁺?덈떎.
- `ActIngestionService`???댁젣 `AutoHitCatalog`瑜??듯빐 `job + action + active self-buff` 湲곗??쇰줈 `HitOutcomeContext`瑜?梨꾩슫??
- 珥덇린 catalog??WAR `Inner Release + Fell Cleave / Inner Chaos / Primal Rend` 寃쎈줈瑜??ы븿?쒕떎.
- self-buff active state??ingestion ?대??먯꽌 `BuffApplyRaw` / `BuffRemoveRaw`瑜??듯빐 異붿쟻?쒕떎.
- 誘몃텇瑜??ㅽ궗? 怨꾩냽 `UNKNOWN`???좎??쒕떎.
- ?ㅼ쓬 ?뺤옣 ?ъ씤??
  - 吏곸뾽蹂?guaranteed-hit rules 異붽?
  - action-only rule怨?required-self-buff rule 遺꾨━ 蹂닿컯
  - replay / parity fixture濡??ㅼ젣 FFLogs 李⑥씠 ?ъ륫??
### Auto-hit catalog expansion
- `auto-hit-catalog.json` ?뺤옣:
  - WAR: `Inner Release` + `Fell Cleave`, `Decimate`, `Inner Chaos`, `Chaotic Cyclone`, `Primal Rend`, `Primal Ruination`
  - DRG: `Life Surge` + `WEAPONSKILL`
  - MCH: `Reassemble` + `WEAPONSKILL`
  - SAM: action-only guaranteed crit (`Midare Setsugekka`, `Kaeshi: Setsugekka`, `Ogi Namikiri`, `Kaeshi: Namikiri`, `Tendo Setsugekka`, `Tendo Kaeshi Setsugekka`)
  - DNC: `Flourishing Starfall` + `Starfall Dance`
  - MCH: action-only guaranteed crit/direct hit `Full Metal Field`
  - PCT: action-only guaranteed crit/direct hit `Hammer Stamp`, `Hammer Brush`, `Polishing Hammer`
- `action-tag-catalog.json` 異붽?:
  - ?꾩옱??DRG / MCH weaponskill 吏묓빀??`WEAPONSKILL` ?쒓렇瑜?遺??  - `AutoHitCatalog`媛 `required_action_tags`瑜??쎌뼱 `Life Surge` / `Reassemble`瑜??쇰컲?뷀빐??泥섎━
- 二쇱쓽:
  - action metadata媛 ?꾩쭅 ?꾩껜 吏곸뾽/?ㅽ궗 ?꾨쾾?꾨뒗 ?꾨땲??
  - ?꾩옱 `WEAPONSKILL` ?쒓렇??DRG / MCH 履쎈????쒖옉?덇퀬, ?꾩슂 吏곸뾽??怨꾩냽 ?뺤옣?댁빞 ?쒕떎.

## 2026-03-19

### ?꾩옱 珥덉젏
- ??異뺤? ?ъ쟾??`clearability`吏留? ?ㅼ젣 吏꾪뻾 以묒씤 ?뺣????묒뾽? `rDPS parity`??
- ?꾩옱 二??源껋? `heavy2`? `heavy4` raw ACT 濡쒓렇瑜?FFLogs? 吏곸젒 ?議고빐 DoT attribution ?ㅼ감瑜?以꾩씠??寃껋씠??

### 湲곗????뺤젙
- heavy2 submission `2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt`??FFLogs ?좏깮 fight??`fight=6`???꾨땲??`fight=2`媛 留욌떎.
- `fight=6`? Lindwurm 援ш컙?대씪 ?댁쟾 heavy2 parity媛 ?ㅼ젣蹂대떎 醫뗪쾶 蹂댁???
- metadata? notes??`fight=2`濡??섏젙?덈떎.

### ?뺤씤???ъ떎
- heavy2 FFLogs ability table? ?꾨옒 DoT瑜?`action GUID` 湲곗??쇰줈留??〓뒗??
- `SAM: 1D41`
- `WHM: 4094`
- `SCH: 409C, 9094`
- 利?heavy2 ?붿감??蹂몄쭏? `status/action GUID mismatch`蹂대떎 `?ㅼ젣 tick attribution`?대떎.

### ?대쾲???ｌ? ?섏젙
- `ActIngestionService`?먯꽌 `status=0`?닿퀬 source媛 ?대? ?뚮젮吏?DoT?? target ?꾩껜 active dot濡?遺꾨같?섍린 ?꾩뿉 `same source + same target` active dot瑜??곗꽑 ?ъ슜?섎룄濡?蹂寃쏀뻽??
- ?곸슜 議곌굔? `acceptedBySource == false`??寃쎌슦濡??쒗븳?덈떎.
- 紐⑹쟻? heavy2?먯꽌 `?ы깂/諛깅??꾩궗/?ㅻ━`媛 ?쒕줈??status=0 tick???섎닠 癒밸뜕 援먯감 ?ㅼ뿼??以꾩씠??寃껋씠??

### 愿???뚯뒪??吏꾨떒 異붽?
- `SubmissionParityReportDiagnostics.debugHeavy2Fight6RawDotWindow_printsSamWhmSchDotBuckets`
- `SubmissionParityReportDiagnostics.debugHeavy2Fight6EmittedDotBuckets_printsSamWhmSchActionTargets`
- `ActIngestionServiceTest.dotTick_withUnknownStatusId_onEnemyTarget_prefersTrackedSourceDot`

### heavy2 raw 愿李?
- heavy2 submission window?먯꽌 `SAM/WHM/SCH`??boss DoT??嫄곗쓽 ?꾨? `status=0`?쇰줈 ?ㅼ뼱?⑤떎.
- raw bucket ?덉떆:
- `SAM`: `??釉붾（ 43 ticks / 1,093,189`, `?덈뱶 ??27 ticks / 792,124`
- `SCH`: `??釉붾（ 17 ticks / 693,356`, `?덈뱶 ??11 ticks / 517,934`
- `WHM`: `?덈뱶 ??10 ticks / 230,474`, `??釉붾（ 8 ticks / 109,685`

### ?섏젙 ??heavy2 emitted 愿李?
- `SAM 1D41`: `??釉붾（ 45 ticks / 1,142,410`, `?덈뱶 ??35 ticks / 876,059`
- `SCH 409C`: `??釉붾（ 13 ticks / 464,166`, `?덈뱶 ??13 ticks / 285,904`
- `WHM 4094`: `?덈뱶 ??21 ticks / 428,030`, `??釉붾（ 12 ticks / 223,974`
- ?댁쟾蹂대떎 援먯감 ?ㅼ뿼? 以꾩뿀吏留? `WHM/SCH`??source-known 寃쎈줈留뚯쑝濡쒕뒗 遺議깊븯怨?`SAM`? ?ъ쟾??怨쇰?媛 ?⑥븘 ?덈떎.

### ?섏젙 ??heavy2 ability 珥앺빀
- `SAM 1D41`: local `2,295,413`, FFLogs ability `1,542,816`, delta `+752,597`
- `WHM 4094`: local `967,669`, FFLogs ability `2,640,639`, delta `-1,672,970`
- `SCH 409C`: local `789,865`, FFLogs ability `2,004,891`, delta `-1,215,026`

### ?꾩옱 ?댁꽍
- 諛⑷툑 ?섏젙? `?쒕줈??tick??諛쏆븘媛??臾몄젣`瑜?以꾩?怨? 洹?寃곌낵 ?⑥븘 ?덈뜕 吏꾩쭨 臾몄젣瑜????좊챸?섍쾶 ?쒕윭?덈떎.
- heavy2???ㅼ쓬 ?듭떖? `unknown source / status=0` boss DoT瑜??대뼡 洹쇨굅濡?actor/action??蹂듭썝?좎???
- ?뱁엳 `WHM/SCH` ?꾨씫 ?遺遺꾩? source-known 寃쎈줈媛 ?꾨땲??unknown-source 蹂듭썝 遺議깆씪 媛?μ꽦???믩떎.
- `SAM` 怨쇰???unknown-source 蹂듭썝 ?먮뒗 prison target ?ы븿 諛⑹떇?먯꽌 異붽? 寃?좉? ?꾩슂?섎떎.

### 理쒖떊 heavy2 parity ?곹깭
- `MAPE = 0.0625`
- `p95 = 0.1244`
- `max = 0.1249`
- 二쇱슂 ?붿감:
- `PLD +3029.6`
- `WHM -2800.7`
- `DRG +2141.0`
- `SCH -1795.6`
- `SAM +1046.9`

### ?ㅼ쓬 ?묒뾽
1. heavy2?먯꽌 boss target 湲곗? `unknown source + status=0` DoT ????곕줈 吏묎퀎?쒕떎.
2. `WHM/SCH/SAM`蹂꾨줈 unknown-source attribution 洹쇨굅瑜?actor/action ?⑥쐞濡??ㅼ떆 ?議고븳??
3. prison target(`40001729`)??parity 吏묎퀎???대뼸寃?諛섏쁺?좎? 蹂꾨룄 ?뺤씤?쒕떎.
4. ?섏젙 ??heavy2/heavy4/Lindwurm瑜??ㅼ떆 媛숈씠 痢≪젙?쒕떎.

## 2026-03-20

### 紐⑺몴
- 理쒓렐 湲됰씫??parity瑜??먯씤 遺꾨━ ??利됱떆 蹂듦뎄.
- ?ъ떆??媛?ν븳 湲곗???臾몄꽌 媛깆떊.

### ?먯씤
- `ActIngestionService`???ㅽ뿕 寃쎈줈媛 ?⑥븘 ?덉뿀??
  - `wouldEmitDotDamage()`?먯꽌 `resolveTrackedSourceDots()`瑜??덉슜 寃쎈줈濡??ъ슜
  - `emitDotDamage()`??`unknownStatusDot` 泥섎━?먯꽌 `acceptedBySource` 議곌굔 遺꾧린 + tracked-source ?곗꽑 遺꾨같
- ??寃쎈줈媛 heavy2/heavy4/Lindwurm ?꾩껜?먯꽌 rDPS attribution???쒓끝??怨듯넻 ?낇솕瑜??좊컻.

### 議곗튂
- ???ㅽ뿕 遺꾧린 ?쒓굅/濡ㅻ갚:
  - `wouldEmitDotDamage()`??tracked-source ?덉슜 遺꾧린 ?쒓굅
  - `emitDotDamage()` unknown-status 泥섎━ 蹂듭썝:
    - snapshot redistribution ?곗꽑
    - tracked target dots 蹂댁“
    - ?댄썑 accepted-by-source 寃쎈줈
  - 誘몄궗??`resolveTrackedSourceDots()` ?쒓굅
- ?뚯뒪???먮났:
  - `ActIngestionServiceTest.dotTick_withUnknownStatusId_onEnemyTarget_prefersTrackedSourceDot`
    - ?덉젙 寃쎈줈 湲곕?媛?2嫄?遺꾨같)?쇰줈 ?뺤젙
- diagnostics assertion ?뺣━:
  - heavy2 selected fight assertion `2 -> 6` ?쇨큵 ?뺤젙
  - ?좉퇋 吏꾨떒 ?좎?:
    - `debugHeavy2Fight6ActorAbilityTargetParity_printsSamSchWhmTargetDeltas`

### 寃利?寃곌낵
- ?뚭? ?뚯뒪???듦낵:
  - `ActIngestionServiceTest`
  - `SubmissionParityRegressionGateTest`
- parity ?ъ륫??3 submissions / 24 actors):
  - `mape=0.01184`
  - `p95=0.02804`
  - `max=0.03426`
  - `outlierRatio=0.0`
  - gate: `pass=true`
- submission蹂?
  - heavy4: `MAPE=0.01130`, `p95=0.02338`, `max=0.02826`
  - heavy2: `MAPE=0.01606`, `p95=0.03166`, `max=0.03426`
  - lindwurm: `MAPE=0.00817`, `p95=0.01530`, `max=0.01604`

### 愿痢?硫붾え
- heavy2 `SAM/SCH/WHM` 異뺤뿉??`eventsByAbility`??0 ?먮뒗 怨쇱냼媛 鍮덈쾲???먯씤 湲곗??쇰줈 遺?곹빀.
- ?먯씤 ?먮떒? 怨꾩냽 `abilities table` ?곗꽑, `eventsByAbility`??蹂댁“ 利앷굅濡쒕쭔 ?ъ슜.

### 異붽? ?뺣━ (2026-03-20 ?꾩냽)
- `known-status + unknown-source` attribution 以묐났 援ы쁽??resolver ?⑥쑝濡??듯빀?덈떎.
  - `UnknownStatusDotAttributionResolver.resolveKnownStatusUnknownSourceAttribution()` 異붽?
  - `ActIngestionService`??resolver ?몄텧留??대떦?섎룄濡??⑥닚??
- 移댄깉濡쒓렇 愿由??ы쁽??媛뺥솕:
  - `scripts/build_dot_attribution_catalog.py` 異붽? (ACT Definitions 湲곕컲 ?앹꽦)
  - `docs/dot-attribution-catalog-maintenance.md` 異붽?
  - `DotAttributionCatalogTest` 異붽?濡?移댄깉濡쒓렇 臾닿껐???뚭? 諛⑹?
- ?뚭? ?뺤씤:
  - ingestion/resolver/catalog ?뚯뒪???듦낵
  - parity ?ъ륫??
    - rollup `mape=0.01184`, `p95=0.02804`, `max=0.03426`, gate `pass=true`

### heavy2 DoT 異붽? 吏꾨떒/?ㅽ뿕 (2026-03-20)
- ?좉퇋 吏꾨떒 異붽?:
  - `debugHeavy2Fight6SnapshotWeightVsAbilityTotals_printsSamSchWhmShares`
- 吏꾨떒 寃곌낵(heavy2 fight6, primary target=`4000111D`):
  - raw status=0 source DoT:
    - `?ы깂=1,851,723`, `?ㅻ━=831,747`, `諛깅??꾩궗=0`
  - snapshot weight share:
    - `SAM(1D41)=0.4215`, `SCH(409C)=0.2839`, `WHM(4094)=0.2714`
  - FFLogs ability total share(?숈씪 3異?:
    - `SAM??2%`, `SCH??6%`, `WHM??2%`
  - ?댁꽍:
    - snapshot float 鍮꾩쑉??洹몃?濡?attribution weight濡??곕뒗 媛?뺤씠 heavy2 異뺢낵 ?ш쾶 遺덉씪移?

- ?쒕룄(?ㅽ뙣, 濡ㅻ갚 ?꾨즺):
  - snapshot redistribution??`actor 洹좊벑 + actor ?대? status 鍮꾩쑉`濡?蹂寃?
  - 寃곌낵:
    - rollup `p95 0.028 -> 0.035`, gate `pass=false`
    - heavy4源뚯? ?숈떆 ?낇솕
  - 議곗튂:
    - 利됱떆 濡ㅻ갚, baseline 蹂듦뎄(`p95??.02804`, gate `pass=true`)

### heavy2 attribution ?뺣????쒕룄 (2026-03-20 異붽?)
- ?곸슜 蹂寃?
  - `ActIngestionService.resolveSnapshotRedistribution()`?먯꽌 snapshot 遺꾨같 媛以묒튂瑜?
    `activeTargetDots`(異붿쟻 以묒씤 DoT) 援먯쭛???곗꽑?쇰줈 ?좏깮?섎룄濡?蹂닿컯.
  - 援먯쭛?⑹씠 鍮꾩뼱?덉쓣 ?뚮쭔 湲곗〈 snapshot ?꾩껜 媛以묒튂 fallback ?좎?.
  - `ActIngestionServiceTest.dotTick_withUnknownStatusId_usesActiveTrackedDotsSubsetBeforeFullSnapshotFallback`
    ?뚯뒪??異붽?.
- ?곸슜 蹂寃?
  - `ActLineParser` type `37` ?뚯떛???뺣젹 湲곕컲?쇰줈 ?섏젙:
    - 湲곗〈: ?몃뜳??1移몄뵫 ?щ씪?대뵫(寃뱀묠 ?댁꽍 媛??
    - 蹂寃? `effectCount(p[18])` 湲곗?, `p[19]`遺??4移??⑥쐞濡??뚯떛
  - type `37`???ㅼ쨷 status/source ?좏샇瑜?`DotStatusSignalRaw.signals`濡?蹂닿??섎룄濡??뺤옣.
  - `ActIngestionService.noteDotStatusSignal()`? ?ㅼ쨷 ?좏샇瑜?紐⑤몢 evidence濡??곸옱.
  - parser/ingestion ?뚯뒪??蹂닿컯:
    - `ActLineParserTest.parse_dotStatusSignal_typeCode37_readsAlignedEffectSlots`
    - `ActLineParserTest.parse_dotStatusSignal_typeCode37_ignoresMalformedOverlappingSlots`
- 寃利?寃곌낵:
  - ?⑥쐞 ?뚯뒪???듦낵.
  - parity ?ъ륫??寃곌낵??baseline怨??ㅼ쭏?곸쑝濡??숈씪:
    - rollup `mape??.01184`, `p95??.02804`, `max??.03426`, gate `pass=true`
    - heavy2 `p95??.03166`濡?媛쒖꽑??誘몃?.
- 異붽? 吏꾨떒:
  - `debugHeavy2Fight6ActorAbilityTargetParity_printsSamSchWhmTargetDeltas`?먯꽌
    FFLogs `eventsByAbility`媛 `SCH/WHM` DoT GUID(409C/4094)?????`0`?쇰줈 愿痢〓맖.
  - ?댁꽍:
    - heavy2??DoT attribution ?먯씤 洹쒕챸?먮뒗 `eventsByAbility`媛 ?좊ː 湲곗????꾨땲硫?
      `abilities total` 以묒떖 鍮꾧탳瑜??좎??댁빞 ??

### heavy2/heavy4 ?숈떆 媛쒖꽑 ?⑥튂 (2026-03-20 異붽? 2)
- ?먯씤 洹쇨굅:
  - heavy2 fight6 boss target(`4000111D`)??raw `37` status signal 吏묎퀎:
    - `WHM(074F)`/`SCH(0767,0F2B)` ?좏샇媛 `SAM(04CC)`蹂대떎 ?⑥뵮 ?먯＜ 愿痢〓맖.
  - 湲곗〈? snapshot float留뚯쑝濡?遺꾨같??SAM 怨쇨??띿씠 諛섎났??
- ?곸슜 蹂寃?
  - `ActIngestionService`??`recentStatusSignalsByTarget` 罹먯떆 異붽?.
  - `resolveSnapshotRedistribution()`?먯꽌 遺꾨같 媛以묒튂瑜?
    `snapshot weight` ?⑤룆???꾨땲??
    `snapshot` + `理쒓렐 3.5珥?type37 signal 鍮덈룄` ?쇳빀?쇰줈 議곗젙.
    - 釉붾젋??怨꾩닔: `STATUS_SIGNAL_WEIGHT_BLEND_ALPHA = 0.80`
    - signal key ??湲곕컲 confidence ?ㅼ????곸슜:
      - 1媛?key???뚮뒗 ?쏀븳 蹂댁젙(?덈컲 ?뚰뙆)
      - 2媛??댁긽 key???뚮뒗 ?꾩껜 ?뚰뙆 ?곸슜
  - 湲곗〈 `activeTargetDots` 援먯쭛???곗꽑 濡쒖쭅? ?좎?.
  - type37 ?뚯꽌 ?뺣젹 援먯젙 + ?ㅼ쨷 ?좏샇 ?뚯떛怨?寃고빀?섏뼱 ?④낵 諛쒗쐶.
- ?뚯뒪??
  - `ActIngestionServiceTest.dotTick_withUnknownStatusId_blendsRecentType37SignalsIntoSnapshotRedistribution` 異붽?.
  - 愿??parser/ingestion 湲곗〈 ?뚯뒪???듦낵.
- parity ?ъ륫??寃곌낵(3 submissions / 24 actors):
  - ?댁쟾: `mape=0.01184`, `p95=0.02804`, `max=0.03426`
  - 蹂寃??? `mape=0.01128`, `p95=0.02529`, `max=0.03080`
  - gate: `pass=true` ?좎?
- submission蹂?蹂??
  - heavy2: `MAPE 0.01606 -> 0.01379`, `p95 0.03166 -> 0.02464`, `max 0.03426 -> 0.02578`
  - heavy4: `MAPE 0.01130 -> 0.01189`, `p95 0.02338 -> 0.02508`, `max 0.02826 -> 0.03080`
  - lindwurm: ?ъ떎???숈씪

### auto-attack DoT ?ㅺ????쒓굅 + gate ?덉젙??(2026-03-20 異붽? 3)
- ?먯씤 吏꾨떒:
  - heavy4 PLD(`?ъ쓽`)?먯꽌 `DoT#17`??`457,379`濡?愿痢?
  - guid delta 湲곗? `0x17(Attack)` 怨쇱쭛怨꾧? `+252,638`源뚯? 諛쒖깮.
  - 洹쇰낯 ?먯씤: `dot-attribution-catalog.json`??`status 248 -> action 23(0x17)` 留ㅽ븨.
- ?곸슜 蹂寃?
  - `DotAttributionCatalog`?먯꽌 DoT 留ㅽ븨 ??auto-attack action id瑜??꾩뿭 李⑤떒:
    - invalid set: `{0x7, 0x17}`
    - `statusToAction`, `actionToStatuses`, `applicationActionsByJob`, `statusIdsByJob`???숈씪 洹쒖튃 ?곸슜.
  - `scripts/build_dot_attribution_catalog.py`?먮룄 ?숈씪 洹쒖튃 諛섏쁺(?ъ깮?????ъ쑀??諛⑹?).
  - 移댄깉濡쒓렇 由ъ냼?ㅼ뿉??PLD(19) ?섎せ??mapping ?쒓굅(鍮?entry濡??뺣━).
  - ?뚭? ?뚯뒪??異붽?:
    - `DotAttributionCatalogTest.catalog_excludesAutoAttackActionIdsFromDotMappings`
  - `SubmissionParityRegressionGateTest`??FFLogs credential 二쇱엯??diagnostics? ?숈씪 諛⑹떇?쇰줈 怨좎젙:
    - `FflogsTokenStore` reflection 二쇱엯(`clientId/clientSecret`)
    - `FflogsApiClient.defaultPartition` 二쇱엯
- 寃利?
  - `DotAttributionCatalogTest` ?듦낵
  - `SubmissionParityRegressionGateTest` ?듦낵
  - `./scripts/parity_repro_check.sh` ?듦낵, gate `pass=true`
- 理쒖떊 ?섏튂(3 submissions / 24 actors):
  - rollup:
    - `mape=0.01026`
    - `p95=0.01649`
    - `max=0.02225`
  - submissions:
    - heavy4: `MAPE=0.01101`, `p95=0.02025`, `max=0.02225`
    - lindwurm: `MAPE=0.00818`, `p95=0.01530`, `max=0.01604`
    - heavy2: `MAPE=0.01160`, `p95=0.01606`, `max=0.01616`

### ?좊ː??媛뺥솕 1李?(2026-03-20 異붽? 4)
- ingestion ?덉쟾?μ튂:
  - `ActIngestionService` DoT emit 怨듯넻 寃쎈줈??invalid action id ?쒕∼ 媛??異붽?.
  - ??? `0x07`, `0x17` (auto-attack 怨꾩뿴)
  - 遺꾨같/洹??紐⑤뱺 寃쎈줈( snapshot redistribution, tracked redistribution, source-attributed )???숈씪 ?곸슜.
- 理쒖냼 ?뚭? ?뚯뒪??
  - `ActIngestionServiceTest.dotTick_withAutoAttackLikeStatusId_dropsInvalidDotAction`
- 遺꾩꽍 ?묐떟 媛쒖꽑:
  - `SubmissionParityQualityService.ActorQualityEntry`??`topSkillDeltas` 異붽?.
  - worst actor留덈떎 ?곸쐞 5媛?skill delta瑜??④퍡 諛섑솚(媛????local guid hex ?ы븿).
  - 湲곗〈 `/api/debug/parity/quality` ?묐떟?먯꽌 諛붾줈 ?뺤씤 媛??
- 寃利?
  - `ActIngestionServiceTest`, `SubmissionParityQualityServiceTest`, `DotAttributionCatalogTest`, `SubmissionParityRegressionGateTest` ?듦낵.
  - `./scripts/parity_repro_check.sh` ?ъ륫??
    - rollup `mape=0.01026`, `p95=0.01649`, `max=0.02225`, gate `pass=true` ?좎?.

### 吏꾨떒 ?뺣???+ known-status unknown-source fallback (2026-03-20 異붽? 5)
- 吏꾨떒 ?곗씠??援ъ“ 媛쒖꽑:
  - `SubmissionParityReport.SkillBreakdownEntry`??`skillGuid` 異붽?.
  - local/FFLogs top skill 紐⑤몢 GUID瑜?梨꾩썙??`topSkillDeltas`瑜?GUID ?곗꽑 留ㅼ묶.
  - ?숈씪 GUID媛 local?먯꽌 ?щ윭 ?뷀듃由щ줈 ?ㅼ뼱?ㅻ뒗 寃쎌슦 ?⑹궛 泥섎━(`put overwrite` ?쒓굅).
- ingestion ?덉쟾 fallback 異붽?:
  - ??? `known status + unknown source` DoT.
  - 湲곗〈 evidence 湲곕컲 蹂듭썝 ?ㅽ뙣 ??
    ?뚰떚 ???대떦 status瑜?媛吏????덈뒗 job ?꾨낫媛 ?뺥솗??1紐낆씪 ?뚮쭔 source/action 洹??
  - ?꾨낫媛 2紐??댁긽?대㈃ fallback 誘몄쟻??怨쇰낫??諛⑹?).
- ?뚯뒪??
  - `ActIngestionServiceTest.dotTick_withKnownStatusAndUnknownSource_usesUniqueJobFallbackWithoutRecentEvidence`
  - `ActIngestionServiceTest.dotTick_withKnownStatusAndUnknownSource_doesNotFallbackWhenMatchingJobIsAmbiguous`
  - `ActIngestionServiceTest`, `SubmissionParityQualityServiceTest` ?듦낵.
- ?ъ륫??
  - `./scripts/parity_repro_check.sh` 湲곗? rollup ?섏튂??baseline怨??숈씪:
    - `mape=0.0102602959`, `p95=0.0164872692`, `max=0.0222490071`, gate `pass=true`.
- 寃곕줎:
  - ?대쾲 蹂寃쎌? ?섏튂 媛쒖꽑蹂대떎 吏꾨떒 ?뺥솗???덉쟾??媛쒖꽑 ?④퀎.
  - ?댄썑 DoT 寃곗넀 異??? `64AC`, `9094`, `4094`)???ㅼ젣 ?먯씤蹂??섏젙??諛붾줈 ?ъ슜?????덉쓬.

### ?ㅽ뙣 ?쒕룄 濡ㅻ갚: signal-only key 遺꾨같 ?뺤옣 (2026-03-20 異붽? 6)
- ?쒕룄:
  - `applyStatusSignalWeighting()`?먯꽌 snapshot key媛 ?놁뼱??
    `active dot + recent type37 signal`?대㈃ 遺꾨같 ?꾨낫濡??뺤옣.
- 寃곌낵:
  - parity ?낇솕 ?뺤씤:
    - rollup `mape=0.0102603 -> 0.0105053`
    - rollup `p95=0.0164873 -> 0.0218130`
    - rollup `max=0.0222490 -> 0.0237461`
    - heavy2 `MAPE=0.0115958 -> 0.0126042`, `p95=0.0160617 -> 0.0209838`
- 議곗튂:
  - 利됱떆 濡ㅻ갚(肄붾뱶/?뚯뒪??紐⑤몢 蹂듦뎄).
  - `./scripts/parity_repro_check.sh`濡?baseline ?щ났援??뺤씤:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`, gate `pass=true`.

### ?ㅽ뙣 ?쒕룄 濡ㅻ갚: active/snapshot 援먯쭛???놁쓣 ??snapshot fallback 李⑤떒 (2026-03-20 異붽? 7)
- ?쒕룄:
  - `selectSnapshotRedistributionWeights()`?먯꽌
    active DoT媛 議댁옱?섏?留?snapshot key? 援먯쭛?⑹씠 ?놁쑝硫?
    `fallbackWeights`瑜??곗? ?딄퀬 鍮?留?諛섑솚?섎룄濡?蹂寃?
  - ?섎룄: `status=0` 怨쇨????뱁엳 SAM `1D41`) 異뺤냼.
- 寃곌낵:
  - `SubmissionParityRegressionGateTest` 利됱떆 ?ㅽ뙣.
  - gate ?섏튂:
    - `p95 APE = 0.4471232655894308` (紐⑺몴 `<= 0.03` ?鍮?????낇솕)
  - ?먯씤:
    - `wouldEmitDotDamage()` 寃쎈줈媛 snapshot redistribution 鍮꾩뼱 ?덉쑝硫?false瑜?諛섑솚?섎뒗 援ъ“??
      ?대떦 遺꾧린?먯꽌 DoT ?대깽???먯껜媛 ????꾨씫??
- 議곗튂:
  - 肄붾뱶/?뚯뒪???꾨? 利됱떆 濡ㅻ갚.
  - baseline ?ы솗??
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`, gate `pass=true`.
- 寃곕줎:
  - ???묎렐? 援ъ“?곸쑝濡??대깽???꾨씫 ?꾪뿕??而ㅼ꽌 ?ъ떆??湲덉?.

### snapshot weight smoothing ?꾩엯 (2026-03-20 異붽? 8)
- 諛곌꼍:
  - heavy2 `status=0` 遺꾨같?먯꽌 SAM?쇰줈 怨쇱쭛以묐릺??寃쏀뼢???덉뼱
    snapshot 媛以묒튂??洹밸떒媛믪쓣 ?꾪솕?섎뒗 ?쇰컲??諛⑸쾿???ㅽ뿕.
- ?곸슜:
  - `ActIngestionService.noteStatusSnapshot()`?먯꽌
    snapshot float瑜?諛붾줈 ?곗? ?딄퀬 `pow(value, gamma)`濡?蹂???????
  - ?꾩옱 梨꾪깮 媛? `STATUS_SNAPSHOT_WEIGHT_GAMMA = 0.85`.
- 寃利?
  - `ActIngestionServiceTest` ?듦낵
  - `SubmissionParityRegressionGateTest` ?듦낵
  - `./scripts/parity_repro_check.sh` ?듦낵
- ?섏튂 蹂??(baseline ?鍮?:
  - rollup:
    - `mape: 0.0102603 -> 0.0099661` (媛쒖꽑)
    - `p95: 0.0164873 -> 0.0169417` (?뚰룺 ?낇솕)
    - `max: 0.0222490 -> 0.0220786` (媛쒖꽑)
  - heavy2:
    - `MAPE: 0.0115958 -> 0.0107990` (媛쒖꽑)
    - `p95: 0.0160617 -> 0.0159162` (媛쒖꽑)
    - `max: 0.0161569 -> 0.0168157` (?뚰룺 ?낇솕)
  - heavy4:
    - `MAPE: 0.0110102 -> 0.0109641` (媛쒖꽑)
    - `p95: 0.0202528 -> 0.0202885` (洹쇱냼 ?낇솕)
    - `max: 0.0222490 -> 0.0220786` (媛쒖꽑)

### ?ㅽ뙣 ?쒕룄 濡ㅻ갚: status-signal ?щ텇諛??덈룄???뺣? (2026-03-20 異붽? 9)
- ?쒕룄:
  - `STATUS_SIGNAL_REDISTRIBUTION_WINDOW`瑜?`3.5s -> 15s`濡??뺣??댁꽌
    `status=0` DoT 遺꾨같??signal 諛섏쁺 鍮꾩쨷???섎┝.
- 寃곌낵:
  - ?꾩뿭 吏???낇솕 ?뺤씤:
    - rollup `mape: 0.0102603 -> 0.0108067`
    - rollup `p95: 0.0164873 -> 0.0244171`
    - rollup `max: 0.0222490 -> 0.0301645`
  - ?뱁엳 heavy2/4??p95媛 ?④퍡 ?낇솕?섏뼱 梨꾪깮 遺덇?.
- 議곗튂:
  - 利됱떆 濡ㅻ갚 ?꾨즺.
  - ?ъ륫?뺤쑝濡?baseline 蹂듦? ?뺤씤:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`.

### ?ㅽ뙣 ?쒕룄 濡ㅻ갚: strict signal 遺????fallback signal 釉붾젋??異붽? (2026-03-20 異붽? 10)
- ?쒕룄:
  - `3.5s` strict signal???놁쓣 ??`15s` fallback signal????? alpha濡?蹂댁“ 諛섏쁺?섎뒗 寃쎈줈 異붽?.
  - ?섎룄: SAM `1D41` 怨쇰같遺꾩쓣 ?꾨쭔????텛??湲곗〈 ?뚭?瑜??쇳븿.
- 寃곌낵:
  - heavy2 ?쇰? actor ?섏튂???뚰룺 媛쒖꽑?섏뿀?쇰굹,
    ?꾩뿭 p95??baseline蹂대떎 ?낇솕:
    - rollup `mape=0.0101859`, `p95=0.0169692`, `max=0.0222639`
  - 紐⑺몴(?꾩뿭 ?덉젙??+ ??submission ?숈떆 媛쒖꽑) 誘몄땐議?
- 議곗튂:
  - ?대떦 肄붾뱶 ?꾨? 濡ㅻ갚.
  - 寃곕줎: signal-window 怨꾩뿴 ?쒕떇? ?꾩옱 ?곗씠?곗뀑?먯꽌 ?덉젙?곸쑝濡??섎졃?섏? ?딆븘 ?곗꽑?쒖쐞 ?쒖쇅.

### ?ㅽ뙣 ?쒕룄 濡ㅻ갚: source-known status=0 DoT瑜?active source dot濡??곗꽑 洹??(2026-03-20 異붽? 11)
- ?쒕룄:
  - `status=0`?닿퀬 source媛 ?뚰떚?먯쑝濡?紐낇솗??寃쎌슦,
    snapshot redistribution ?꾩뿉 source??active DoT ?몃옓留??곗꽑 ?ъ슜.
- 寃곌낵:
  - ?꾩뿭 ?뚭? 寃뚯씠??利됱떆 ?ㅽ뙣:
    - rollup `mape=0.02349`, `p95=0.07073`, `max=0.10729`, `outlierRatio=0.125`
  - heavy2 湲곗? `max APE`媛 `10%+`濡?湲됰벑.
- 議곗튂:
  - 肄붾뱶/?뚯뒪??利됱떆 濡ㅻ갚.
  - baseline ?щ났援??뺤씤:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`
- 寃곕줎:
  - source ?곗꽑 ?⑥젙? ?ㅼ쭅???ㅻ줈洹??쇰컲?붿뿉???꾪뿕.
  - ?숈씪 ?묎렐 ?ъ떆??湲덉?.

### ?ㅽ뙣 ?쒕룄 濡ㅻ갚: rules-empty source(status=0) snapshot ?щ텇諛?李⑤떒 (2026-03-20 異붽? 12)
- ?쒕룄:
  - `status=0` DoT?먯꽌 source媛 ?뚰떚?먯쑝濡?紐낇솗?섍퀬,
    ?대떦 source job??unknown-status 異붿쟻 洹쒖튃??鍮꾩뼱 ?덉쑝硫?`application/status 紐⑤몢 ?놁쓬`)
    snapshot redistribution ?먯껜瑜??섑뻾?섏? ?딅룄濡?李⑤떒.
- 寃곌낵:
  - ?꾩뿭 ?덉쭏 湲됯꺽???낇솕:
    - rollup `mape=0.01735`, `p95=0.06098`, `max=0.07466`, `outlierRatio=0.1667`
  - heavy4/heavy2 紐⑤몢 5% 珥덇낵 ?ㅼ감 actor ?ㅼ닔 諛쒖깮.
- 議곗튂:
  - 肄붾뱶 利됱떆 濡ㅻ갚 ??baseline 蹂듦뎄 ?뺤씤:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`.
- 寃곕줎:
  - rules-empty source 李⑤떒? ?꾩옱 ingestion 援ъ“?먯꽌 ?좏슚??遺꾨같 ?대깽?멸퉴吏 ?먯떎?쒖폒 ?뚭?.
  - ?숈씪 ?묎렐 ?ъ떆??湲덉?.

### ?ㅽ뙣 ?쒕룄 濡ㅻ갚: source ?⑥씪 active-dot + recent evidence ?곗꽑 洹??(2026-03-20 異붽? 13)
- ?쒕룄:
  - `status=0` DoT?먯꽌 source媛 紐낇솗?섍퀬,
    ?대떦 source/target??active dot媛 ?뺥솗??1媛쒖씠硫?recent application/status 洹쇨굅媛 ?덉쑝硫?
    snapshot 遺꾨같蹂대떎 source ?⑥씪 洹?띿쓣 ?곗꽑 ?곸슜.
- 寃곌낵:
  - ?뚭? 寃뚯씠???ㅽ뙣:
    - rollup `mape=0.02004`, `p95=0.06013`, `max=0.09345`, `outlierRatio=0.125`
  - ?뱁엳 heavy2 `MAPE=0.03427`源뚯? ?곸듅.
- 議곗튂:
  - 肄붾뱶/?뚯뒪??利됱떆 濡ㅻ갚.
  - baseline ?щ났援??뺤씤:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`.
- 寃곕줎:
  - source ?⑥씪 洹??洹쒖튃? ?쇰? 吏곴???耳?댁뒪瑜?留욎떠???꾩껜 遺꾨같 洹좏삎???ш쾶 源⑤쑉由?
  - ?숈씪 ?묎렐 ?ъ떆??湲덉?.

### ?ㅽ뿕: snapshot redistribution??recent application 利앷굅 媛먯뇿 異붽? (2026-03-20 異붽? 14)
- ?쒕룄:
  - `ActIngestionService.resolveSnapshotRedistribution()`?먯꽌
    `status=0` 遺꾨같 媛以묒튂??`recent application/status` 洹쇨굅 湲곕컲 媛먯뇿瑜?異붽?.
  - 洹쇨굅媛 ?녿뒗 key??`0.55` 諛? ?ㅻ옒??洹쇨굅??`0.70~1.0` ?좏삎 蹂댁젙.
- 寃곌낵:
  - rollup 吏??trade-off 諛쒖깮:
    - `mape: 0.0102603 -> 0.0101189` (媛쒖꽑)
    - `p95: 0.0164873 -> 0.0176408` (?낇솕)
    - `max: 0.0222490 -> 0.0196319` (媛쒖꽑)
  - heavy4/ heavy2?먯꽌 DRG 履?怨쇨??띿씠 ?섏뼱??`p95`媛 遺덉븞??
- 寃곕줎:
  - ?꾨㈃ ?곸슜? ?덉젙 紐⑺몴(`p95`)瑜??댁묠.
  - ?⑤룆 梨꾪깮 遺덇?.

### ?ㅽ뿕: recent application 利앷굅 媛먯뇿瑜?"signal 遺??援ш컙"?쇰줈 ?쒗븳 (2026-03-20 異붽? 15)
- ?쒕룄:
  - ???ㅽ뿕(異붽? 14)??異뺤냼?? `type 37` 湲곕컲 recent status signal???녿뒗 援ш컙?먯꽌留?媛먯뇿 ?곸슜.
- 寃곌낵:
  - ?꾩뿭 吏??
    - `mape: 0.0102603 -> 0.0100973` (媛쒖꽑)
    - `p95: 0.0164873 -> 0.0174068` (?낇솕)
    - `max: 0.0222490 -> 0.0206143` (媛쒖꽑)
  - ?붿빟: `mape/max`??醫뗭븘?몃룄 `p95`媛 ?낇솕?섎뒗 ?⑦꽩 諛섎났.
- 寃곕줎:
  - ?꾩옱 ?곗씠?곗뀑 湲곗??쇰줈 "利앷굅 媛먯뇿" 怨꾩뿴 ?쒕떇? ?쇨? ?섎졃 ?ㅽ뙣.
  - ?ㅼ쓬 ?④퀎??媛以묒튂 ?쒕떇???꾨땲??
    1) FFLogs skill bucket/type ?뺢퇋??李⑥씠 遺꾨━ 吏꾨떒,
    2) DoT lifecycle ?꾨씫(?뱁엳 `64AC/9094/4094`) ?먯씤 遺꾪빐
    瑜?癒쇱? ?섑뻾?댁빞 ?쒕떎.

### 吏꾨떒 異붽?: status=0 DoT 洹쇨굅 而ㅻ쾭由ъ?/?寃?誘몄뒪留ㅼ튂 怨꾩닔??(2026-03-20 異붽? 16)
- 異붽?:
  - `SubmissionParityReportDiagnostics.debugStatus0DotEvidenceCoverage_acrossSubmissions_printsTargetMismatchRates`
  - selected fight window ??`status=0` + known source DoT?????
    `exact(source+target) / sourceOnly / noEvidence`瑜?異쒕젰.
- 寃곌낵:
  - heavy4(fight2): `total=771`, `exact=48`, `sourceOnly=290`, `sourceOnlyTargetMismatch=290`, `noEvidence=433`
  - heavy2(fight6): `total=648`, `exact=86`, `sourceOnly=351`, `sourceOnlyTargetMismatch=351`, `noEvidence=211`
  - lindwurm(fight8): `status=0 known-source DoT = 0`
- ?댁꽍:
  - 臾몄젣媛 ?섎뒗 濡쒓렇?먯꽌 `source-only` 洹쇨굅???ъ떎???꾨? targetId 遺덉씪移?
  - 利? 怨쇨굅??source-only fallback???뚭?瑜?留뚮뱺 ?댁쑀媛 ?뺣웾?곸쑝濡??뺤씤??

### ?ㅽ뿕: targetName ?숇벑??湲곕컲 source fallback/?깃? ?寃?洹쇨굅 ?곌껐 (2026-03-20 異붽? 17)
- ?쒕룄:
  - `ActIngestionService`??targetId 遺덉씪移??곹솴?먯꽌 targetName ?숇벑 ???쒗븳?곸쑝濡?洹쇨굅瑜??댁뼱諛쏅뒗 寃쎈줈 異붽?.
  - `NetworkAbilityRaw/DotTickRaw/BuffApplyRaw` 泥섎━ ??target ?대쫫 罹먯떆 蹂닿컯.
- 寃곌낵:
  - `./scripts/parity_repro_check.sh` 湲곗? ?꾩뿭 ?섏튂 蹂???놁쓬:
    - `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490` (baseline ?숈씪)
- 寃곕줎:
  - ?꾩옱 援ы쁽 寃쎈줈?먯꽌 ?대떦 fallback? ?ㅼ쭏 ?곹뼢???녾굅??諛쒗솕 議곌굔??誘몄땐議?
  - ?ㅼ쓬? ?숈씪 蹂댁젙 諛섎났???꾨땲??
    `status=0` ?대깽?몃? action/status濡?洹?랁븷 洹쇨굅瑜?replay window ?댁뿉??吏곸젒 異붿쟻?섎뒗 ?ㅽ봽?쇱씤 留ㅼ묶 ?덉씠?대? 癒쇱? 留뚮뱾 ?꾩슂媛 ?덉쓬.

### 吏꾨떒: selected fight 湲곗? type37 ?좏샇 而ㅻ쾭由ъ? 怨꾩닔??(2026-03-20 異붽? 18)
- 異붽?:
  - `SubmissionParityReportDiagnostics.debugType37SignalCoverage_acrossSubmissions_printsSelectedFightCounts`
- 寃곌낵:
  - heavy4(fight2):
    - `included37=4196`, `parsedLines=48`, `trackedSlots=48`
    - `trackedByStatus={767=15, A9F=15, A38=14, F2B=4}`
  - heavy2(fight6):
    - `included37=4395`, `parsedLines=55`, `trackedSlots=40`
    - `trackedByStatus={A9F=17, 767=14, 4CC=5, F2B=4}`
  - lindwurm(fight8):
    - `included37=4399`, `parsedLines=45`, `trackedSlots=31`
    - `trackedByStatus={A9F=15, 767=12, F2B=4}`
- ?댁꽍:
  - type37 ?뚯꽌 ?먯껜媛 ?듭떖 ?꾨씫 ?먯씤? ?꾨떂.
  - selected fight???좏슚 tracked signal ???먯껜媛 ?묒븘?? `status=0` ???tick???⑤룆 ?ㅻ챸異뺤쑝濡쒕뒗 遺議?

### ?ㅽ뙣 ?쒕룄 濡ㅻ갚: status0 source ?뚰듃 媛以묒튂 ?곹뼢 (2026-03-20 異붽? 19)
- ?쒕룄:
  - snapshot redistribution??source ?뚰듃 媛以묒튂 `STATUS0_SOURCE_HINT_WEIGHT`瑜?`1.0 -> 2.5` ?곹뼢.
- 寃곌낵:
  - ?뚭? 寃뚯씠???ㅽ뙣:
    - lindwurm submission `p95=0.021316...`濡?gate 湲곗? 珥덇낵.
- 議곗튂:
  - 利됱떆 濡ㅻ갚 ??baseline ?ы솗??
  - `./scripts/parity_repro_check.sh`:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`, gate `pass=true`.
- 寃곕줎:
  - source ?뚰듃 ?곹뼢? ?꾩뿭 ?쇰컲?붿뿉 遺?뺤쟻.
  - ?숈씪 怨꾩뿴 媛以묒튂 ?쒕떇 ?ъ떆??湲덉?.

### ?ㅽ뙣 ?쒕룄 濡ㅻ갚: strict corroborated source ?좉???(2026-03-20 異붽? 20)
- ?쒕룄:
  - `ActIngestionService.emitDotDamage()`?먯꽌 `status=0` + known source + `resolveCorroboratedActionId` ?깃났 ??
    snapshot redistribution蹂대떎 癒쇱? source ?⑥씪 洹??
- 寃곌낵:
  - ?꾩뿭 湲됰씫:
    - rollup `mape=0.01946`, `p95=0.05830`, `max=0.10865`, gate `pass=false`
    - heavy2: `MAPE=0.03215`, `p95=0.09136`, `max=0.10865`
    - heavy4??`p95=0.04355`濡??낇솕.
- 議곗튂:
  - 利됱떆 濡ㅻ갚 ??baseline 蹂듦뎄:
    - rollup `mape=0.0102603`, `p95=0.0164873`, `max=0.0222490`, gate `pass=true`.
- ?댁꽍:
  - `corroborated` 洹쇨굅???뺥솗?섏?留??ъ냼??
  - ?쇰? tick留??좉??랁븯硫??섎㉧吏 遺꾨같 洹좏삎??源⑥졇 ?꾩껜 actor 媛??명뼢??而ㅼ쭚.
  - ?쒕?遺??뺤젙 + ?섎㉧吏 遺꾨같???쇳빀? ?꾩옱 援ъ“?먯꽌 鍮꾩꽑???뚭?瑜??좊컻.

### ?쇰컲??寃利??뺤옣: ?숈씪 raw??all-fights parity 怨꾩닔??(2026-03-20 異붽? 21)
- 諛곌꼍:
  - ?ъ슜???붽뎄?ы빆: 3媛????濡쒓렇留?留욎텛??理쒖쟻??湲덉?, report ?꾨컲?먯꽌 FFLogs live rDPS? ?좎궗?댁빞 ??
- ?곸슜:
  - `SubmissionParityReportService`??fight override 寃쎈줈 異붽?:
    - `buildReportForFight(submissionId, fightId)`
    - URL??`fight=` ?뚮씪誘명꽣瑜?媛뺤젣 fightId蹂대떎 ?곗꽑?섏? ?딅룄濡?遺꾧린 異붽?.
  - diagnostics 異붽?:
    - `SubmissionParityReportDiagnostics.debugAllFightsParity_forHeavy2Report_printsFightByFightQuality`
- 寃곌낵(heavy2 report `fM4NVcGvb7aRjzCt`, meaningful fights 7媛?:
  - fight 6: `mape=0.011596`, `p95=0.016062`
  - fight 4/5/8: `p95??.019~0.025`
  - fight 3: `p95=0.036358`
  - fight 1: `p95=0.043374`
  - fight 2: `p95=0.050063`
- 寃곕줎:
  - ?꾩옱 ?붿쭊? ?쒖씪遺 fight??醫뗭?留? ?뱀젙 fight 援곗뿉??援ъ“?곸쑝濡??붾뱾由щ뒗???곹깭.
  - ?욎쑝濡?媛쒖꽑 ?곗꽑?쒖쐞??heavy2 report??fight 1/2/3 ?붿감瑜?湲곗??쇰줈 ?≪븘???섎ŉ,
    3媛?怨좎젙 ?쒖텧留뚯쑝濡?99.9%瑜?二쇱옣?섎㈃ ????

### ACTWebSocket 李멸퀬 寃곕줎 (2026-03-20 異붽? 22)
- 李멸퀬 ?뚯뒪:
  - https://github.com/zcube/ACTWebSocket
  - `Sample/actwebsocket.js`, `Sample/actwebsocket_compat.js`
- ?뺤씤???ъ떎:
  - ACTWebSocket??`broadcast/send` 湲곕컲 ?꾩넚 ?덉씠?댁씠硫? `CombatData` payload ?꾨떖???듭떖.
  - ?쇱씤 ?섎?(type 21/24/37/38/264 ?? ?댁꽍 洹쒖튃? ????μ냼留뚯쑝濡??꾧껐?섏? ?딆쓬.
  - 利?parity ?뺣???臾몄젣??transport媛 ?꾨땲??parser/ingestion attribution 紐⑤뜽 臾몄젣??

### ?쇱씠釉??쇰컲???뚭? 寃뚯씠??異붽? (2026-03-20 異붽? 23)
- ?곸슜:
  - `SubmissionParityRegressionGateTest`??heavy2 report all meaningful fights 議곌굔 異붽?.
  - 湲곗?:
    - fight蹂?`matchedActorCount >= 8`
    - fight蹂?`p95 <= 0.055`
    - fight蹂?`max <= 0.060`
- 紐⑹쟻:
  - ?⑥씪 selected fight ?쒕떇?쇰줈 gate瑜??듦낵?섎뒗 ?뚭?瑜?李⑤떒.
  - ?쒕━?뚮젅???ы쁽?앹씠 ?꾨땲???쒕씪?대툕 ?쇰컲?붴앹뿉 媛源뚯슫 ?덉쟾?μ튂 ?뺣낫.
- 寃利?
  - `SubmissionParityRegressionGateTest` ?듦낵.
  - 湲곗〈 `parity_repro_check.sh` baseline ?좎?:
    - rollup `mape=0.01026`, `p95=0.01649`, `max=0.02225`.
## 2026-03-27 라이브 우선 가드레일

- data/submissions/*는 오프라인 회귀 확인과 diagnostics용 코퍼스일 뿐이다.
- 진짜 목표는 몇 개 로그의 종료 시점 수치 유사성이 아니라 pacemeter live rDPS ~= FFLogs companion live rDPS다.
- submission parity가 좋아져도 live attribution 모델이 덜 설명 가능해지거나 덜 안정적이면 그 패치는 채택할 수 없다.
- replay diagnostics는 재현 가능한 근거를 주기 때문에 여전히 필요하지만, 목표가 아니라 gate다.
- 이후 parity 변경은 아래 순서로 평가해야 한다:
  1. replay regression baseline 유지
  2. all-fights 일반화 유지
  3. live-path attribution explainability 개선
  4. FFLogs companion과의 live 추세 일치도 개선

## 2026-03-30 Higanbana status0 redistribution clamp

- 변경:
  - ActIngestionService.resolveSnapshotRedistribution에서 DoT tick이 이미 known party source를 가지고 있고 shouldAcceptDot(dot)가 참이면 status0_snapshot_redistribution을 건너뛰도록 했다.
  - 이렇게 하면 애매한 status=0 tick에는 snapshot redistribution을 유지하면서도, 이미 source 근거가 있는 Higanbana tick을 snapshot redistribution이 덮어쓰지 못하게 막을 수 있다.
- 이유:
  - heavy2 Samurai 1D41 과귀속의 대부분이 status0_snapshot_redistribution에서 발생하고 있었다.
- 검증:
  - ActIngestionServiceTest
  - SubmissionParityReportDiagnostics.debugHeavy2Fight{1,2}Samurai{DotAttributionModes,TargetParity}_...
  - SubmissionParityRegressionGateTest
  - SubmissionParityReportDiagnostics.debugParityQualityRollup_withConfiguredFflogsCredentials_printsGateAndWorstActors
- 관측된 영향:
  - heavy2 fight1 1D41 snapshot redistribution amount: 1,539,560 -> 581,606
  - heavy2 fight2 1D41 snapshot redistribution amount: 2,345,287 -> 838,955
  - heavy2 fight1 Higanbana target delta: +1,372,974 -> +1,156,727
  - heavy2 fight2 Higanbana target delta: +2,068,343 -> +1,749,419
  - rollup summary: mape=0.01390, p95=0.03520, max=0.03550
- 남은 차이:
  - Samurai는 여전히 heavy2 최악 actor 중 하나다.
  - 남은 1D41 local total은 2,026,363, FFLogs는 1,542,816으로 delta는 +483,547이다.
  - 다음 초점은 heavy2 fight2에서 남아 있는 status0_snapshot_redistribution 버킷과 target mismatch다.

## 2026-04-07 heavy2 fight2 Dragoon 64AC 체크포인트

- 범위:
  - 이번 패스에서는 production attribution 변경을 하지 않았다.
  - 다음 패스에서 April fight3와 같은 관측면으로 heavy2 fight2 64AC를 바로 볼 수 있도록 Dragoon diagnostics를 추가했다.
- 추가한 diagnostics:
  - debugHeavy2Fight2DragoonDirectVsDot_prints64acDecomposition
  - debugHeavy2Fight2DragoonWindowedLocalTotals_prints64acDamageInsideFflogsWindows
  - debugHeavy2Fight2DragoonHitLeak_prints64acLocalHitsOutsideFflogsWindows
  - debugHeavy2Fight2DragoonFflogsAbilityVsEvents_printsChaoticSpringSurfaceDelta
  - debugHeavy2Fight2DragoonStatus0TargetSourceBreakdown_prints64acTargetMix
  - debugHeavy2Fight2DragoonAlignedEventDiff_prints64acLocalVsFflogsSequences
- 이번에 확인한 수치:
  - target parity: local 2,203,903 vs FFLogs combined-events 1,934,116, delta +269,787
  - direct vs dot: local emitted 2,203,903, raw21 967,058, inferred dot 1,236,845
  - FFLogs 64AC ability total: 1,785,989
  - FFLogs 64AC + 0A9F event total: 1,934,116
  - FFLogs ability vs events delta: +148,127
- 해석:
  - 남아 있는 64AC 차이는 주로 "FFLogs window 밖 local hit leak" 문제가 아니다.
  - windowed comparison 기준으로 FFLogs window 밖 local hit은 2건 / 24,835 damage뿐이다.
  - 남은 차이 대부분이 FFLogs window 안에 있으므로, 다음 조사 초점은 넓은 status=0 clamp가 아니라 64AC / 0A9F shared GUID semantics와 FFLogs target identity surface여야 한다.
  - 현재 target parity 출력도 local은 레드 핫 / 딥 블루 / 수중 감옥으로 보이지만 FFLogs는 target id B / 11 / 18로 묶여 보여서, raw target-id 비교를 곧바로 production math 근거로 쓰기는 아직 이르다.
- 현재 live attribution 관점의 의미:
  - 64AC emitted mode breakdown에는 여전히 status0_tracked_target_split이 보이지만, 이번 diagnostics만으로 또 다른 전역 suppression을 정당화할 수는 없다.
  - 다음 안전한 단계는 attribution 규칙을 건드리기 전에 FFLogs/local surface 차이를 evidence 기준으로 더 분해하는 것이다.
## 2026-04-09 체크포인트

### 확인된 사실
- heavy2 `fight=2` SAM `1D41`의 weighted damage는 Samurai raw tick에서 나오지 않는다.
- weighted `1D41` 버킷은 DRG `64AC` raw `status=0` tick이 foreign action으로 새면서 만들어진다.
- 새 진단 `debugHeavy2Fight2SamuraiWeightedSourceContributors_prints1d41RawContributors`에서 확인한 것:
  - `rawSource=구려(10256964)`
  - `recentExact=64AC`
  - `sourceTracked=10256964:64AC`
- 새 진단 `debugHeavy2Fight2DragoonWeightedActionRecipients_prints64acForeignActionMix`에서 확인한 것:
  - weighted foreign recipient 최댓값은 `1D41`
  - 그다음이 `409C`, `4094`, `9094`
  - same-source `64AC`보다 foreign-action 총량이 더 크다

### 실패한 시도
- weighted helper 안에서 `64AC -> foreign 1D41` 누수만 막는 아주 좁은 production 패치를 넣어봤다.
- 결과:
  - heavy2 DRG `64AC`가 다시 악화됐다
  - `localTotal=2227512`
  - `fflogsTotal=1785989`
  - `delta=+441523`
- 해당 패치는 즉시 원복했다.

### 교차 fight 해석
- heavy4 `fight=5`, lindwurm `fight=8`에는 같은 의미의 `weightedActionRecipients` 출력이 보이지 않는다.
- 현재 해석:
  - weighted foreign-action mix는 heavy2 특이 contamination 패턴이다
  - 하지만 `1D41`만 제거하는 건 너무 좁아서 `64AC`가 다시 튄다

### 현재 production 상태
- 이전에 채택했던 production 변경만 유지한다:
  - `64AC` weighted helper의 same-source weight damp (`* 0.5`)
- 원복한 `1D41` 전용 차단은 유지하지 않는다.
- 원복 후 검증:
  - `ActIngestionServiceTest` 통과
  - `SubmissionParityRegressionGateTest` 통과

### 다음 작업
- heavy2 `64AC` weighted foreign-action mix를 `1D41` 하나만 막지 말고 그룹 단위로 다시 분해한다.
- 다음 production 후보는 아래 세 그룹을 설명 가능하게 분리하는 쪽이어야 한다:
  - same-source `64AC`
  - healer DoT action (`4094`, `409C`, `9094`)
  - Samurai `1D41`
