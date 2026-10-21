# 종류 근거 수명 — 제한 범위 전환 절차

> 작성: 2026-09-25 · 상태: FR-02/08 부분 구현·미배포, 격리 DB 테스트 외 대상 환경 migration·운영 수집/배치·publish 미실행
> 요구사항: [v3 spec FR-02/08 및 AC-33~39](./data-freshness-v3-spec.md) · [v3 plan P2/P5](./data-freshness-v3-plan.md)

## 1. 이번 변경과 남은 범위

이번 변경은 승인 상태와 분리된 메뉴 소멸 근거, 무변경 방문을 포함한 관측 원장, 공통 종류 분류/판정, profile/feature의 같은 근거 확인을 다룬다. 운영 메뉴를 삭제하거나 서비스 대상 업종·빵집다움 산식을 바꾸는 작업은 아니다. P2/P5 전체, 일반 observation/attempt lease·작업 예약, 전체 공통 publisher/감사, BE 소비자·캐시·회원 재계산 완료를 뜻하지 않는다.

Java의 `menu_evidence_observation`은 검증 메타데이터와 함께 수용된 관측의 불변 기록이다. hash가 같은 새 방문도 기록하고 동일 요청 식별자(`observationId`) 재전송은 중복 확인으로 세지 않는다. `menu_evidence_legacy_removal`은 기존 소멸 제안의 사본이며 제안 FK 없이 보존해 승인/원본 제안 정리와 독립적으로 근거를 유지한다. Python의 공통 canonical 분류기와 evidence reducer를 종류·대표 종류·축/타입·9개 라벨에 적용하되 소비자별 확인 횟수 차이는 유지한다.

이 문서는 배포 순서와 통과 조건이다. 새 일회용 DB에서 아래 검증만 수행했고, 대상 환경 migration·크롤·전체 rebuild·운영 publish는 실행하지 않았다. 코드/테스트 최종 결과와 실제 CLI 옵션은 해당 변경 revision에서 확인하고, 검증 전 전체 배치를 자동 실행하지 않는다.

## 2. 정책과 legacy 보류

| 항목 | 이번 제안 계약 | 운영 적용 전 확인 |
|---|---|---|
| 독립 관측 간격 | 최소 6시간, 최대 30일. 설정 가능하며 정책 fingerprint에 포함 | 기존 수집 티어와 표본에서 지연/오탐 검증 |
| 종류·축/타입·라벨 | 공통 상태에서 연속 2회 확인 정책 사용 | 동일 요청·근접 재시도·불완전 응답을 추가 횟수로 세지 않음 |
| 대표 종류 | 공통 상태에서 1회 확인 정책 사용 | 종류 목록과 차이가 정책으로 설명됨 |
| 복원 | 검증된 현재 메뉴판/대표 메뉴의 명시적 양성 근거로 재평가 | 과거 리뷰 재수집만으로 복원하지 않음 |
| legacy 소멸 | 원본·출처를 전부 보존, 미해결이면 NEEDS_REVIEW로 게시 차단 | 기존 serving 유지; shadow 제외를 운영 제외로 승격하지 않음 |

6시간/30일은 배포 준비를 위한 제안 기본값이며 실증된 최적값이 아니다. 값을 바꾸면 정책 버전/fingerprint가 바뀌고 새 shadow 산출물을 만들어야 한다. worker의 `externalId`·offset을 포함한 `observedAt`과 서버의 메뉴 영역 완전성 판정을 함께 사용하며 메타데이터 누락/검증 실패는 현재 음성 근거로 사용하지 않는다. 같은 과거 payload를 다시 제출해 시각만 바꾸는 것도 검증된 새 방문이 아니다.

legacy UNKNOWN hold는 잘못된 부활을 막지만 현재 판매 중인 종류도 한동안 보류할 수 있다. 해당 종류와 이유를 가게별로 보고하고 검증된 현재 양성 관측을 우선 확보한다. 이 위험을 숨기기 위해 snapshot 수·제안 수·승인 시각으로 “이미 2회 확인”을 만들거나, 전체 hold를 일괄 해제하지 않는다. 원천 근거 확보가 실패한 가게는 보류하며 추천 데이터의 최신 성공으로 표시하지 않는다.

