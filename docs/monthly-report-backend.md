# 월간 빵일지 백엔드

관련 작업: [백엔드 #822](https://github.com/SWEETT-GIRL/bbang-ggut_BE/issues/822),
[프론트 #122](https://github.com/SWEETT-GIRL/daedongbbangmap/pull/122).
기준은 2026-10-01 프론트의 `docs/monthly-report-backend-handoff.md`와 `src/features/monthly-report/model.ts`다.

## 이번 구현

`GET /reports/monthly/{yearMonth}/me`는 Bearer 토큰의 회원에 대해 저장된 스냅샷을 조회한다.
회원 ID를 경로나 요청 파라미터로 지정할 수 없다. 기존 `ApiResponse`의 `data`에 schemaVersion 1 객체를 담는다.
개인 통계 6장과 표지가 대상이며 공동 결산·이용시간·배지·서버 공유 이미지 생성은 이번 계약에 없다.

| 상황 | HTTP | 응답 코드 |
| --- | --- | --- |
| 정상 발행본 | 200 | 기존 성공 코드, data에 전체 보고서 |
| 잘못된 월 | 400 | MONTHLY_REPORT4001 |
| 인증 없음 | 401 | 기존 인증 오류 |
| 행 없음 또는 WITHHELD | 404 | MONTHLY_REPORT4041 |
| FAILED | 503 | MONTHLY_REPORT5031 |
| payload 손상, 회원·월 불일치 | 500 | MONTHLY_REPORT5001 |
| DB 등 기타 서버 실패 | 500 | 기존 서버 오류 |

- 월은 `2000-01`~`2099-12`의 `YYYY-MM`만 받는다. 아직 발행되지 않은 현재·미래 월은 404다.
- 삭제된 회원은 기존 `MemberReader` 규칙으로 조회가 차단된다. 발행 대상은 탈퇴하지 않은 일반 회원(`USER`)으로 정했다. 게스트·블로그 봇은 제외한다. 대상 필터는 배치 구현 시 적용한다.
- 필수 수치는 nullable wrapper로 역직렬화하고 검증한다. 누락을 0으로 바꾸지 않으며 문자열·소수·음수·안전한 정수 범위 초과를 거절한다.
- `comparison.currentRecords`와 요약 기록 수, 능력치별 상한과 총합, artwork의 캐릭터·행성·유형 일치를 검증한다.
- 전월 집계가 없으면 `comparison.previousRecords: null`로 발행한다. 필드 자체는 필수다. 프론트는 비교 카드만 생략하고 나머지 보고서를 표시한다.
  전월 활동이 실제 0이면 숫자 `0`으로 보내 비교 카드를 표시한다. `currentRecords`는 계속 필수이며 요약 기록 수와 일치해야 한다.
  프론트의 응답 검증·카드 목록·공유 이미지에도 null 처리가 필요하다. 이 연동을 배포한 뒤 null을 포함한 보고서를 발행한다.
- `totalScore: null`, `abilities: null`은 그대로 전달한다. 집계 완료된 0과 전월의 실제 값도 그대로 전달한다.
- 조회 응답에는 `Cache-Control: no-store`를 붙인다. 조회 시 원시 활동을 다시 집계하지 않는다.
- `nickname`은 조회 시 현재 회원의 닉네임으로 반환한다. 닉네임 변경은 응답에만 반영하고 저장된 통계는 갱신하지 않는다.
- 빵 사진은 발행 시 선택한 원본 기록 ID와 사진 참조 UUID로 조회한다. 현재 원본/리사이즈 키를 반환하므로
  명시적으로 연결한 사진 교체가 반영된다. 현재 사진에 보고서와 같은 빵 종류의 태그가 있어야 한다.
  태그 삭제·종류 변경, 사진 삭제, 본인 원본 기록과 연결 불일치 시 null이다. 비공개 기록도 본인은 볼 수 있다.
- 사진 조회는 숫자·순위·대표 사진의 출처를 다시 집계하지 않는다. 사진 출처를 복원하지 못한 구버전 보고서는 기존 키의 소유권·존재와 해당 사진의 현재 빵 종류를 확인한다.

## 내 보고서 목록

`GET /reports/monthly/me?page=0&size=12`

- 동일한 Bearer 인증을 사용한다. 본인의 PUBLISHED만 보고서 월 내림차순으로 반환한다.
- 기본값은 page=0, size=12다. page는 0 이상, size는 1~100이며 범위 밖이면 400/MONTHLY_REPORT4002다.
- 데이터가 없으면 200과 `content: [], hasNext: false`를 반환한다. WITHHELD/FAILED는 목록에 포함하지 않는다.
- 목록에는 월·발행 시각·상세 경로·발행 당시 캐릭터를 담는다. 다음 페이지는 page를 1 증가시킨다.
- `character`는 상세와 같은 `{ code, name, imageKey }`이며 저장된 발행 시점 열에서 읽는다.
  현재 사용자 선택이나 카탈로그와 조인하지 않는다. `imageKey`는 CDN 키이며 없으면 null이다.
  클라이언트는 구버전 응답에 character가 없거나 null이면 중립 폴백을 표시한다.
- `publishedAt`은 UTC ISO-8601 문자열, `link`는 앱 내부 경로다. 응답은 `Cache-Control: no-store`다.

```json
{
  "success": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "data": {
    "content": [
      {
        "yearMonth": "2026-09",
        "publishedAt": "2026-10-01T01:00:00Z",
        "link": "/reports/monthly/2026-09",
        "character": { "code": "cat", "name": "냥이", "imageKey": null }
      }
    ],
    "hasNext": false
  }
}
```

## 저장과 발행 연결점

`monthly_report_member`의 기본 키는 `(member_id, year_month)`다.
`payload`, `raw_metrics`는 JSONB, `generated_at`, `published_at`은 TIMESTAMPTZ다.
상태는 `WITHHELD`, `FAILED`, `PUBLISHED`를 구분한다.

`MonthlyReportMember` Entity는 `MonthlyReportMemberId(memberId, yearMonth)` 복합키를 사용한다.
`MonthlyReportRepository`는 `JpaRepository`이며 상세는 Entity, 목록은 필요한 열만 읽는 JPA projection/Slice로 조회한다.
발행본에는 변경 메서드를 두지 않고 Hibernate `@Immutable`로 일반 dirty checking 갱신을 막는다.
동시 발행을 막는 PostgreSQL upsert는 Spring Data의 native query로 유지한다.
사진 조회는 `BreadRecordTagRepository.findMonthlyReportPhotoTags`의 JPQL fetch join으로 현재 태그·사진·원본 기록·메뉴 분류를 가져온다.
집계와 동일한 `resolvedCategory()`로 빵 종류를 확인하므로 메뉴 분류 보완 규칙도 일치한다.

### 발행 당시 캐릭터·행성 보존

Entity에 `characterCode`, `characterName`, `characterImageKey`, `planetCode`, `planetName`을 저장한다.
현재 MemberWorld나 카탈로그와의 연관관계로 두지 않으며, 조회 때 현재 선택·진행도·이름을 다시 가져오지 않는다.
통계·능력치·전용 artwork 코드/버전/키는 최종 payload에 그대로 보관한다.
payload에 남아 있는 발행 당시 nickname은 조회 응답에서 사용하지 않고 현재 회원의 닉네임으로 대체한다.

발행 upsert가 검증된 payload에서 다섯 열을 함께 추출해 저장하고, DB 제약과 조회 검증이 둘의 불일치를 막는다.
발행된 회원·월에 다시 호출해도 캐릭터·행성과 payload를 덮어쓰지 않는다.
배치가 발행에 사용할 캐릭터·행성을 선택하는 과정은 아직 원시 집계 구현과 함께 남아 있다.

`V20261001140644__add_monthly_report_appearance.sql`은 기존 발행 payload에서 다섯 열을 채운다.
현재 프로필을 사용해 과거 값을 추정하지 않는다. 코드뿐 아니라 표시 이름과 이미지 키도 복사하므로
카탈로그 이름이나 사용자 선택이 바뀌어도 과거 보고서의 값은 유지된다.
이미지 파일 자체가 바뀌지 않게 하려면 기존 계약대로 버전이 포함된 불변 CDN 키를 사용한다.
삭제된 개인 빵 사진을 응답에서 가리는 기존 정책은 유지한다.

`MonthlyReportPublicationService.publish(...)`는 향후 집계 배치가 부를 내부 서비스다.
HTTP 발행 API와 자동 집계 스케줄러는 아직 연결하지 않았다. 이 서비스를 호출해 최초 발행하면 알림함 적재와 푸시 요청도 수행한다.

### 빵 종류 집계와 생성 연결

`MonthlyReportGenerationService.publishFromDraft(...)`는 초안에 기록 수·기록한 빵집 수·빵 종류·전월 비교를
실제 데이터로 채워 발행 서비스를 호출한다. 전체 보고서를 자동으로 만드는 배치는 아직 없으며,
빵집 행동·동네·능력치·캐릭터와 행성은 호출자가 준비해야 한다.
이미 PUBLISHED인 회원·월이면 집계 서비스를 호출하지 않고 false를 반환한다.

`MonthlyReportBreadAggregationService`는 `BreadRecordTagRepository.findMonthlyReportTags`의 JPQL로
본인의 기록을 `eatenAt >= 해당 월초 AND eatenAt < 다음 월초` 범위에서 읽는다.
월말에 존재한 기록만 포함하므로 `createdAt < 다음 월초`와
`deletedAt IS NULL OR deletedAt >= 다음 월초`를 함께 적용한다. 월말 후 삭제된 로그는 통계에 남지만 사진은 조회 시 숨긴다.
기존 빵로그와 같은 한국 시간 LocalDateTime 기준이며 본인의 비공개 기록도 포함한다.
기록·사진·메뉴·메뉴 분류는 fetch join으로 한 번에 가져온다.

- 태그 1개를 기록된 빵 1회로 센다. 같은 기록의 여러 태그도 각각 세며 실제 섭취 수량을 추정하지 않는다.
- `BreadRecordTag.resolvedCategory()`를 재사용한다. 태그가 구체 분류이면 그대로, 빵·기타이면 메뉴 분류로 보완한다.
- 전체 `summary.breads`는 미분류를 포함한 태그 수다. 빵·기타로 남은 태그는 종류별 순위에서 제외하고
  `raw_metrics.breadAggregation.uncategorizedTags`에 따로 남긴다.
- 종류별 순위는 횟수 내림차순, 동점이면 이름 오름차순이다. 대표 사진 선택은 횟수에 영향을 주지 않는다.
- 대표 사진은 같은 종류의 **최고였던 빵으로 선택된 태그**를 우선한다. `lovedBreads`와 구버전 `lovedBread`를 확인한다.
  이 중 가장 최근 eatenAt, 기록 ID 내림차순, 사진 순서·사진 ID·태그 ID 오름차순으로 결정한다.
  최고였던 빵이 없으면 같은 종류의 일반 기록에 같은 최신순 규칙을 적용한다.
- 사진 키는 존재하는 640 → 320 → 1024 리사이즈 키 → 원본 순으로 선택한다.
- 발행 시 `raw_metrics.breadPhotoSources`에 종류별 `{ recordId, photoReferenceId }`를 저장한다.
  `payload.breads[].imageKey`에는 초기 키가 남지만, 출처가 있는 보고서는 현재 사진을 찾는 식별자로 사용하지 않는다.
- 사진을 교체해도 참조 UUID를 유지하고, 그 사진에 같은 빵 종류의 태그가 있으면 수정된 사진을 보여준다.
  태그 이름이나 ID는 달라져도 된다. 태그가 없어지거나 다른 종류로 바뀌면 null로 가린다.
  사진 삭제 시에도 null로 가리며, 같은 로그의 다른 사진을 다시 선택하지 않는다. 횟수·순위는 발행 당시 값을 유지한다.
  현재 null만으로 로그 삭제와 사진 누락을 구분할 수 없으므로 화면 문구는 ‘사진을 볼 수 없어요’를 권장한다.
- 집계 버전 `bread-tags-month-end-v2`, 사진 선택 기준 `loved-then-latest-eaten`, 전체·미분류 태그 수를 원시 메타데이터에 저장한다.

### 기록한 빵집과 전월 비교

`MonthlyReportRecordAggregationService`가 기록 수·기록한 빵집 수·전월 비교를 생성에 연결한다.
`BreadRecordRepository.countMonthlyReportRecords`의 JPQL 집계는 태그를 조인하지 않으므로 기록 수가 부풀지 않는다.
가게 ID가 있으면 같은 ID를 1곳으로 세고, ID가 없으면 기록마다 각각 1곳으로 센다. 직접 입력한 이름은 비교하지 않는다.
예: A가게 ID로 3건, 같은 직접 입력 이름으로 2건을 남기면 기록 5건·기록한 빵집 3곳이다.
빵집 상세 조회 수는 이 집계에 포함하지 않는다.

전월 수치는 같은 회원의 직전 월 PUBLISHED payload의 `summary.records`를 재사용한다.
전월 원본 로그를 다시 세지 않는다. 발행본이 없거나 FAILED/WITHHELD면 `previousRecords: null`이다.
raw_metrics에는 `recordAggregation`의 버전·기록 수·빵집 수·전월 수치와 출처를 저장한다.

**현재 한계:** 삭제 시각은 보존되지만 수정 전 섭취일·가게·태그는 보존되지 않는다.
월말 후 발행 전에 수정된 내용을 과거 상태로 복원하는 이력 보강은 아직 남아 있다.
월말 조건 쿼리만으로 과거 모든 필드가 복원된다고 보지 않는다. 태그와 사진을 재생성하는 수정도 이 대상이다.

### 사진 수정 요청과 보고서 연결

기존 빵로그 수정은 사진 행을 삭제하고 다시 생성한다. 변하는 photo.id 대신 `bread_record_photo.reference_id`를
추가해 같은 사진의 파일 교체와 재정렬을 추적한다. 새 사진은 새 UUID, 기존 사진은 동일 UUID를 사용한다.
한 UUID는 현재 사진 행 한 건에만 존재하도록 DB 유니크 인덱스를 둔다.
`originalPhotoId`는 사진 교체 관계만 유지한다. 메뉴 태그는 수정 요청으로 다시 생성되므로 별도로 확인해야 한다.

`BreadRecordPhotoRequest.originalPhotoId`는 선택 필드이며, 상세 응답의 `photos[].id`를 사용한다.
프론트는 기존 사진의 편집·교체 요청에 이 값을 함께 보내야 한다. 기존 키를 그대로 쓰는 구버전 요청은 키로
연결을 유지한다. 새 키인데 originalPhotoId가 없으면 새 사진으로 취급하며 순서로 교체 관계를 추정하지 않는다.

```json
{ "fileName": "edited.jpg", "contentType": "image/jpeg", "awsKey": "bread-records/members/123/images/new-key",
  "sortOrder": 0, "width": 800, "height": 600, "originalPhotoId": 42 }
```

- 원본 ID는 수정 중인 본인 로그의 사진이어야 한다. 같은 원본을 여러 요청 사진에 중복 연결할 수 없다.
- 새 로그 생성에 원본 ID를 보내거나 원본이 없으면 400/BREAD_RECORD_INVALID_PHOTO_REFERENCE다.
- 파일 키가 바뀌면 기존 리사이즈 키와 완료 상태는 이어받지 않고 새 파일 처리 상태를 사용한다.
- 사진을 삭제하고 별도 사진을 추가하는 경우 originalPhotoId를 보내지 않는다. 보고서의 삭제된 사진을 다른 사진으로 대체하지 않는다.
- 프론트의 originalPhotoId 전송 연결은 별도 반영이 필요하다. 응답 imageKey 구조와 기존 업로드 API는 유지한다.

`V20261001170000__add_bread_photo_reference.sql`은 사진 UUID를 채우고,
`V20261001170100__link_monthly_report_bread_photos.sql`은 기존 보고서의 사진 키가 본인 사진 한 건에만
대응하는 경우 출처를 연결한다. 삭제된 사진이나 여러 기록에 공유된 키의 출처는 추측하지 않는다.

`MonthlyReportPayloadCodec.write`는 DTO를 JSON 문자열로 직렬화하고, `read`는 저장된 문자열을 DTO로 역직렬화한다.
두 경로 모두 계약 검증을 수행한다. HTTP 응답 JSON 직렬화는 Spring MVC가 별도로 처리한다.
능력치 검증은 `score <= maxScore`를 확인하며 운영 상한을 25점으로 고정하지 않는다. 상한과 산식은 아직 미정이다.

### 발행 쿼리의 선택

유니크 키와 함께 조건부 upsert를 사용하면 최초 삽입, 보류·실패 갱신, 이미 발행된 행 보존을 한 문장으로 처리한다.
`save()`도 유니크 제약·잠금·충돌 재시도를 조합하면 구현할 수 있다. 먼저 조회하고 save만 하는 방식은 두 요청이
동시에 ‘없음’을 읽는 경쟁을 해결하지 못한다. save 자체에는 ‘이미 발행된 행은 보존한다’는 조건도 없다.
현재 방식은 PostgreSQL에 의존하고 JPA 생명주기·dirty checking을 거치지 않는 단점이 있다.
native 변경 전 flush, 변경 후 영속성 컨텍스트 clear와 실제 PostgreSQL 테스트로 이를 보완한다.

1. 호출 배치가 대상 회원, 원천 이력의 완전성, 전월 집계 유무, 산식 정책을 확인한다. 전월 집계만 없으면 비교 수치를 null로 두고 발행한다.
2. 월 종료 이후에 집계한 payload, 원시 집계와 검증 메타데이터, 산식 버전, 집계 시각을 넘긴다.
3. 서비스는 응답 계약과 회원·월, KST 월 종료 시각, 필수 메타데이터를 검증한다.
4. PostgreSQL의 `INSERT ... ON CONFLICT ... WHERE status <> 'PUBLISHED'`로 발행한다.
5. 최초 발행은 true, 이미 발행된 월은 false다. 동시 호출도 한 건만 성공한다. 보류·실패 행은 완성된 결과로 발행할 수 있다.
6. 최초 발행한 회원에게만 같은 트랜잭션에서 알림을 적재한다. 커밋 후 기존 PushEventListener가 FCM 발송을 처리한다.

기존 발행본을 재시도로 덮어쓰지 않는다. 숫자 정정·재발행은 정책 확정 후 별도 경로로 추가한다.
`raw_metrics`는 비어 있지 않은 객체여야 하지만 현재 서비스가 원천 데이터의 완전성을 판정하지는 않는다.
향후 배치가 지켜야 할 선행 조건을 이 서비스의 형식 검증으로 대신해서는 안 된다.

## 발행 알림과 상세 이동

- 발행 완료 직후 알림을 요청한다. 오전 10시 예약 발송 스케줄러는 아직 없다.
- 수신자는 보고서 소유자 한 명이다. 기존 NOTICE 타입으로 알림함과 푸시에 같은 제목·본문·상세 경로를 사용한다.
- 제목은 `9월 빵일지가 도착했어요` 형태다. FCM data에 `type: NOTICE`, `notificationId`, `link: /reports/monthly/2026-09`를 보낸다.
- PushDispatcher는 저장된 deepLink를 data.link에 넣는다. 알림함 목록의 link와 같은 경로가 된다.
- 프론트의 `src/services/push/index.tsx`는 data.link를 우선 사용한다. 백그라운드 탭과 콜드스타트 모두 보고서 상세를 열 수 있으며, 콜드스타트는 초기 화면 결정 후 이동한다.
- 기존 알림 수신 설정·활성 FCM 토큰 확인을 재사용한다. 수신 거부·토큰 없음은 푸시를 건너뛰고 알림함과 보고서는 남긴다.
- 알림 적재 실패 시 발행도 롤백된다. 커밋 전에는 푸시를 보내지 않는다. 재발행 호출은 새 알림/푸시 이벤트를 만들지 않는다.
- FCM 발송 결과는 기존 notification_delivery에 기록한다. FCM 실패가 발행을 되돌리지는 않는다.

현재 기존 비동기 푸시 경로를 사용한다. 프로세스 중단으로 커밋 후 이벤트가 유실되거나 FCM이 실패한 경우의
자동 복구·예약 발송은 별도 아웃박스/재시도 작업이 필요하다. 재발행 API 호출로 푸시를 강제 재전송하지 않는다.

마이그레이션은 보고서 테이블 생성, 캐릭터·행성 보존, 사진 UUID 추가, 기존 보고서 사진 출처 연결 순서다.
운영은 기존 저장소 규칙에 따라 스키마 적용 후 애플리케이션을 배포한다. 이번 작업에서 운영 DB는 변경하지 않았다.

## 전달서 검토: 코드에서 확인한 제약

| 항목 | 확인한 코드와 영향 |
| --- | --- |
| 월 기준 | `BreadRecord`는 eatenAt/createdAt을 모두 가진다. 개인화 적재는 createdAt을 사용하므로 eatenAt 보고서에 `RECORD_STORE/RECORD_BREAD` 집계를 그대로 재사용할 수 없다. |
| 시간대 | 배포 compose의 TZ/user.timezone과 개인화 적재 설명은 Asia/Seoul 벽시계 기준이다. 빵로그 집계 범위는 월초 이상, 다음 월초 미만으로 잡아야 한다. |
| 빵 개수 | 태그에 수량 필드가 없으므로 태그 수를 실제 섭취량으로 해석할 수 없다. 동일 기록·여러 사진 중복 처리와 미분류 처리 필요. |
| SAVE/UNSAVE | `InteractionEventListener`는 AFTER_COMMIT 비동기로 기록하며 실패 시 로그만 남긴다. 현재 신호가 과거 월말 상태를 완전하게 보장하지 않는다. |
| PLAN 해제 | `StorePlanService.delete`는 하드 삭제한다. `insertPlans`는 현재 관계 테이블을 읽는다. 해제 이력이 없어 과거 월말 상태와 당일 저장·삭제를 복원할 수 없다. |
| COURSE_ADD/REMOVE | ADD는 행위자 기준 이벤트지만 REMOVE 적재는 코스 주인 기준이다. interaction에는 courseId가 없고 context는 추천 출처다. 복수 코스와 공동 편집을 가게별 마지막 이벤트로 계산하면 안 된다. |
| 캐릭터·행성 | selectedCharacter의 변경 이력은 없다. 행성은 최고 기록 수와 도착 이력을 함께 반영하는 `WorldProgressCalculator`를 재사용해야 한다. |
| 사진 수정·삭제 | 파일 키만으로는 교체를 추적할 수 없다. 사진 UUID와 원본 기록을 확인해 수정은 반영하고 삭제는 숨긴다. |

따라서 “10월부터 정확”은 코드만으로 확정할 수 없다. 실제 배포 경계와 이력 누락을 확인하고,
월말 상태가 필요한 저장·계획 이력을 보강하거나 지원 월/집계 정의를 조정해야 한다.

## 다음 결정과 구현

빵 종류 집계는 위 기준으로 구현했다. 나머지 집계·산식·자동 배치는 아래 결정과 구현이 남아 있다.

1. **빵로그:** 전달서 제안처럼 eatenAt을 기준으로 요약·빵·동네·전월 비교를 통일한다. 개인 비공개 기록은 포함한다.
2. **빵집:** 기록한 빵집은 가게 ID로 중복을 제거하고, ID 없는 로그는 각각 한 곳으로 집계한다. 이 기준은 생성 서비스에 연결했다. 빵집 상세 조회 집계는 별도 작업이다.
3. **월말 상태:** 기존 SAVE/UNSAVE 이벤트와 코스·코스 빵집의 생성·삭제 시각을 재사용한다. 계획 해제 이력과 비동기 이벤트 유실을 보완한다. 공동 코스의 월간 통계는 소유자 기준으로 정했으므로 행위자 신호를 짝짓지 않는다. 모든 도메인에 새 이력 테이블을 만드는 것으로 전제하지 않는다.
4. **유형/점수:** 운영 분포를 확인한 뒤 정규화·상한·최소 신호·동점 규칙을 정한다. 25점과 예시 총점 72를 운영 상수로 사용하지 않는다. 유형도 미정이면 발행을 보류한다.
5. **세계관:** 첫 버전은 배치 시점의 선택 캐릭터·기존 행성 계산 결과를 고정하는 안을 제안한다. 실제 업로드한 전용 그림만 artwork에 넣는다.
6. **지원/수정 정책:** 최초 지원 월과 월말 이후 수정 전 상태의 이력 보존을 구현한다. 발행 대상은 일반 회원이며, 전월 값이 없으면 비교 카드만 생략한다. 기존 전월 발행본이 있으면 그 값을 재사용하도록 구현했다.
7. **배치/푸시:** 선행 적재 완료 확인, 페이지 단위 회원 처리, 실패 상태 기록/재시도를 구현한다. 발행 후 즉시 푸시는 연결했으며, 예약 발송과 장애 자동 복구는 후속 범위다.

## 2026-10-01 정책 논의와 운영 DB 확인

### 확정한 방향과 남은 선택

- 저장·계획은 **해당 월에 추가했고 월말에도 남은 서로 다른 빵집**으로 확정했다. 이전 달부터 보유한 관계는 제외한다.
  공동 코스는 소유자 기준이다. 친구가 내 코스에 추가한 빵집도 포함하며 여러 코스에 중복된 가게는 1곳으로 센다.
- 직접 입력한 가게 이름은 빵로그의 `storeName`이다. 화면의 `summary.stores`는 ‘기록한 빵집’,
  `stores.viewed`는 ‘둘러본 빵집’(가게 상세 조회)이므로 직접 입력 기록을 조회 행동에 합산하지 않는다.
  가게 ID가 없는 직접 입력 로그는 같은 이름이어도 각각 다른 빵집으로 센다.
- 월말 후 삭제한 로그도 해당 월 통계에 포함하고, 삭제된 사진은 숨긴다. 월말 후 새로 작성한 과거 섭취일 로그는 그 월말에 존재하지 않았으므로 제외한다.
- 전월 수치가 있으면 발행본을 그대로 사용한다. 없으면 `comparison.previousRecords: null`로 발행하고 프론트에서 비교 카드만 제외한다.
  프론트 응답 검증·카드 목록·공유 이미지 연동은 아직 필요하다.
- 발행 대상은 탈퇴하지 않은 `USER`다. `GUEST`, `BLOG_BOT`은 제외한다.
  실제 활동이 없는 회원은 0과 빈 배열로 발행한다. 집계 실패나 이력 누락을 활동 없음으로 바꾸지는 않는다.
- 다음 달 1일 계산한다. 기존 행동 신호 적재가 매일 04:00 KST이므로 이를 재사용하면 적재 완료 후 계산해야 한다.
  04:30 계산과 10:00 푸시는 제안이며 아직 확정 시각이 아니다. 정해진 시각 경과만으로 선행 적재 성공을 가정하지 않는다.
- 능력치 산식·유형 규칙은 논의 중이다. 아래 분포는 산식 확정값이 아니다.

### 운영 DB 실측

2026-10-01 운영 `soboro`에 기존 터널로 접속해 스키마와 집계값만 조회했다.
모든 조회에 `default_transaction_read_only=on`, `BEGIN READ ONLY`, 15초 statement timeout을 적용했다.
운영 데이터와 스키마는 변경하지 않았다. 아래 건수는 조회 시점 값이다.

| 대상 | 확인한 저장 정보 | 월간 보고서에서 활용 / 보완 |
| --- | --- | --- |
| 저장·해제 | `user_store_interaction`: SAVE 1,414건, UNSAVE 9건 | 기존 이벤트 재사용. 최초 UNSAVE 시각은 9월 21일이며 그 이전의 완전한 해제 이력은 확인되지 않았다. AFTER_COMMIT 비동기 저장의 유실 가능성도 보완 대상. |
| 계획 | `store_plan` 58행, PLAN 이벤트 57건 | 현재 관계와 추가 시각은 있음. 원본 행은 하드 삭제하며 계획 해제 이벤트는 없음. 미래의 월말 복원을 위해 해제 시각 보존 필요. |
| 코스 | `courses` 989행, 삭제 표시 267행 | created_at/deleted_at로 생성·삭제를 판단할 수 있음. 별도 코스 삭제 이력 테이블부터 만들 필요 없음. |
| 코스 내 빵집 | `course_stores` 2,210행, 삭제 표시 183행 | course_id/store_id/created_at/deleted_at 재사용. 여러 코스 중 월말에 유효한 관계가 하나라도 있으면 유지 상태. |
| 코스 추가·제거 신호 | COURSE_ADD 1,828건, COURSE_REMOVE 176건 | interaction에는 course_id가 없음. ADD는 행위자, REMOVE는 코스 주인 기준이므로 공동 코스의 개인 행동 이력으로 그대로 짝지을 수 없음. |

관련 테이블에 변경 이력을 별도로 남기는 DB 트리거는 없었다.
현재 계획과 연결되지 않는 PLAN 이벤트는 이번 조회에서 0건이었지만, 이것이 계획 해제 기록 기능이 있다는 뜻은 아니다.
과거 occurred_at의 이벤트 상당수가 9월 21~22일 이후 적재됐으므로 최초 사건 시각만으로 이력 수집 시작을 판단하지 않는다.

### 점수 설계용 9월 표본

현재 탈퇴하지 않은 USER 중 10월 1일 이전에 가입한 640명을 대상으로 조회했다.
개인 식별 정보는 추출하지 않았다. 0인 회원은 양수 분포에서 제외했다.

| 신호 | 1회 이상 회원 | 양수 회원 중앙값 | 양수 회원 90백분위 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| 서로 다른 빵집 조회 | 153 | 3 | 16.8 | 41 |
| 삭제되지 않은 9월 섭취일 빵로그 | 68 | 1 | 6 | 15 |
| 9월 저장 후 조회 시점까지 남은 빵집 | 36 | 3 | 15.5 | 51 |
| 9월 생성 후 월말에 남은 코스 | 77 | 1 | 2 | 6 |

저장 표본은 **현재 남은 관계**이므로 월말 상태의 확정 통계가 아니다. 코스 수는 계획 빵집 수와 다른 지표다.
일반 회원의 9월 빵로그 185건 중 가게 ID 없이 직접 입력한 기록은 2건이었다.

게이지 제안은 능력치당 같은 상한을 두고, 양수 활동 분포로 축별 기준량을 정한 뒤 산식 버전 동안 고정하는 방식이다.
예를 들어 `round(25 * min(1, log(1 + 활동량) / log(1 + 기준량)))`이면 기준량 10일 때
활동량 0/1/3/6/10은 점수 0/7/14/20/25가 된다. 낮은 활동량도 표시되고 많은 활동의 증가폭은 완만해진다.
25점, 기준량 10, 로그 산식은 모두 논의용 예시다. 개인별 최대 활동량을 만점으로 삼거나 매달 최고 회원에 따라
척도를 바꾸면 활동량 차이와 월별 변화를 읽기 어려우므로 제안하지 않는다.

## 검증

```sh
./gradlew test --tests 'sweetgirl.croissant.monthlyreport.*'
```

PostgreSQL 전용 테스트는 임시 스키마를 만들고 삭제한다. 테스트용 DB 연결만 제공한다.

```sh
MONTHLY_REPORT_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:포트/테스트DB \
MONTHLY_REPORT_TEST_DB_USER=테스트사용자 \
MONTHLY_REPORT_TEST_DB_PASSWORD=테스트비밀번호 \
./gradlew test -PlabTests --tests '*MonthlyReportRepositoryTest'
```

계약·HTTP 테스트는 본인 인증, 월 형식, 누락/미집계/0, 합계 오류, 다른 회원 payload, 삭제 사진,
404/500/503 구분을 확인한다. PostgreSQL 테스트는 실제 마이그레이션, JSONB 저장, 회원·월 분리,
동시 발행, 재시도 보존, 실패 후 발행, FK/상태 제약과 사진 소유권을 확인한다.
목록의 회원·상태 필터, 최신순·페이지 분리, 빈 목록, 커밋 후 이벤트와 롤백 시 알림 취소도 검증한다.
푸시 테스트는 FCM을 모의 객체로 대체해 data.link와 수신 거부를 확인한다. 실제 기기 발송은 수행하지 않는다.
JPA 매핑을 실제 PostgreSQL 스키마와 validate하며, 캐릭터 선택·행성 진행도·카탈로그 이름 변경 후 재조회,
발행 재시도, JSON과 열 불일치 거절, 기존 발행본의 스냅샷 열 백필을 검증한다.
