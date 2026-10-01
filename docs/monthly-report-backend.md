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
- 삭제된 회원은 기존 `MemberReader` 규칙으로 조회가 차단된다. 정지·게스트의 발행 대상 정책은 배치 연결 전에 확정한다.
- 필수 수치는 nullable wrapper로 역직렬화하고 검증한다. 누락을 0으로 바꾸지 않으며 문자열·소수·음수·안전한 정수 범위 초과를 거절한다.
- `comparison.currentRecords`와 요약 기록 수, 능력치별 상한과 총합, artwork의 캐릭터·행성·유형 일치를 검증한다.
- `totalScore: null`, `abilities: null`은 그대로 전달한다. 집계 완료된 0과 전월의 실제 값도 그대로 전달한다.
- 조회 응답에는 `Cache-Control: no-store`를 붙인다. 조회 시 원시 활동을 다시 집계하지 않는다.
- 빵 사진 키만 현재 `bread_record_photo`와 삭제되지 않은 본인 기록에 연결되는지 한 번에 확인한다. 삭제·사진 교체·다른 회원의 키는 응답에서 null로 바꾼다. 기록 공개 여부는 본인 조회에 영향을 주지 않는다.
- 사진은 DB에 저장된 원본/리사이즈 상대 키를 스냅샷에 사용한다. 삭제된 키를 가린다고 발행 당시 숫자를 재계산하지는 않는다.

## 내 보고서 목록

`GET /reports/monthly/me?page=0&size=12`

- 동일한 Bearer 인증을 사용한다. 본인의 PUBLISHED만 보고서 월 내림차순으로 반환한다.
- 기본값은 page=0, size=12다. page는 0 이상, size는 1~100이며 범위 밖이면 400/MONTHLY_REPORT4002다.
- 데이터가 없으면 200과 `content: [], hasNext: false`를 반환한다. WITHHELD/FAILED는 목록에 포함하지 않는다.
- 목록에는 전체 payload 대신 월·발행 시각·상세 경로만 담는다. 다음 페이지는 page를 1 증가시킨다.
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
        "link": "/reports/monthly/2026-09"
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
동시 발행을 막는 PostgreSQL upsert와 사진 소유권 쿼리는 Spring Data의 native query로 유지한다.

### 발행 당시 캐릭터·행성 보존

Entity에 `characterCode`, `characterName`, `characterImageKey`, `planetCode`, `planetName`을 저장한다.
현재 MemberWorld나 카탈로그와의 연관관계로 두지 않으며, 조회 때 현재 선택·진행도·이름을 다시 가져오지 않는다.
보고서의 닉네임·통계·능력치·전용 artwork 코드/버전/키는 최종 payload에 그대로 보관한다.

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

1. 호출 배치가 대상 회원, 원천 이력의 완전성, 전월 집계 완료, 산식 정책을 확인한다.
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

마이그레이션은 `V20261001131708__create_monthly_report_member.sql`과
`V20261001140644__add_monthly_report_appearance.sql`이다.
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
| 삭제 사진 | 스냅샷 JSON의 사진은 원본 삭제와 자동 연동되지 않는다. 이번 조회의 소유권·존재 확인으로 노출을 막는다. |

따라서 “10월부터 정확”은 코드만으로 확정할 수 없다. 실제 배포 경계와 이력 누락을 확인하고,
월말 상태가 필요한 저장·계획 이력을 보강하거나 지원 월/집계 정의를 조정해야 한다.

## 다음 결정과 구현

아래는 제안이며 아직 산식이나 배치 동작으로 구현하지 않았다.

1. **빵로그:** 전달서 제안처럼 eatenAt을 기준으로 요약·빵·동네·전월 비교를 통일한다. 개인 비공개 기록은 포함한다.
2. **빵/빵집:** 빵 수의 태그 중복 제거 단위, 미분류, 직접 입력 빵집 포함 여부를 정한다. 요약 빵 수와 순위는 같은 단위를 쓴다.
3. **월말 상태:** 회원·가게뿐 아니라 코스 ID와 행위자를 보존하는 관계 변경 이력이 필요하다. PLAN 해제, 코스 삭제, 복수 코스를 포함하고 트랜잭션 안에서 유실 없이 기록하는 방안을 검토한다.
4. **유형/점수:** 운영 분포를 확인한 뒤 정규화·상한·최소 신호·동점 규칙을 정한다. 25점과 예시 총점 72를 운영 상수로 사용하지 않는다. 유형도 미정이면 발행을 보류한다.
5. **세계관:** 첫 버전은 배치 시점의 선택 캐릭터·기존 행성 계산 결과를 고정하는 안을 제안한다. 실제 업로드한 전용 그림만 artwork에 넣는다.
6. **지원/수정 정책:** 최초 지원 월, 게스트·정지 회원, 지연·수정·삭제 기록과 전월 수치의 재집계 기준을 정한다.
7. **배치/푸시:** 선행 적재 완료 확인, 페이지 단위 회원 처리, 실패 상태 기록/재시도를 구현한다. 발행 후 즉시 푸시는 연결했으며, 예약 발송과 장애 자동 복구는 후속 범위다.

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