### PR #14 리뷰 후 전환 정책

- 이관 대상과 운영 제외 대상을 분리했다. 미해결 legacy 이름/종류, 이름을 식별할 수 없는 원천 행은 가게의 `publication_status=NEEDS_REVIEW`로 남는다. profile draft에는 사유를 보존하고 feature draft는 NEEDS_REVIEW 행으로 남긴다. 이를 WITHOUT_LABELS나 삭제 집합으로 만들지 않는다.
- 선택 범위에 NEEDS_REVIEW가 하나라도 있으면 target 접속 전에 전체 publish를 거부한다. `--apply-invalidations`와 `--dry-run`도 우회가 아니다. 보류 가게의 기존 프로필·라벨은 유지되지만 최신화 성공을 뜻하지 않는다. 검증된 일부만 반영하려면 제외 사유를 검수하고 별도 `--store-ids` 범위를 명시한다. 자동 skip은 없다.
- 시각·이름이 확인된 legacy는 그 뒤의 직접 양성 또는 최소 간격을 만족한 완전 음성 두 번으로 재평가한다. 종류가 복원되어도 옛 이름의 hold가 남으면 보류한다. source 시각이 NULL이거나 메뉴 이름을 식별할 수 없으면 임의 관측으로 자동 해제하지 않는다.
- `evidence_at`은 검증된 snapshot의 fetched_at만 사용한다. 제안 생성 시각은 `proposal_created_at`으로 보존하며 비교 기준에서 제외한다. snapshot_id/가게/출처/target 불일치나 참조 누락은 행을 버리지 않고 NULL·time_basis=UNKNOWN으로 남긴다.
- evidence/context 포맷 v2, 배치 `v7-20260925`로 재빌드한다. 구판 draft는 게시 불가다. publish 단계는 frozen 상태에서 보류를 다시 계산하므로 문자열 status 수정만으로 우회할 수 없다.

리뷰 제공 수치인 이전 규칙 36,273건 / 보존 대상 70,636건(PENDING 64,986 + APPROVED 5,650)은 **제안 건수 비교**다. 같은 가게·종류의 중복, 현재 양성에 의한 해제, 기존 대표 메뉴의 별도 1회 정책 등이 있으므로 종류·가게·추천 결과가 1.9배 제외된다는 뜻으로 사용하지 않는다. 이번 수정에서는 운영 DB로 그 수치나 순위 영향을 재측정하지 않았다. 실제 영향은 아래의 고정 산출물 비교와 추천 소비자 자료로 확인해야 한다.

사용자 제공 2026-09-25 조사 수치인 승인 제안 5,650건·가게 1,091곳·종류가 붙은 제안 5,613건은 승인/분류 현황이다. **실제 부활 재현 건수나 이번 변경의 영향 가게 수가 아니다.** 같은 조사에서 제시된 종류/점수 영향 수도 당시 입력·계산법의 보고치로 구분하고, 이번 동일 cutoff/fingerprint shadow 결과를 대신하지 않는다. 본 구현에서 운영 DB 수치를 재검증하지 않았다.

## 3. 배포 순서와 중단 조건

### 1) migration 준비·검증

- 적용할 코드 revision, 대상 dataops DB 식별, 기존 스키마 버전과 복구 가능한 백업을 기록한다. 운영 `public` 스키마 변경은 이번 경로에 포함하지 않는다.
- 격리된 검증 환경에서 [원장/legacy migration](../src/main/resources/db/migration/V20260925100000__create_menu_evidence_ledger.sql)과 [draft 근거 migration](../src/main/resources/db/migration/V20260925103000__profile_kind_evidence.sql)을 확인한다. 대상 NAVER 소멸 제안(PENDING/APPLYING/APPROVED)의 출처가 보존되고 제안 승인/삭제로 사본이 없어지지 않는지 검증한다. 별도 제외 사유가 있는 제안은 원천 소멸 근거로 섞지 않는다.
- 동일 migration/후속 보존 작업의 재실행, 부분 실패, 신규 관측이 아직 없는 상태를 검증한다. 관측 횟수·현재 판매 상태를 legacy 자료에서 만들어내면 전환을 중단한다.
- 대상 환경 migration 적용은 코드 테스트와 별도 작업이다. 이번 구현 중 적용했다고 기록하지 않는다.
- [publish attempt migration](../src/main/resources/db/migration/V20260925104000__kind_evidence_publish_attempt.sql)도 먼저 적용한다. `kind_evidence_publish_attempt`는 local intent·before-image·manifest·결과 상태를 보존하며, 같은 target의 PREPARED/APPLYING/UNKNOWN은 대사 전 신규 publish를 차단한다.
- [legacy 관측 시각 교정 migration](../src/main/resources/db/migration/V20260925105000__correct_legacy_menu_evidence_time.sql)을 이어서 적용한다. 최초 migration은 체크섬 보존을 위해 변경하지 않는다. 교정 전에 원본 제안이 삭제됐더라도 legacy 행은 남고, 검증할 수 없는 시각은 UNKNOWN으로 보류된다. 새 loader가 요구하는 provenance 열이 없으면 계산도 실패한다.

