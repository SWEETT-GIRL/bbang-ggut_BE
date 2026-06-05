# User Report Moderation TODO

이 문서는 사용자 신고 API 이후 이어서 구현할 작업 목록이다.
Codex에게 바로 이어서 요청할 때는 아래 "복붙용 요청" 블록을 그대로 붙여넣으면 된다.

## 복붙용 요청

```text
사용자 신고 운영 정책 기준으로 다음 moderation API들을 작은 커밋 단위로 구현해줘.

우선순위:
1. 관리자 신고 상세 조회
2. 리뷰 숨김/삭제 moderation action
3. 프로필 초기화/회원 작성 제한
4. 신고 결과 알림
5. 이의제기
6. 자동 선조치/신고 통계

기존 공통 신고 API는 POST /reports, 관리자 목록/검수 API는 USER_REPORT kind로 이미 있다.
기존 커밋/구조를 먼저 읽고, 사용자 변경 파일은 건드리지 말고, 각 단계마다 테스트와 마이그레이션을 포함해서 작은 단위로 커밋해줘.
```

## 현재 완료된 범위

- `POST /reports`: 리뷰/프로필 공통 신고 접수
- `GET /api/admin/reports?kind=USER_REPORT`: 관리자 신고 목록 조회
- `PATCH /api/admin/reports/{reportId}` with `kind=USER_REPORT`: 신고 승인/거절
- `GET /api/admin/reports/{reportId}?kind=USER_REPORT`: 관리자 신고 상세 조회
- `POST /api/admin/reports/{reportId}/moderation-actions`: 리뷰 숨김/삭제/복구 moderation action
- `POST /api/admin/members/{memberId}/profile-reset`: 회원 닉네임 초기화
- `POST /api/admin/members/{memberId}/restrictions`: 회원 제한 생성
- `user_report` 테이블 마이그레이션
- `review.hidden_at` 및 `moderation_action` 테이블 마이그레이션
- `member_restriction` 테이블 마이그레이션
- 신고 운영 정책 문서

현재 관리자 승인/거절은 신고 상태를 변경하고, 승인된 리뷰 신고에는 숨김/삭제/복구 조치를 적용할 수 있다.
프로필 초기화와 회원 제한을 적용할 수 있으며, 리뷰 생성 API는 활성 작성 제한을 검사한다.

## 1. 관리자 신고 상세 조회 ✅ 완료

목표:

- 운영자가 신고 대상 원문과 신고 정보를 한 화면에서 판단할 수 있게 한다.

API:

```http
GET /api/admin/reports/{reportId}?kind=USER_REPORT
```

응답 초안:

```json
{
  "reportId": 1,
  "kind": "USER_REPORT",
  "targetType": "REVIEW",
  "targetId": 123,
  "reasonType": "ABUSE",
  "reasonDetail": "욕설",
  "status": "PENDING",
  "reporterId": 10,
  "reportedMemberId": 20,
  "createdAt": "2026-06-04T19:30:00",
  "reviewedAt": null,
  "target": {
    "reviewId": 123,
    "menuId": 1,
    "menuName": "소금빵",
    "storeId": 2,
    "storeName": "빵집",
    "authorId": 20,
    "content": "문제된 리뷰 내용",
    "rating": 4.5,
    "images": [],
    "createdAt": "2026-06-04T18:00:00"
  }
}
```

구현 메모:

- `targetType=REVIEW` 상세부터 먼저 구현한다.
- `targetType=MEMBER_PROFILE`은 현재 Member에 닉네임만 있으므로 `memberId`, `nickname`, `createdAt` 정도만 반환한다.
- 삭제/숨김된 대상도 신고 상세에서는 조회 가능해야 할 수 있으므로 repository 메서드를 분리 검토한다.

추천 커밋:

```text
Add admin user report detail API
```

## 2. 리뷰 숨김/삭제 moderation action ✅ 완료

목표:

- 신고 승인 이후 운영자가 실제 콘텐츠 조치를 수행할 수 있게 한다.
- 숨김과 삭제를 분리한다.

API:

```http
POST /api/admin/reports/{reportId}/moderation-actions
```

Request:

```json
{
  "action": "HIDE_REVIEW",
  "reason": "욕설 포함",
  "notifyUser": true
}
```

action 후보:

```text
NONE
HIDE_REVIEW
DELETE_REVIEW
RESTORE_REVIEW
```

필요 DB 초안:

```sql
ALTER TABLE review
    ADD COLUMN hidden_at TIMESTAMP,
    ADD COLUMN hidden_reason VARCHAR(500),
    ADD COLUMN hidden_by BIGINT REFERENCES member (id);

CREATE TABLE moderation_action (
    id BIGSERIAL PRIMARY KEY,
    report_id BIGINT REFERENCES user_report (id),
    target_type VARCHAR(30) NOT NULL,
    target_id BIGINT NOT NULL,
    member_id BIGINT REFERENCES member (id),
    action VARCHAR(30) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL
);
```

구현 메모:

- 기존 `Review.softDelete()`는 작성자 삭제에도 쓰이므로, 운영자 숨김은 `hiddenAt`으로 분리한다.
- 일반 리뷰 목록/상세 조회에서 `hiddenAt IS NULL` 조건을 반영한다.
- 관리자 신고 상세에서는 숨김 리뷰도 볼 수 있게 한다.
- `DELETE_REVIEW`는 기존 soft delete를 재사용할 수 있지만, 관리자 삭제 사유는 `moderation_action`에 남긴다.

추천 커밋:

```text
Add review moderation actions
```

## 3. 프로필 초기화/회원 작성 제한 ✅ 완료

