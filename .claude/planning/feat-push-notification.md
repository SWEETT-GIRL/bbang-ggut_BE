# 푸시 알림 — 설계

작성 2026-09-10 · 브랜치 `feat/push-notification` · base `origin/main`

> 구현 전에 판단을 끝내기 위한 문서다. 여기 없는 결정이 구현 중에 튀어나오면 설계가 덜 된 것이다.

---

## 0. 무엇을 만드는가

**찜 알림과 공지에 푸시를 붙인다.** 지금 이 둘은 인앱 알림함에만 쌓이고 푸시가 나가지 않는다.

곁들여 **알림 저장 구조를 원본·우편함·발송대장 셋으로 가른다.** 푸시가 붙으면
"인앱에는 쌓였는데 푸시는 안 나갔다"가 정상 상태가 되는데, 지금 구조는 그걸 적을 자리가 없다.

## 1. 지금 무엇이 문제인가

`notification` 한 행이 곧 한 사람의 우편함 줄이다. 전체공지를 보내면 회원 수만큼 행이 생기고
`title`·`body`가 통째로 복사된다. 문구를 고치려면 수만 행을 UPDATE 해야 한다.

그리고 알림함이 둘로 갈려 있다 — `notification`(찜·공지)과 `report_notification`(제보 결과).
조회 API도 따로다. 푸시 파이프라인을 두 벌 만들 이유가 없으므로 이번에 합친다.

## 2. 테이블 셋

| 테이블 | 답하는 질문 | 공지 1건 기준 |
|---|---|---|
| `notification` | 무엇을 알리는가 | **1행** |
| `notification_recipient` | 누구의 알림함에 있는가 | 사람 수만큼 |
| `notification_delivery` | 어느 채널로 언제 나갔고 눌렸나 | 인앱 + 푸시 보낸 만큼 |

읽음(`read_at`)은 우편함에 있다. 발송 결과는 대장에만 있다.
**인앱도 채널이라 대장에 쌓는다** — 우편함과 겹쳐 보이지만 답하는 질문이 다르다.
우편함은 "지금 알림함에 무엇이 있나", 대장은 "언제 어느 채널로 나갔고 눌렸나"다.

대장은 이번 릴리즈부터 쌓는다. 이관해 온 옛 알림에는 행이 없다 — 그때 무엇이 나갔는지
기록이 없기 때문이다.

마이그레이션은 `V20260910120000__restructure_notification_for_push.sql`.

### 뒤집은 결정

| 원안 | 최종 | 이유 |
|---|---|---|
| `notification`에 `title`/`body`만 | **`actor_id`·`target_*` 유지** | 활동 알림 문구는 앱이 조립한다. 서버가 문장을 박으면 닉네임이 바뀌어도 옛 이름이 남고, 탈퇴 표시도 못 한다 |
| `delivery`를 기기(`fcm_token_id`) 단위 | **사람 단위 유지** | 죽은 토큰 정리는 `FcmService`가 발송 응답을 보고 바로 한다 — 대장을 볼 일이 없다. 기기별 성패를 볼 화면도 없다. 토큰이 여럿이면 하나라도 성공했을 때 `SENT` |
| 지운 코스 알림 숨기기 | **보여주고 눌리면 안내** | "분명히 봤는데 사라졌다"가 안 된다. §4 참조 |

### 확정 전제

- 빵알람(지오펜스)은 **기능이 없다.** `StoreNotification`·`AlarmCommandService` 지오펜스 경로는 이번 작업 범위 밖이다
- `MarketingNotification` 엔티티는 아무 데서도 안 쓰인다. `AD` 붙일 때 지우고 새로 간다
- `notification_legacy`·`report_notification`·`course_save_notification`은 **이 마이그레이션에서 DROP한다.** 남겨봐야 되돌리는 수단이 아니다 — rename 하는 순간 옛 코드는 테이블을 못 찾고(`ddl-auto=validate`), 엔티티를 지우면 코드에서 닿지도 않는다
- **배포 전 DB 스냅샷이 전제다.** 되돌릴 자리는 그것 하나뿐이다. 스냅샷 없이 배포하지 않는다
- 다계정·기기 어뷰징은 막지 않는다

## 3. 알림 종류

| type | 문구 주인 | 푸시 | 비고 |
|---|---|---|---|
| `COURSE_SAVED` | 앱 | **이번에 붙임** | 이름 유지 — 배포된 앱이 이 값으로 분기한다 |
| `NOTICE` | 서버 | **이번에 붙임** | |
| `CONTRIBUTION_RESULT` | 서버 | 기존 유지 | 메뉴/가게 추가·가게 정보 제보의 승인·반려 |
| `ABUSE_REPORT_RESULT` | 서버 | 기존 유지 | 내가 신고한 건의 처리 결과 |
| `MODERATION_ACTION` | 서버 | 기존 유지 | 내가 제재당한 알림. 이의제기 흐름이 붙어 있어 앱이 다르게 그린다 |
| `AD` | 서버 | 나중 | `isMarketingEnabled` 동의 필요. 광고성 정보는 법적으로 별도 동의다 |
| `INQUIRY_REPLY` | 서버 | 나중 | `LetterThread`/`LetterMessage`. `target_type`에 `LETTER` 추가 필요 |