### 2) server와 worker 전환

- migration을 먼저 적용한 환경에 호환 server를 배포하고, 그 뒤 검증 메타데이터를 보내는 worker로 전환한다. 구 worker/구 batch와의 혼용 기간을 명시한다.
- 같은 요청의 재전송은 같은 `observationId`를 재사용한다. 같은 ID와 다른 내용/외부 ID/관측 메타데이터는 충돌이며 새 방문을 가장한 재전송을 만들지 않는다.
- 외부 ID, offset을 포함한 관측 시각, 영역 완전성을 검증한다. 불완전·실패·늦은 입력은 소멸 확인을 전진시키지 않는다.
- 현재 저장된 실제 메뉴 fixture는 target identity·메뉴 11개·대표 8개를 추출해도 전체 목록 수(`total`/`totalCount`)가 없어 `menu_complete=false`다. 양성 근거와 부재를 증명할 완전성은 다르며, 이 상태의 운영 수집으로 자동 소멸이 동작한다고 보고하지 않는다. 전체 수·비필터 목록·pagination 종료 등을 실제 payload에서 검증하기 전 자동 소멸 전환은 보류한다.

  **검증 결과 (2026-09-26, 저장된 원본 실측 — 추가 수집 없음).** 위 판단의 근거였던 fixture는 옛 payload 모양이다. 네이버가 09-06~09-20 사이에 메뉴 payload를 바꿨고, 09-20 이후 수집분은 무작위 표본에서 100% 새 모양이다.

  | 항목 | 옛 모양 (`menus` 맨 배열) | 새 모양 (`placeMenus` 컨테이너) |
  | --- | --- | --- |
  | 전체 목록 수 | 없음 | `menuCount` (키 이름이 `total`이 아니다) |
  | 비필터 목록 | — | 컨테이너 키에 쿼리 인자 없음 (300/300) |
  | pagination 종료 | — | `hasNextPage`/`pageInfo` 키 자체가 없고 `menuCount == items` |
  | 수집 시기 | 08~09-06 | 09-20 이후 |

  전수 대조에서 `menuCount == items`가 2,441/2,441로 반례가 없었다(메뉴 140개 가게 포함). 즉 완전성 근거는 payload에 있었고, 파서가 `total`/`totalCount`만 찾아 새 모양에서 영영 완전성이 서지 않았다. 파서를 v2(`naver-menu-root-v2`)로 올려 `menuCount`를 인정한다 — 일치·불일치·정수 아님을 `total`과 같은 기준으로 검사하며, 나머지 조건(단일 비필터 컨테이너·페이지 종료·중복 id 없음·errors 없음)은 그대로다.

  이 교정 전후의 완전성 비율(무작위 3,000건 실측):

  | 파서 | 전 기간 | 09-20 이후 수집분 |
  | --- | --- | --- |
  | v1 (`total`/`totalCount`만) | 0.0% | 0.0% |
  | v2 (`menuCount` 인정) | 25.0% | **80.5%** |

  남는 19.5%는 `placeMenus`가 null인 가게(메뉴 탭 부재)로, 완전성이 서지 않는 것이 맞다. 따라서 표본 수집 전에 이 파서 교정이 선행돼야 한다 — 교정 없이 스윕하면 관측 행은 생기지만 소멸 판정에 쓸 수 있는 관측은 0건이다. 이 교정은 완전성 판정만 바꾸고 소비자(`store_profile_batch.py`의 소멸 기준)는 건드리지 않으므로, 아래 3)·4) 게이트는 그대로 남는다.
