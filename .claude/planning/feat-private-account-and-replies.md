# feat/private-account-and-replies — 비공개 계정(팔로우 승인제) · 댓글 답글

사용자 편지 두 건에서 나온 요구. 2026-11-04.

- "다른 분들이 달아주신 댓글에 제 답변을 달고 싶어요" → 댓글에 답글(스레드)
- "계정 비공개는 없나요??" → 인스타식 비공개 계정

## Spec

### A. 비공개 계정

- 회원 설정 `isPrivate`(기본 false). `PATCH /users/me/privacy { isPrivate }`, `GET /users/me/privacy`.
- 비공개 계정을 팔로우하면 즉시 팔로우되지 않고 **팔로우 요청**이 남는다. 공개 계정은 지금처럼 즉시.
- 요청받은 사람이 수락/거절한다. 수락하면 `member_follow` 행이 생긴다(기존 `insertIfAbsent`).
- 요청한 사람이 취소할 수 있다(= 언팔로우 API 가 요청도 지운다).
- 알림: 요청이 오면 `FOLLOW_REQUEST`(받는 사람), 수락되면 `FOLLOW_ACCEPTED`(요청한 사람). 둘 다 `follow` 스위치를 따른다.
- 보이기 규칙 — 보는 사람이 본인도 아니고 팔로워도 아니면 비공개 계정의 **공개 빵로그를 못 본다**:
  - 빵로그 상세 · 댓글 · 좋아요 목록(`BreadRecordVisibilityPolicy`)
  - 프로필의 공개 빵로그 목록(`/members/{id}/bread-records`) → 빈 목록, 다녀간 빵집 → 빈 목록
  - 가게 공개 빵로그 목록 · 수 · 추천순(`findPublicByStoreId` 등)에서 작성자가 비공개면 제외
  - 프로필 자체(닉네임 · 캐릭터 · 레벨 · 수)는 보인다. `isPrivate` · `followRequestedByMe` · `canViewRecords` 를 더한다.
- 전환 규칙: 공개→비공개는 기존 팔로워 유지. 비공개→공개는 대기 요청을 전부 자동 수락(알림 없음).
- 차단하면 두 사람 사이 요청도 양방향으로 지운다. 탈퇴하면 그 사람의 요청을 전부 지운다.
- 응답은 필드 추가만.

### B. 댓글 답글

- `record_comment.parent_comment_id`(nullable, self FK). 깊이는 1 — 답글의 답글은 같은 부모에 붙는다.
- `POST /bread-records/{recordId}/comments` 에 `parentCommentId`(선택) 추가. 부모는 같은 빵로그 · 지워지지 않은 댓글이어야 한다(아니면 404 COMMENT404).
- 목록 응답에 `parentId` 추가. 목록은 지금처럼 평면 · 오래된 순(구버전 앱 호환). 새 앱이 `parentId` 로 묶는다.
- 알림: 부모 댓글 작성자에게 `COMMENT_REPLY`("OO님이 회원님의 댓글에 답글을 남겼어요"). 빵로그 작성자 알림(RECORD_COMMENT)은 그대로. 부모 작성자 = 빵로그 작성자 = 나 면 중복 없이 하나만.
- 부모 댓글을 지우면 답글도 함께 지운다(같은 deletedBy) — 알림도 거둔다.

## Plan

### 마이그레이션
- `V20261104100000__member_private_account_and_follow_request.sql`: `member.is_private BOOLEAN NOT NULL DEFAULT false`, `member_follow_request(requester_id, target_id, created_at)` PK(requester, target), 인덱스 target.
- `V20261104110000__record_comment_parent.sql`: `record_comment.parent_comment_id BIGINT REFERENCES record_comment(id)`, 인덱스.

