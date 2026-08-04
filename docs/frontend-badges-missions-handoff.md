# FE 에이전트 전달용 — 뱃지·미션 서버 연동

아래 블록을 FE 저장소에서 작업할 에이전트에게 그대로 전달한다.

---

`docs/be-tasks-badges.md`의 BE Phase 1~4가 구현되었다. 현재 FE의 로컬 파생 계산을 아래 API로 교체해라.

## API 계약

모든 응답은 기존 공통 envelope인 `{ success, code, message, data }` 형식이다. 아래 표기는 `data`만 적었다. 모든 회원 API는 게스트 JWT로도 호출 가능하다.

### 1. 배지 카탈로그 + 획득 상태

`GET /members/me/badges`

```json
[
  {
    "code": "welcome",
    "imageUrl": null,
    "title": "웰컴 빵순이",
    "hint": "시작 미션 3개 완료",
    "achieved": true,
    "achievedAt": "2026-08-04T18:00:00",
    "celebrated": false
  }
]
```

- `active=true` 배지만 `sortOrder`, `id` 순서로 온다.
- 조회 시 서버가 미획득 배지를 lazy 판정하고 처음 충족한 시각을 영구 저장한다.
- 획득은 비가역이다. 이후 찜·기록·코스를 지워도 `achieved`는 다시 false가 되지 않는다.
- `imageUrl`은 어드민이 `imageKey`를 넣은 경우 완성된 CDN URL이며, 초기 시드 3종은 이미지 등록 전까지 `null`일 수 있다. `null`이면 공통 🏅 같은 generic fallback을 써라. 코드별 카탈로그를 다시 하드코딩하지 마라.
- 시드 code는 `welcome`, `first-finish`, `regular`이다. 새 배지는 앱 배포 없이 추가될 수 있으므로 이 세 값만 처리하는 switch를 만들지 마라.

`POST /members/me/badges/{code}/celebrate`

- 응답 data 없음.
- `/badge-earned` 닫기 버튼에서 호출하고 성공 후 닫아라.
- 다음 배지 조회부터 `celebrated=true`다.

### 2. 미션 카탈로그 + 진행률

`GET /members/me/missions`

```json
[
  {
    "code": "starter-save",
    "title": "가고 싶은 빵집 찜하기",
    "deeplink": "daedongbbangmap://search",
    "progress": { "current": 1, "target": 1 },
    "done": true,
    "badgeCode": "welcome"
  }
]
```

- `active=true` 미션만 서버 정렬 순서대로 온다.
- FE는 조건을 재계산하지 말고 `progress.current`, `progress.target`, `done`을 그대로 렌더한다.
- deeplink는 서버 데이터다. 지원하는 링크면 이동하고, 모르는 링크는 구버전 호환을 위해 비활성 표시한다.
- 초기 링크는 `daedongbbangmap://search`, `daedongbbangmap://bbanglog/record`, `daedongbbangmap://my-courses`다.

### 3. 완주 이력(필요한 화면에서만)

`GET /members/me/course-progresses?status=FINISHED`

각 항목은 기존 course progress 응답에 아래 필드가 추가된 형태다.

```json
{
  "id": 1,
  "title": "망원 투어",
  "imageKey": null,
  "thumbnailImageKey": null,
  "storeCount": 3,
  "visitedStoreCount": 3,
  "progressPercent": 100,
  "startedAt": "2026-08-01T12:00:00",
  "lastTouchedAt": "2026-08-04T18:00:00",
  "status": "FINISHED",
  "finishedAt": "2026-08-04T18:00:00"
}
```

- status는 `IN_PROGRESS | FINISHED | STOPPED`다.
- 기존 `GET /courses/progress`는 계속 진행 중 항목만 반환하므로 기존 화면과 호환된다.
- 기록 생성 시 서버가 모든 코스 가게 방문 여부를 판정한다. FE finish 신고 API는 필요 없다.

## FE 변경 지시

1. API 타입과 service/query를 추가한다. 기존 프로젝트의 API client, 인증, query-key, error handling 관례를 따른다.
2. `src/utils/badges.ts`의 `BADGES`, `computeAchieved()` 의존을 제거한다.
3. `src/stores/badgeStore.ts`의 `hasFinishedTour`, `earned`, `celebrated` 로컬 영속 상태와 관련 호출을 모두 제거한다. 참조가 0이면 파일 자체도 삭제한다.
4. MY 탭 뱃지 영역은 `GET /members/me/badges` 결과를 그대로 렌더한다. 잠긴 배지는 이름·hint를 노출하고 이미지에 blur+dim을 적용한다. `imageUrl=null`은 generic fallback을 표시한다.
5. 빵로그 홈의 `starterSteps` 하드코딩을 `GET /members/me/missions` 결과로 교체한다. 진행률 표시는 서버 수치를 사용한다.
6. 이미 존재하는 `app/badge-earned.tsx`를 서버 응답 기반으로 바꾼다. `achieved && !celebrated` 중 카탈로그 순서상 첫 배지를 표시하고, 닫을 때 celebrate mutation을 호출한 뒤 badges query를 invalidate한다.
7. 게스트도 badges/missions API는 호출한다. 다만 기존 UX대로 게스트에게 전체 화면 획득 축하는 띄우지 않는다. 소셜 가입 시 같은 member가 승격되거나 기존 회원으로 데이터가 병합되므로, 로그인 직후 refetch하면 미축하 획득 배지가 이어진다.
8. 새 code가 와도 깨지지 않도록 code 기반 exhaustive switch를 피한다. 모르는 deeplink는 안전하게 비활성화한다.
9. 로딩 중 `0/3`이나 잠금 상태가 순간 노출되지 않도록 기존 `starterReady`와 같은 loading guard를 유지한다.
10. 기존 뱃지 3종, 게스트→로그인, 앱 재시작, celebrate 재호출(idempotent), 알 수 없는 code/deeplink, imageUrl null을 테스트한다.

## 완료 조건

- 앱 로컬 저장소를 지워도 완주/배지 획득/축하 여부가 유지된다.
- 배지·미션 카탈로그와 문구·순서가 FE 상수 없이 서버 응답대로 보인다.
- 게스트 상태에서 만든 찜·코스·기록·배지가 로그인 후 유지된다.
- 새 배지/미션 code를 서버에 추가해도 앱이 크래시하지 않는다.
- 기존 코스 진행 중 목록은 회귀하지 않는다.

---