- 동일 내용의 서로 다른 검증 방문이 원장에 남는지 확인한다. 이 검증만으로 일반 attempt lease·전체 파생 작업 예약이 구현됐다고 판단하지 않는다.

### 3) 검증된 관측 확보

- 제한된 가게 집합을 고정하고 정책의 최소 간격을 만족하는 독립 방문을 확보한다. 마지막 관측 이후 최대 간격 초과는 정책에 따라 다시 평가한다.
- 메뉴 유지·한 번 부재·두 번 부재·현재 메뉴/대표 재등장·과거 리뷰만 존재·외부 ID 불일치·불완전 payload·legacy UNKNOWN을 표본에 포함한다.
- 운영 시간을 기다리는 대신 제어 가능한 시각 fixture로 경계 테스트를 먼저 수행한다. fixture 통과와 실제 수집 표본 확인을 구분한다.
- 새 관측이 없는 가게를 완료로 채우지 않는다. 실패/미검증 가게는 후속 shadow/publish의 성공 범위에서 제외하고 사유를 남긴다.

### 4) shadow 빌드·차이 검수

- `--store-ids`로 명시적 build/publish 범위를 고정하고 evidence cutoff(관측 ID·legacy proposal ID 상한), 정책/분류기/원장 입력 fingerprint를 기록한다. `store_profile_draft.kind_evidence`와 `kind_evidence_fingerprint`의 context를 feature 계산에서 복원하고 signal·메뉴·snapshot 참조까지 대조한다. 불일치·메타데이터 누락이면 쓰기 전에 중단하며, ID 상한을 두 DB의 동일 시각 snapshot으로 표현하지 않는다. feature `observed_at`은 현재 `last_success_at`이 아니라 frozen evidence의 `latest_observed_at`과 일치해야 한다. 이 시각은 검증된 종류 근거의 관측 시각이지 모든 리뷰·태그 내용의 최신성 보장은 아니다.
- `kinds`, `signature_kinds`, 축/타입, 9개 라벨의 전후 결과와 공통 판정 사유를 비교한다. 목록을 교집합/합집합으로 강제하지 않는다.
- 빵집다움 산식과 `menu_kinds_all` 입력 계약이 유지되는지 실제 호출 인자로 확인한다. 후보 하한 이동만으로 영향을 요약하지 말고 종류 겹침·배지·문장/취향 소비 변화도 검토 대상으로 남긴다.
- legacy UNKNOWN hold 수와 대상 종류, 검증된 양성에 의한 복원, 순수 과거 리뷰의 재허용 여부를 별도로 출력한다.
- 근거가 없어진 feature는 성공적으로 판정한 명시적 범위 안에서만 무효화 후보로 만든다. 범위 밖·읽기 실패·미처리·단순 파일 누락은 삭제 근거가 아니다. 기존 라벨을 0으로 채워 부재를 가장하지 않는다.
- 산출물·범위·cutoff/fingerprint·차이·무효화 ID·실패/보류 사유를 검수 기록으로 남긴다. shadow는 운영 쓰기를 하지 않는다.
- 미해결 legacy를 제거한 집합만 비교하지 않는다. 전체 cohort의 보류 가게·종류, shadow 변화, 명시적으로 게시 선택한 범위의 변화를 나누어 보고한다. 순위·배지·문장을 측정하지 못한 경우 0이나 영향 없음으로 기록하지 않는다.

#### 운영 접속 없는 산출물 비교

[비교 도구](../scripts/kind_evidence_impact.py)는 이미 고정한 두 JSON만 읽는다. DB/API 접속·환경설정 읽기·추천 실행·자동 산출물 추출·publish는 하지 않는다. 입력 계약과 최소 JSON 예제는 파일의 docstring 및 `--help`에 있다.

```sh
python3.12 -B scripts/kind_evidence_impact.py \
  --baseline before.json --candidate after.json
```