### 코드 (A)
- `member/entity/Member`: `isPrivate` + `changePrivacy(boolean)`.
- `member/dto/request/PrivacyUpdateRequest`, `member/dto/response/PrivacyResponse`.
- `member/service/MemberCommandService.updatePrivacy`, `MemberQueryService.getPrivacy`. 공개 전환 시 `FollowRequestCommandService.acceptAll(memberId)` 호출 — 순환 피하려고 이벤트 `MemberPrivacyChangedEvent` 로.
- `follow/entity/MemberFollowRequest(+Id)`, `follow/repository/MemberFollowRequestRepository`.
- `follow/service/FollowCommandService.follow`: 상대가 비공개면 요청 생성(+알림). `unfollow`: 요청도 지운다.
- `follow/service/FollowRequestCommandService`: accept · reject · acceptAllOf(공개 전환).
- `follow/service/FollowRequestQueryService`: 받은 요청 목록.
- `follow/service/FollowVisibilityReader`: `canViewRecords(viewerId, owner)`.
- `follow/controller/FollowRequestController`: `GET /members/me/follow-requests`, `POST /members/{id}/follow-requests/accept`, `POST .../reject`.
- `FollowBlockListener` · `FollowWithdrawalListener`: 요청 삭제 추가.
- `alarm/enums/NotificationType`: `FOLLOW_REQUEST`, `FOLLOW_ACCEPTED`. `NotificationSetting.allowsCommunity` 에 follow 스위치로.
- `alarm/service/CommunityNotificationService.notifyFollowRequest` · `notifyFollowAccepted`.
- `breadlog/service/BreadRecordVisibilityPolicy.canView`: 비공개 작성자 + 팔로워 아님 → false.
- `breadlog/repository/BreadRecordRepository`: 가게 목록 3개 쿼리에 비공개 작성자 조건.
- `breadlog/service/BreadRecordSocialQueryService` · `profile/service/VisitedStoreQueryService`: 못 보면 빈 목록.
- `profile/service/ProfileQueryService` · `ProfileResponse`: `isPrivate` · `followRequestedByMe` · `canViewRecords`.
- `FollowMemberResponse`: 필드 추가 없음(목록 줄은 그대로).

### 코드 (B)
- `comment/entity/BreadRecordComment`: `parent` + `ofReply`.
- `comment/dto/request/CommentCreateRequest.parentCommentId`.
- `comment/dto/response/CommentResponse.parentId`.
- `comment/repository/BreadRecordCommentRepository`: `findActiveRepliesByParentId`, `findActiveByIdAndRecordId`.
- `comment/service/CommentCommandService.create/delete`.
- `alarm`: `COMMENT_REPLY` + `notifyCommentReply`.

### 테스트
- 서비스: FollowCommandServiceTest(비공개 → 요청), FollowRequestCommandServiceTest, FollowRequestQueryServiceTest, FollowVisibilityReaderTest, BreadRecordVisibilityPolicyTest(+), ProfileQueryServiceTest(+), VisitedStoreQueryServiceTest(+), BreadRecordSocialQueryServiceTest(+), MemberCommandServiceTest(+), CommentCommandServiceTest(+), CommunityNotificationServiceTest(+).
- 컨트롤러: FollowRequestControllerTest, MemberControllerTest(+), BreadRecordCommentControllerTest(+), 계약 테스트 3개(+필드).
- 리포지토리: MemberFollowRequestRepositoryTest, BreadRecordRepository 가게 목록 비공개 제외(기존 통합 테스트 있으면 +).

### 위험
- 가게 목록 네이티브 쿼리 수정 — H2 통합 테스트로 확인.
- `BreadRecordVisibilityPolicy` 는 여러 곳에서 쓰여 성능(팔로우 exists 쿼리 1회 추가). 비공개 작성자일 때만 탄다.
- 구버전 앱은 `FOLLOW_REQUEST` 알림을 title · body 로 그린다(서버가 문구를 둔다). 탭하면 deepLink `/follow-requests` — 앱이 모르면 무시.

## 예외 상황 점검 (2026-11-04, 구현 반영)