목표:

- 프로필 신고 승인 후 닉네임 초기화 또는 회원 작성 제한을 수행한다.

API:

```http
POST /api/admin/members/{memberId}/profile-reset
```

```json
{
  "reason": "부적절한 프로필"
}
```

닉네임은 요청으로 받지 않고 서버 정책값 `빵긋 사용자`로 초기화한다.

```http
POST /api/admin/members/{memberId}/restrictions
```

```json
{
  "type": "WRITE_RESTRICTED",
  "durationDays": 7,
  "reason": "최근 90일 내 반복 위반"
}
```

restriction type 후보:

```text
WARNING
WRITE_RESTRICTED
PROFILE_RESTRICTED
SUSPENDED
```

필요 DB 초안:

```sql
CREATE TABLE member_restriction (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL REFERENCES member (id),
    restriction_type VARCHAR(30) NOT NULL,
    reason VARCHAR(500),
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
```

구현 메모:

- 현재 Member에는 프로필 이미지가 없고 nickname만 있다.
- 닉네임 초기화 정책값 예: `빵긋 사용자`
- 작성 제한은 리뷰/댓글 생성 API에서 공통 검사할 수 있도록 `MemberRestrictionReader` 같은 컴포넌트로 분리한다.

추천 커밋:

```text
Add member moderation actions
```

## 4. 신고 결과 알림

목표:

- 신고자와 피신고자에게 서로 다른 수준의 알림을 보낸다.

알림 종류:

```text
USER_REPORT_RESULT
MODERATION_ACTION
```

신고자 문구:

```text
신고 검토가 완료되었습니다.
운영 정책에 따라 필요한 조치를 진행했습니다.
```

피신고자 문구:

```text
운영 정책 위반으로 콘텐츠가 제한되었습니다.
결정에 동의하지 않는 경우 30일 이내에 이의제기를 요청할 수 있습니다.
```

구현 메모:

- 기존 `ReportNotificationService`는 가게/메뉴 제보용 문구이므로 그대로 재사용하지 않는다.
- 사용자 신고용 알림 서비스를 별도 생성하거나, 알림 도메인을 generic하게 확장한다.
- 신고자에게는 구체적인 제재 수위나 피신고자 정보를 노출하지 않는다.

추천 커밋:

```text
Add user report moderation notifications
```

## 5. 이의제기

목표:

- 콘텐츠 삭제, 프로필 초기화, 작성 제한, 계정 정지에 대해 사용자가 재검토를 요청할 수 있게 한다.

사용자 API:

```http
POST /moderation-appeals
```

```json
{
  "actionId": 55,
  "content": "해당 리뷰는 욕설이 아닙니다."
}
```

관리자 API:

```http
GET /api/admin/moderation-appeals?status=PENDING&page=0&size=20
PATCH /api/admin/moderation-appeals/{appealId}
```

```json
{
  "action": "ACCEPT",
  "adminMemo": "복구 처리"
}
```

필요 DB 초안:

```sql
CREATE TABLE moderation_appeal (
    id BIGSERIAL PRIMARY KEY,
    moderation_action_id BIGINT NOT NULL REFERENCES moderation_action (id),
    member_id BIGINT NOT NULL REFERENCES member (id),
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    admin_memo VARCHAR(1000),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);
```

구현 메모:

- 이의제기는 조치 후 30일 이내만 허용한다.
- 최초 처리자와 다른 관리자가 처리하는 정책은 코드로 강제할지 운영 룰로 둘지 결정 필요.
- 이의제기 승인 시 리뷰 복구, 제한 해제, 위반 카운트 제거를 고려한다.

추천 커밋:

```text
Add moderation appeal API
```

## 6. 자동 선조치/신고 통계

목표:

- 운영자가 우선순위를 판단할 수 있게 신고 통계와 자동 후보 표시를 제공한다.

초기 자동 기준:

- 같은 리뷰가 서로 다른 신고자 3명 이상에게 24시간 내 신고되면 임시 숨김 후보
- `PRIVACY`, `HATE`, `SEXUAL` 신고가 2명 이상이면 우선 검수 큐 상단
- 동일 회원의 리뷰 3개 이상이 24시간 내 신고되면 회원 단위 검수 후보

관리자 API 후보:

```http
GET /api/admin/reports/stats
GET /api/admin/reports/high-priority?status=PENDING&page=0&size=20
```

구현 메모:

- 초기에는 자동 숨김을 바로 켜지 않는다.
- 먼저 우선순위 큐와 통계만 제공한다.
- 운영 데이터가 쌓인 뒤 자동 숨김을 별도 feature flag로 켠다.

추천 커밋:

```text
Add user report moderation stats
```

## 검증 기준

각 단계마다 최소 아래를 수행한다.

```bash
./gradlew spotlessCheck checkstyleMain test --tests 'sweetgirl.croissant.report.*'
```

관리자 기능을 건드리면 추가로 실행한다.

```bash
./gradlew test --tests 'sweetgirl.croissant.admin.service.AdminReportServiceTest'
```

전체 테스트는 로컬 DB 마이그레이션 상태와 기존 테스트 실패의 영향을 받을 수 있으므로, 실패 시 이번 변경과 관련된 실패인지 먼저 분리한다.

## 주의 사항

- 기존 `gate_fix_validation.csv` 변경은 신고 API 작업과 무관하므로 커밋에 섞지 않는다.
- 신고 상태 변경과 실제 제재 조치는 계속 분리한다.
- 신고자와 피신고자 정보는 서로에게 노출하지 않는다.
- 강한 제재는 반드시 `moderation_action` 이력으로 남긴다.