- 각 산출물은 `schema_version=1`, 고유 `artifact_id`, 같은 `scope.cohort_id`·가게 ID 전집·축/라벨 차원, 가게별 profile/labels·`serving_outcome`·`publish_selected`를 명시한다. `kinds`, `signature_kinds`, 축, 타입/타입 라벨, `bakery_score`를 모두 포함하고 값이 없으면 누락 대신 NULL을 기록한다. 기존 draft JSON을 변환 없이 받는 CLI가 아니다. 추출할 때 같은 입력·cutoff/정책·실제 호출 인자를 고정하고 별도로 기록한다.
- feature의 WITH_LABELS는 실제 evidence의 게시 가능 여부까지 확인한 경우에만 READY로 표현한다. NEEDS_REVIEW의 shadow 값도 보존하되 `publish_selected=false`로 둔다. WITHOUT_LABELS의 선택은 이미 별도 검수된 무효화 후보를 뜻하며, 이 도구가 무효화를 승인하지 않는다.
- 전체 shadow 변화(`proposed_all_stores`)와 명시적 게시 예정 범위(`planned_publish_only`), 보류/미선택 ID를 구분한다. 미선택 가게의 예정 반영 차이는 빈 값이며 운영 반영 완료를 뜻하지 않는다. 제안 건수와 고유 가게·종류 변화 수도 분리한다.
- 상위 추천 비교에는 양쪽 `recommendations`가 필요하다. 요청 ID 전집·top_k·요청별 `input_fingerprint`를 동일하게 고정하고 실제 순위 및 선택적으로 배지·이유 문장을 넣는다. 미입력 항목과 비교할 공통 가게가 없는 배지/문장은 NOT_MEASURED다. 추천 산출물 차이를 게시 예정 범위의 효과로 자동 추정하지 않는다.
- 불완전/중복/범위 불일치 입력, 보류 가게의 게시 선택은 exit 2로 거절한다. 기본 출력은 입력 SHA-256이 포함된 stdout JSON이며, `--output report.json`은 기존 파일을 덮지 않고 새 파일만 만든다. 도구 회귀 테스트 통과는 실제 운영 영향 측정 완료가 아니다.

### 5) 승인된 제한 범위 publish

- 적재 진입점은 `recommendation_feature_load.py`이며 검증된 profile과 feature를 함께 반영한다. 기존 `store_profile_load.py` 단독 적재로 새 경로를 우회하지 않는다. feature 무효화가 있으면 검수한 명시적 ID와 `--apply-invalidations` 승인이 필요하며, 승인 없이는 target 접속 전에 중단한다.
- 구 profile/feature writer를 중지하거나 같은 공통 publisher와 advisory lock으로 전환한다. 협력 writer 간 직렬화가 advisory lock을 우회하는 writer까지 막지는 않는다. 특히 없는 feature의 삽입은 행 잠금만으로 차단되지 않으므로 구 적재 작업이 살아 있는 혼용 상태를 안전한 운영 전환으로 취급하지 않는다.
- 검수한 산출물과 무효화 집합에 대해 대상 환경·범위·근거 일치 검사를 다시 통과한 뒤 명시적으로 승인된 publish만 수행한다. 검수 후 근거/정책/범위가 바뀌면 재빌드·재검수가 필요하다.
- profile draft를 다시 읽을 때 고정한 근거의 external ID와 current external ID도 대조한다. 매핑 변경 시 과거 context로 publish하지 않고 새 identity에서 다시 수집·빌드·검수한다.
- [scoped publisher](../scripts/kind_evidence_publish.py)는 serving advisory lock과 버전 검사를 사용하고, local PREPARED intent를 commit한 후 profile/feature upsert와 명시적 범위 무효화를 한 serving 트랜잭션으로 반영한다. profile보다 최신 target profile/feature를 덮거나 삭제하지 않는다. 무효화할 ID가 있으면 DELETE 권한도 확인한다. 이 경로의 격리 DB 통합 검증은 단위 테스트와 별도 게이트다.
- serving commit 응답이 불명확하거나 commit 후 local 결과 기록이 실패하면 UNKNOWN 또는 미해결 APPLYING을 보존한다. 같은 target은 실제 두 표와 before-image/manifest를 대사할 때까지 재쓰기를 막는다. 결과가 불명인데 단순히 상태를 FAILED/APPLIED로 바꿔 차단을 해제하지 않는다. 자동 대사·보상은 이번 최소 publisher의 구현 범위 밖이다.
- profile·feature 전후 결과와 범위 안 무효화를 대사한다. 결과 불명·부분 반영·근거 불일치가 있으면 재시도 전에 대사하고 범위를 확대하지 않는다.
- 운영 배치·전체 rebuild·대량 publish는 코드 통합의 자동 후속 단계가 아니다. 제한 표본 검증과 운영 승인이 끝나기 전 실행하지 않는다.