| 상황 | 처리 |
|---|---|
| 차단 관계에서 팔로우/요청 | 403 FOLLOW403_BLOCKED (요청 전에 막음) |
| 요청 뒤 한쪽이 차단 | 요청 양방향 삭제 + 양쪽 알림함의 "요청했어요" 회수 |
| 요청 뒤 한쪽이 탈퇴 | 요청 삭제. 수락 시점에 요청자가 탈퇴했으면 요청만 지우고 404 |
| 같은 사람이 요청 반복 | 멱등(REQUESTED 그대로), 알림은 한 번(uk_notification_actor_target) |
| 하루 100명 상한 | 팔로우 + 요청 합산 — 비공개 계정에 요청 도배 불가 |
| 수락 두 번 · 없는 요청 거절 | 404 FOLLOW404_REQUEST |
| 수락했는데 이미 팔로워(경합) | insert 0 → 알림 없이 끝 |
| 비공개 → 공개 | 대기 요청 전부 팔로워로(알림 없음) |
| 공개 → 비공개 | 기존 팔로워 유지, 이후 팔로우는 요청으로 |
| 같은 값으로 설정 변경 | 이벤트 안 냄 |
| 비공개 계정 글 접근(상세·댓글·좋아요·멘션 후보) | BreadRecordVisibilityPolicy 403 BREAD_RECORD403_NOT_VISIBLE |
| 비공개 계정 프로필 | 프로필 자체는 보임(닉네임·캐릭터·수) + isPrivate · followRequestedByMe · canViewRecords |
| 프로필 빵로그 목록 · 다녀간 빵집 | 팔로워 아니면 빈 목록(쿼리 안 탐) |
| 가게 공개 빵로그 목록 · 수 · 추천순 | 비공개 작성자 글은 팔로워·본인에게만 |
| 목록 줄(검색·팔로워·추천·좋아요) | isPrivate · followRequestedByMe 로 "요청됨" 버튼 상태 |
| 팔로잉 새 글 알림 | 팔로워에게만 가므로 받은 사람은 늘 볼 수 있음 |
| 같이 간 사람 태그(비공개 작성자, 태그된 사람이 팔로워 아님) | 알림은 가지만 상세는 403 — 인스타와 같음. 앱은 403 을 "볼 수 없어요"로 |
| 답글 대상이 지워짐·숨김·다른 글 | 404 COMMENT404 |
| 답글의 답글 | 최상위에 붙음(깊이 1), 알림은 내가 고른 댓글 작성자에게 |
| 부모 댓글 삭제(작성자/빵로그 주인) | 답글도 같은 사유로 삭제 + 알림 회수. 답글만 지우면 형제 유지 |
| 부모 작성자 = 빵로그 작성자 | RECORD_COMMENT 하나만(COMMENT_REPLY 중복 안 냄) |
| 구버전 앱 | 응답 필드 추가만. 댓글 목록은 평면 그대로(parentId 무시) · 팔로우 POST data 는 안 읽음 · 새 알림 종류는 title/body 로 그림 |

## FE 에서 할 일
- 설정: 비공개 계정 토글(`GET/PATCH /users/me/privacy`)
- 남의 프로필: `isPrivate` 자물쇠, `canViewRecords=false` 면 잠금 화면, 팔로우 버튼 상태 3종(팔로우 / 요청됨 / 팔로잉 — `followedByMe` · `followRequestedByMe`), 팔로우 POST 응답 `status` 로 즉시 전환
- 받은 요청 화면(`GET /members/me/follow-requests`, accept/reject) + 마이 탭 배지(`/count`)
- 알림: FOLLOW_REQUEST(탭 → 요청 화면) · FOLLOW_ACCEPTED(탭 → 프로필) · COMMENT_REPLY(탭 → 댓글)
- 댓글: 각 댓글에 "답글" 버튼 → `parentCommentId` 로 작성, 목록은 `parentId` 로 1단 들여쓰기
- 403 BREAD_RECORD403_NOT_VISIBLE 을 "볼 수 없는 빵로그예요"로(이미 있음)