`COURSE_LIKED`·`INVITE`는 기능이 없다. 자리만 둔다.

## 4. 지운 코스 알림 — 보여주고, 누르면 안내한다

지금은 `findMine`이 `Course`를 조건부 조인해 `deleted_at IS NULL`로 **조회에서 숨긴다.**
이걸 걷어낸다. 저장은 실제로 일어났던 일이고, 알림이 소리 없이 사라지면 유저는 자기가
잘못 봤다고 생각한다.

- 목록에 **그대로 보인다**
- **미읽음 카운트에도 들어간다** — 목록과 배지가 어긋나면 그게 더 나쁘다
- 누르면 앱이 "삭제된 빵투어예요"를 띄운다 → 읽음 처리된다

푸시는 이미 나가버린 뒤이므로, **딥링크로 들어왔을 때도 같은 안내**가 필요하다. FE 작업.

## 5. 발송 파이프라인

```
도메인 이벤트 → notification 1행 + recipient N행 (한 트랜잭션, 커밋)
                                  ↓ 커밋 후 비동기
                        수신 자격 판정 → FCM 발송 → delivery N행
```

**푸시 발송을 알림 적재와 같은 트랜잭션에 넣지 않는다.** FCM 응답을 기다리는 동안 DB
커넥션을 잡고 있게 된다. 커밋 후 이벤트로 넘긴다.

공지는 `INSERT ... SELECT` 한 방이 아니라 두 단계가 된다 — 원본 1행 넣고 id를 받아
우편함을 벌크로 꽂는다.

### 수신 자격

발송 직전에 본다. 하나라도 걸리면 우편함에는 남고 푸시만 안 나간다.

- `NotificationSetting.isNotificationEnabled`
- `AD`는 추가로 `isMarketingEnabled`
- 활성 `fcm_token`이 있는가

**방해금지 시간(`startTime`/`endTime`/`daysOfWeek`)은 v1에서 보지 않는다.** 지키려면 예약
발송이 필요한데 스케줄러가 없다. 붙일 때 이 문단을 지운다.

## 6. 클릭 집계 — 하지 않는다

푸시가 눌렸는지는 서버가 알 방법이 없다. FCM은 보낸 것까지만 알려주고, 앱이 `deliveryId`를
되돌려줘야 채워진다.

**그 API를 만들지 않기로 했다.** 앱이 이미 `analytics().logEvent('open_from_report_result')`로
클릭을 Amplitude에 찍고 있다. 같은 사실을 두 군데 쌓으면 언젠가 두 숫자가 어긋나고, 그때
어느 쪽이 맞는지 아무도 모른다. 오픈율은 Amplitude에서 본다.

발송 결과를 어드민 화면에서 보게 되는 날 `clicked_at` 컬럼과 회신 API를 함께 더하면 된다.

## 7. 배포 순서

1. **DB 스냅샷** — 이게 유일한 되돌릴 자리다. 없으면 배포하지 않는다
2. 마이그레이션 적용
3. 행 수 대조 — `notification_recipient` 총 행 수 == 옛 `notification` 행 수 + `report_notification` 행 수
4. 공지 접힘 확인 — `notification` 의 `NOTICE` 행 수가 실제 발송 횟수와 맞는가

접기는 `(title, body, link, created_at)` 로 묶는다. `insertNoticeForAllMembers` 가 `:now`
파라미터 하나로 전 회원에게 INSERT 하므로 한 번의 발송은 `created_at` 이 전부 같다 — 정확히 묶인다.

## 8. 손대야 하는 것

- **`NotificationRepository` 쿼리 9개 전부** — 조인 구조가 바뀐다
- **`AlarmController`** — 응답 모양은 유지한다. 구버전 앱의 by-type 경로도 산다
- **`FcmService`** — `FirebaseMessaging.getInstance()` static 호출을 주입으로 바꾼다. 지금은 목킹이 안 돼 발송 로직을 테스트할 수 없다
- **테스트 12개** — `NotificationRepositoryTest`, `NotificationServiceTest`, `AlarmControllerTest` 등
- **`ReportNotification` 계열 전부** — 테이블이 사라지므로 엔티티·레포지토리·서비스를 새 구조로 갈아끼운다. `/alarm/report-notifications` 응답 모양은 유지해 앱이 모르게 한다. 호출처가 `AdminReportCommandService`·`AbuseReportCommandService`·`MenuAddReportCommandService`·`InquiryCommandService`·`AdminReportNotificationPublisher` 다섯 군데다

## 9. 아직 안 정한 것

- **계측을 어디에 쌓을지** — BE는 PostHog로 `push_sent`를 쏘는데 FE는 Amplitude로 갔다(`d1d0caa`). 한쪽으로 모아야 한다
- **`delivery` 보관 기간** — 공지 1건이 회원 수만큼 행을 만든다. 90일 후 삭제할지, 둘지