`--dry-run` publish는 **target에 실제 DML과 잠금을 수행한 뒤 rollback**하는 방식이다. local audit에는 ROLLED_BACK 결과가 남으므로 로컬 DB도 무변경인 검증이 아니다. SQL·권한·제약 검증용 별도 단계이며, 운영 접속 없이 산출물만 비교하는 shadow와 구분한다.

## 4. 검증 결과를 남길 항목

| 검증 | 확인할 결과 | v3 관련 기준 |
|---|---|---|
| 동일 요청/동일 내용 재전송 | 원장·소멸 확인 중복 없음 | AC-03 |
| 새 검증 방문/내용 동일 | 관측은 남고 간격 정책에 맞게 소멸 확인 | AC-03 |
| 불완전/외부 ID 불일치/늦은 입력 | 거짓 음성·현재성 전진 없음 | AC-04 |
| 승인 후 legacy 근거 | 제안 상태와 독립적으로 보존, 과거 리뷰 부활 없음 | AC-33 |
| 현재 양성/과거 리뷰 | 전자만 재평가·복원, legacy 불명은 보류 | AC-34 |
| 별칭 분류 | 리뷰·대표 경로가 같은 canonical kind 사용 | AC-35 |
| 1회/2회 소비자 임계값 | 종류·대표·축/타입·라벨 차이가 정책으로 설명됨 | AC-36 |
| profile/feature 근거 불일치 | 쓰기 전 거부 | FR-08 부분 게이트 |
| 명시적 범위 무효화 | 범위 밖·실패/미처리 feature 보존 | AC-03/15의 부분 검증 |
| label SQL 실패 / serving commit 응답 유실 / local 결과 실패 | 두 표 rollback 또는 UNKNOWN 보류, intent 없는 운영 쓰기·자동 재적용 없음 | AC-08/14의 부분 검증 |
| 신규 target 버전 / scope 밖 삭제 / DELETE 권한 부재 | 운영 DML 전 거부 또는 버전 CAS 실패로 전체 rollback | FR-08 부분 게이트 |

실행한 테스트의 명령·revision·통과/실패와 미실행 운영 검증을 분리해 기록한다. 단위 테스트만으로 DB 경쟁 쓰기·장애 복구·운영 소비자 E2E 수용을 선언하지 않는다. P2/P5 전체 체크박스는 각 게이트의 별도 근거가 있을 때만 완료한다.

### 2026-09-25 구현 검증 기록

- 코드 revision `a76b488` 기준 Python 일반 단위 테스트 48개, Java 테스트 85개 통과. 아래 opt-in PostgreSQL 10개는 별도 집계이며, 어느 수치도 운영 수집/배치나 소비자 E2E 실행 수가 아니다.
- 별도로 [PostgreSQL 통합 테스트](../scripts/tests/test_kind_evidence_publish_postgres.py) 10개 통과. 새 `postgres:16-alpine` 컨테이너의 loopback 임시 포트에만 접속했고 `.env`나 기존 DB 연결 도우미를 읽지 않았다. 테스트마다 고유 local/serving DB를 만들고 해당 DB만 제거했다.
- 실제 신규 migration 3개를 실행해 legacy 대상 필터·원본 제안 삭제 후 보존·draft NULL 초기값을 검증했다. 최소 serving fixture에서 두 표 upsert/명시적 범위 delete·audit SQL, 제약 실패 전체 rollback, dry-run rollback, 최신 버전 거부, 신규 profile 경쟁 CAS, 협력 publisher 직렬화, commit 전/후 응답 유실 UNKNOWN, local 결과 장애 APPLYING 차단을 확인했다.
- 실행 명령은 `KIND_EVIDENCE_TEST_PORT=65132 /private/tmp/codex-kind-evidence-tests.6vpCLT/venv/bin/python -B -m unittest discover -s scripts/tests -p 'test_kind_evidence_publish_postgres.py' -v`였다. 포트와 venv는 당시 전용 임시 자원이며 재실행 때는 새 일회용 컨테이너/런타임을 준비해야 한다. 일반 unittest discovery에서는 명시적 테스트 포트가 없으면 이 10개를 skip한다.
- 전체 BE 스키마/Flyway 체인·실제 writer 권한·비협력 writer·네트워크 단절/프로세스 종료 전부를 검증한 것은 아니다. 응답 유실과 local 장애는 실제 DB commit 전후에서 주입한 예외로 재현했다. 자동 대사·보상과 운영 표본/소비자 검증은 미완료다.

### 2026-09-25 PR #14 리뷰 후 추가 검증

- 코드 revision `2a6e6a8`의 소스 기준 **일반 83개 + 격리 PostgreSQL 12개, 총 95개** 통과. 추가 범위는 legacy 시각 5개, 게시 보류 10개, 영향 비교 20개와 PostgreSQL 2개다. Java는 이번 후속 수정에 변경이 없어 위 85개 기록을 유지하며 다시 실행하지 않았다.
- 실행 명령: `KIND_EVIDENCE_TEST_PORT=50593 /private/tmp/codex-kind-evidence-review-tests.V7lhKx/venv312/bin/python -B -m unittest discover -s scripts/tests -q`. 새 PostgreSQL 16 일회용 컨테이너만 사용했다. 테스트 DB와 컨테이너는 제거했고 전용 런타임 디렉터리는 휴지통으로 이동했다. 재실행에는 새 테스트 환경이 필요하다. opt-in 포트를 지정하지 않으면 PostgreSQL 12개는 skip된다.
- additive migration의 snapshot/가게/출처/target 대조, 누락·불일치 UNKNOWN 보존, 08시 부재/11시 양성/12시 제안 재생성의 순서 교정을 검증했다. 기존 migration 체크섬은 변경하지 않았다.
- 미검증 1회 legacy·시각 불명·식별 불가 근거의 게시 차단, 두 독립 음성/직접 양성의 알려진 시각 재평가, frozen 상태와 보류 메타데이터 대조, shadow 값 보존 및 게시행 제외, 강제 무효화/dry-run 우회 방지를 검증했다. 시각 불명은 과거 음성 두 건으로도 자동 해제되지 않는다.
- 영향 비교 도구는 동일 cohort·누락/중복 필드 검증, shadow/게시 예정/보류 분리, 실제 추천 입력 없을 때 NOT_MEASURED, 요청×가게와 고유 가게 집계 구분을 fixture로 확인했다. **운영 추천 순위·배지·문장 영향은 미측정**이며 전체 rebuild/publish 승인 조건은 여전히 열려 있다.

## 5. 보류·롤백·남은 소비자 작업

문제가 있으면 새 publish를 중단하고 마지막 검증 산출물·before-image·대상 결과를 대사한다. 원장/legacy 사본을 삭제하거나 예전 승인 상태 기반 batch를 무검증으로 다시 돌리지 않는다. 필요한 보상은 범위와 최신 상태를 확인한 뒤 별도 작업으로 수행한다. 운영 메뉴 복원/삭제는 이 파생 데이터 전환의 롤백에 포함하지 않는다.

다음은 이번 구현에서 실행하지 않은 후속 게이트다.

- BE의 실제 후보·순위·배지·규칙형/LLM 이유 문장 경로 검증과 필요한 코드 변경.
- 이미 저장된 추천 이유 문장·회원 추정 취향의 재생성/재계산 정책과 실행.
- `LabelCenterParams` 등 캐시 반영 시점 및 구/신 데이터 혼용 영향 확인.
- 일반 파생 작업 예약, 불변 run/draft·전역 입력 완전성, 전체 스키마/권한·운영 writer 경쟁/장애 검증과 자동 대사·보상, 운영 자동화.

dataops 산출물이 일치하더라도 이 게이트 전에는 사용자 화면·저장된 문장·회원 취향까지 즉시 갱신됐다고 보고하지 않는다. 전체 v3는 기존 spec/plan의 미완료 조건을 그대로 따른다.
