> **원본은 FE 레포 `daedongbbangmap/docs/course-collab-phases.md`** (2026-09-03 저녁 스냅샷 — version·ETag 304·순서 CAS 반영본). 정책·ERD가 바뀌면 원본을 먼저 고치고 여기를 따라 맞춘다.

# 빵투어 동시편집 V1 — 실행 계획 (phase별)

작성: 2026-08-20
성격: **총괄 문서.** 정책·ERD·순서·범위처럼 FE와 BE가 **같이** 봐야 하는 것만 둔다. phase별 "정확히 어떻게"는 두 문서로 나눴다(2026-09-03).

| 문서 | 담당 | 내용 |
|---|---|---|
| `docs/be-tasks-course-collab.md` | BE | baseline · Phase 2·3·4·5·5-B — 마이그레이션 SQL, 엔티티·리포지토리, **API 계약(요청·응답 JSON·에러 코드)**, 테스트, 검증 |
| `docs/course-collab-fe-phases.md` | FE + 랜딩 | Phase 0·1·6·7·5-B(FE)·8 — 화면별 상태, `isMine` 분류, 폴링 훅, 충돌 6경로, 계측, 검증 |

**규칙**: 정책이 바뀌면 여기를 먼저 고치고 두 문서를 따라 고친다. API 모양은 BE 문서가 기준, FE 문서는 그걸 참조한다.

---

## 진행 현황 (2026-09-03 갱신)

| | 상태 |
|---|---|
| Phase 0 웹 랜딩 | 코드 **커밋·push 완료**(policy `feat/course-invite-landing`), **배포 전**(prod `/c/:id` 404 확인, 9/3). 패키지명 오타는 고침 |
| Phase 1~8 | **착수 전.** FE·BE 어느 브랜치에도 협업 코드 없음 (9/3 실측) |
| 계획서 점검 | ✅ **2026-09-03 완료.** 결정 17개 추가·예외 8개 보강 — 문서 끝 「2026-09-03 점검 결과」 참고. **ERD 검토 완료(9/3)** |
| 문서 분리 | ✅ **2026-09-03.** phase 상세를 BE 지시서·FE 작업서로 이동. 이 문서는 총괄만 |
| 방문 체크(`docs/course-visit-record-link.md`) | ✅ **완료·배포됨.** Phase 4의 전제가 바뀌었다 — 아래 참고 |
| 푸시 알림 | **범위 밖.** 네이티브 모듈이 없어 OTA 불가, 다른 담당자가 별건으로 |

**착수 순서**: Phase 0(배포) → Phase 1(FE 목업) → Phase 2~5·5-B(BE) → Phase 6~7(FE) → Phase 8.
Phase 2 시작 전에 `CourseCommandServiceTest`의 낡은 테스트 3개를 먼저 초록으로 만든다(아래 baseline 참고).

⚠️ **DB 스키마는 문서 끝 「ERD」 섹션이 기준이다(2026-09-03 담당자 검토 완료).** 거기서 벗어나는 테이블·컬럼·키 변경은 만들기 전에 다시 검토받는다. 마이그레이션은 Phase 2 착수 시 그 표 그대로 작성한다.

---

## 전제

- 목표: 빵투어에 친구를 초대해 **같이 편집**한다. 상대 변경은 **8초 폴링**으로 받아온다.
- **웹소켓 안 쓴다.** blue/green 배포가 연결을 끊고, 편집 API가 이미 아이템 단위로 쪼개져 있어 "받아오는 쪽"만 풀면 된다. V2에서 폴링을 웹소켓 초인종으로 바꾸며 이 구조를 그대로 재사용한다.
- 기존 fake-door `InviteCompanionSheet`(코스 상세 "일행 초대")의 본편이다.

### 작업 순서 — 왜 이 순서인가

**웹 랜딩 → FE 목업 → BE → FE 연동 → 동기화** (단독 작업, 순차 진행)

- **웹 랜딩이 0번**: 독립적이고, 실패하면 나머지가 전부 무의미해진다(카톡에서 링크가 안 열리면 초대 기능 자체가 없다). 반나절이면 검증되는 최대 리스크
- **FE가 BE보다 먼저**: 화면이 스키마를 좌우한다(사람별 메모 표시 방식 → 메모 테이블). 이 레포의 확립된 방식이기도 하다 — `docs/bbanglog-phases.md` 참고
- **BE는 한 번에**: 화면이 확정된 뒤 스키마를 못 박아 양쪽을 두 번 고치지 않는다

### 작업 규칙 (BE는 특히 엄격히)

**BE — `bbang-ggut_BE` (조직 `SWEETT-GIRL`)**

- **브랜치 흐름**: `feat/* → dev → main`. **main 직행 PR 금지.** phase마다 브랜치를 새로 판다
- **브랜치 이름**: 영문 kebab-case, 타입 접두사 — `feat/`, `fix/`, `chore/`, `refactor/`, `docs/`
- **커밋 메시지**: `<gitmoji> <type>: <한국어 평서문>` — 기존 스타일을 그대로 따른다
  - 예시(실제 커밋): `✨ feat: 앱 버전 정책 API — 어드민이 숫자만 바꾸면 앱이 스스로 막히거나 안내한다`
  - `🐛 fix: 네이버 place id 없는 가게 등록에서 500이 나지 않게 한다`
  - 쓰는 이모지: ✨ feat · 🐛 fix · ♻️ refactor · 📝 docs · 🔧 chore · 🔥 chore(삭제) · 💚 ci/cd · ✅ test
  - **"~한다/~하게 한다" 평서문.** 무엇을 왜 바꿨는지가 한 줄에 보이게 쓴다
- **PR**: `.github/pull_request_template.md` 3섹션을 반드시 채운다 — `💡 어떤 작업을 하셨나요?`(체크리스트) / `📝 관련 이슈`(`Resolved: #번호`) / `🔍 리뷰어에게`(고민한 지점)
- **이슈**: `.github/ISSUE_TEMPLATE/`에 6종(✨feature 🐛fix ♻️refactor 📝docs 💚ci-cd ✅test). PR에 이슈 번호를 달아야 하므로 phase마다 이슈 먼저
- **Jira 연동됨** (`jira-automation.yml`, `jira-issue-update.yml`) — 이슈/PR이 자동 동기화되니 제목을 성의 있게
- **CI**: `ci.yml`이 돈다. PR 전에 로컬에서 `./gradlew test` 통과 확인

**FE — `daedongbbangmap`**

- 커밋 스타일 동일(gitmoji + 한국어 평서문). `💄 style`도 씀
- PR 템플릿 없음. **2026-08-25부터 FE에도 `dev` 브랜치가 생겼다 — `feat/* → dev → main`, main 직행 금지.** 이번 건은 `feat/course-collab-ui` 브랜치로 모아 dev로 보낸다
- **OTA 브랜치 규칙**: 이 문서 범위(협업 UI·초대·폴링)는 네이티브 모듈 추가가 없어 **OTA 가능**. OTA 발행은 `ota/<버전>-<슬러그>` 브랜치에서 (선례: `ota/1.3.1-full-address`)
- ⚠️ **단, 푸시 알림은 OTA로 못 간다** — 실측 결과 이 앱에는 `@react-native-firebase/messaging`도 `expo-notifications`도 없고, 알림 탭 라우팅 핸들러도 FCM 토큰 등록 코드도 없다(`FcmTokenRequest` 타입만 있고 호출부 0건). 받으려면 네이티브 모듈 추가 → **스토어 빌드 필요**.
  **→ 푸시는 이 문서 범위에서 제외한다. 다른 담당자가 별건으로 진행.** Phase 2의 알림 항목은 "BE만 준비" 수준으로 남긴다

**공통**

- **push·PR 생성은 사용자 지시가 있을 때만.** 브랜치 생성·커밋까지는 진행하고, 원격에 올리는 건 확인받는다
- phase 하나 = 브랜치 하나 = PR 하나를 기본으로 한다

### phase ↔ 브랜치 매핑 (제안)

| Phase | 레포 | 브랜치 |
|---|---|---|
| 0 웹 랜딩 | policy | `feat/course-invite-landing` |
| 1 FE 목업 | FE | `feat/course-collab-ui` |
| 2 멤버·초대 | BE | `feat/course-collab-members` |
| 3 메모 사람별 | BE | `feat/course-store-memos` |
| 4 부수 정리 | BE | `fix/course-collab-side-effects` |
| 5 응답 확장 | BE | `feat/course-collab-response` |
| 5-B 링크 공개 | BE + FE | `feat/course-visibility` (확정 · 순서는 6~7 뒤여도 됨) |
| 6~7 FE 연동·동기화 | FE | `feat/course-collab-ui` 이어서 |

### 레포 3개

| 역할 | 경로 |
|---|---|
| FE (Expo/RN) | `/Users/danalim/Desktop/daedongbbangmap` |
| BE (Spring Boot) | `/Users/danalim/bbanggut/bbang-ggut_BE` — 브랜치 규칙 `feature → dev → main` (main 직행 PR 금지) |
| 웹 랜딩 (Vercel) | `/Users/danalim/bbanggut/bbang-ggut_policy` |

### 확정 정책 (다시 묻지 말 것)

| 항목 | 결정 |
|---|---|
| 멤버 상한 | 방장 포함 8명 |
| 초대 링크 생성 | 방장 + 멤버 누구나 (로그인 필요) |
| 링크 유효기간 | 7일, 코스당 1개 재사용, 만료 시 재생성 |
| 게스트 참여 | **불가 — 로그인 필수** |
| 참여 시 이름 | 수락 화면에서 "이 빵투어에서 쓸 이름" 입력 (코스 안에서만 쓰는 이름). **최대 10자, 공백만은 거부.** 기본값은 닉네임 |
| 역할 | 멤버 = 전원 편집자 (뷰어/에디터 구분 없음) |
| 방장 전용 | 코스 삭제, 공개/비공개 전환, 커버 이미지 |
| 협업 코스 공개 | **PUBLIC 불가.** 공개 코스에 초대 시 **PRIVATE으로 자동 전환** + 안내. **전환 시점은 첫 수락 시(BE)** — 링크만 만들고 아무도 안 오면 공개 유지, FE는 링크 만들 때 안내만(2026-09-03 결정). 찜한 사람·링크 가진 사람이 못 보게 되는 건 **의도된 동작**이다 |
| 협업 코스를 다시 공유하려면 | 방장이 **명시적으로 UNLISTED로 올린다.** 자동으로 올려주지 않는다 |
| 코스 공개 범위 | 3단계. Phase 5-B |
| ↳ PRIVATE | 목록 ✗ · 링크 ✗ · 찜 ✗ — **진짜 나만**(+협업 멤버). 공유 자체가 안 된다 |
| ↳ UNLISTED | 목록 ✗ · 링크 ✓ · **찜 ✗** — 공유용이지 보관용이 아니다 |
| ↳ PUBLIC | 목록 ✓ · 링크 ✓ · 찜 ✓ |
| 찜 | **PUBLIC 전용**(현행 유지). UNLISTED로 내려가면 찜 목록에서 빠진다 — 찜 row는 남아 PUBLIC 복귀 시 되살아난다 |
| UNLISTED 포크 | **허용.** 링크로 본 코스를 챙겨두는 유일한 길이다 |
| UNLISTED 따라가기 | **불가.** 진행률·완주가 원본에 매달리는데, 방장이 PRIVATE으로 내리면 깨진다 |
| UNLISTED 찜 | **불가.** 같은 이유 + 찜 개수가 공개 목록 랭킹(`CourseRepository:28`)에 쓰여 숨은 코스에서 쌓이면 안 된다 |
| 포크 동일 판정에서 메모 | **뺀다.** 가게·순서·일차·방문시간 4개로만 판정 |
| 일차 수 | **늘어나기만 한다.** 줄이는 경로를 만들지 않는다(현행 `Math.max`와 같은 동작) |
| 메모 길이 | **200자.** 방문계획 메모와 같은 제한 |
| 메모 카드 레이아웃 | 내 메모(편집) + 일행 2명, 나머지는 `+N명 더보기`로 접는다 |
| 메모 정렬 | **내 메모가 맨 위**, 일행끼리는 **참여 순서 고정**(폴링·드래그 중 흔들리지 않게) |
| 메모 없는 일행 | **표시하지 않는다.** 쓴 사람만 나온다 |
| 방장 전용 UI | 멤버에게 **아예 숨긴다.** 비활성+안내를 쓰지 않는다 — 지금 코드가 이미 `isMine ? ... : null` 구조라 그대로 두면 되고, 안내 문구를 새로 쓸 일도 없다 |
| 따라가기 | **방장·멤버 모두 가능.** 내가 만드는 코스를 동시에 따라가는 건 자연스럽다 |
| 멤버 나가기 위치 | **더보기(⋯)** — 방장의 '삭제'가 있던 자리. 둘 다 되돌리기 어려운 행동이라 위치·빨간색 처리를 그대로 쓴다 |
| 멤버에게 찜 버튼 | **숨긴다.** 참여 시 찜이 자동 해제되는데 옆에 찜 버튼이 있으면 모순이고, '내 빵투어'에 이미 '함께' 배지로 뜬다 |
| 링크 공개 URL | **`share_token` 필수.** 코스 id가 순차 증가라 `/c/{id}`로는 "링크 아는 사람만"이 성립하지 않는다 |
| share_token 생성 | SecureRandom 32B → base64url. **초대 토큰과 같은 유틸을 쓰되 컬럼은 분리** |
| share_token 발급 시점 | UNLISTED로 **처음 바꿀 때**. 전 코스 선발급 금지 |
| share_token 수명 | 만료 없음. PUBLIC·PRIVATE로 돌아가도 **보존**(왕복 시 기존 공유 링크가 살아 있어야) |
| PRIVATE + 토큰 | **토큰이 있어도 403.** PRIVATE은 토큰보다 강하다 |
| PUBLIC 공유 URL | `/c/{id}` 유지 — 이미 나간 링크를 깨뜨리지 않는다. UNLISTED만 `/c/{shareToken}` |
| share_token 재발급 | V1 없음 (유출 대응 UI는 범위 밖) |
| 메모 | 가게마다 **사람별**. 방문시간은 공통 1개 |
| 포크 시 메모 | 복사 안 함 (가게·순서·일차만) |
| 나간 멤버 | 메모는 삭제, 담았던 가게는 코스에 남김. **회원 탈퇴한 멤버·방장도 같은 규칙**(메모 삭제, 가게 유지 — 2026-09-03 결정) |
| 방장 탈퇴 | 가장 오래된 멤버에게 자동 승계 |
| 멤버의 포크·따라가기 | 둘 다 허용 |
| 찜 + 참여 | 참여 시 찜 자동 해제 |
| 내 빵투어 목록 | 참여 코스는 '함께' 배지. 소유·참여를 **최근 수정순으로 섞어서** 정렬(2026-09-03 결정) |
| 코스 개수 뱃지 | 내가 만든 코스만 (현행 유지) |
| 코스 내 이름 | 나중에 변경 가능 (참여자 목록에서 내 이름 탭). **방장도 코스 내 이름을 갖는다**(2026-09-03 결정) — 방장의 `course_members` 행은 **첫 초대 링크 생성 시** 만들고 기본값은 닉네임. 소유자 판정은 여전히 `courses.member_id` |
| 초대 링크 재생성 | **이전 링크 무효화** — 코스당 살아있는 링크는 항상 1개. **남은 유효기간이 1일 미만이면 재사용하지 않고 재발급**(보내는 링크는 항상 하루 이상 산다, 2026-09-03 결정) |
| 방장 나가기 | 승계하고 나가기 ("다음 사람에게 넘기고 나가기") |
| 밴 계정 | 초대 **수락·생성 모두 차단**(2026-09-03 결정. 협업 메모 = 남에게 보이는 글). `validateCanWrite` 재사용 |
| 빈 일차 | **서버에 저장** (일행이 같은 일차 수를 본다) |
| 폴링 주기 | 8초 |
| 알림 | **V1 없음**(2026-09-03 결정). 합류·탈퇴는 8초 폴링으로 참여자 목록이 갱신되는 것으로, 코스 삭제는 멤버의 폴링이 404를 받아 안내하는 것으로 갈음. 푸시·인앱 알림 모두 V2 |
| 게스트의 공개 코스 초대 | 로그인 필요 + 비공개 전환을 **한 화면에 함께 안내** |
| 커버 동시성 | 방장 전용으로 좁혀서 회피 (유니크 제약·락 작업 생략) |
| 프로필 이미지 | 서버에 개념 없음 → 이름표는 텍스트/이니셜만 |
| 멤버 튜토리얼 | 코스 상세의 편집 튜토리얼·코치마크(1.4.0 온보딩 개편으로 추가)는 **방장만**. `isMine` 조건 유지(2026-09-03 결정) |
| 미설치자 초대 | 스토어 설치 후 **링크 재탭 감수**(V1). 랜딩에 "설치 후 이 링크를 다시 눌러주세요" 문구. Airbridge deferred 딥링크는 V2(2026-09-03 결정) |
| Phase 5-B 순서 | **Phase 5 직후.** BE를 한 번에 끝낸다(2026-09-03 결정) |
| DB 스키마 | **ERD 검토·승인 후에만 마이그레이션 작성.** 문서에 적혀 있어도 승인이 아니다(2026-09-03 지시). 현재 ERD는 9/3 검토 완료 |
| 폴링 비용 | `courses.version`(쓰기마다 +1) + 상세 GET에 **ETag = version, `If-None-Match` 같으면 304**. 폴링은 헤더 하나만 더 보내고 304면 아무것도 안 한다(2026-09-03 결정) |
| 순서 충돌 | 순서 저장 요청에 **`expectedOrder`(내가 본 정류장 id 순서)** 동봉 → 서버가 락 안에서 현재 순서와 비교, 다르면 **409 `COURSE_STALE`** → FE 재조회 + 안내. version 비교가 아니라 순서 CAS인 이유: 친구가 메모만 저장해도 내 드래그가 거부되는 가짜 충돌을 피한다(2026-09-03 결정) |
| 메모와 가게 삭제 | 메모는 **정류장 행(`course_stores.id`)에 묶인다.** 가게를 코스에서 빼면 그 가게의 메모는 **전원 것이 함께 삭제**되고, 다시 담아도 돌아오지 않는다(2026-09-03 결정). 방문 체크가 "남기되 숨김"인 것과 **다르다** |

---

## Phase 지도 — 상세는 두 문서로

| Phase | 담당 | 브랜치 | 한 줄 | 상세 |
|---|---|---|---|---|
| 0 웹 랜딩 | policy + FE 한 줄 | `feat/course-invite-landing` | `/c/:id` 배포·실기기 카톡 검증·신규 설치 경로·공유 문자열 교체 | FE 문서 Phase 0 |
| 1 UI 목업 | FE | `feat/course-collab-ui` | 초대 시트·수락 화면·메모 카드·참여자 칩·'함께' 배지, 계측 7종 | FE 문서 Phase 1 |
| baseline | BE | dev | `CourseCommandServiceTest` 낡은 3개 초록으로 | BE 문서 baseline |
| 2 멤버·초대 | BE | `feat/course-collab-members` | 테이블 3개 + `courses` 4컬럼(마이그레이션 하나, `version` 포함), `validateCanEdit`, 락 5곳, 순서 CAS, API 5개 | BE 문서 Phase 2 |
| 3 메모 | BE | `feat/course-store-memos` | `course_store_memos`, 이관, `memo`=`myMemo` 호환, 가게 빼면 메모 삭제 | BE 문서 Phase 3 |
| 4 부수 정리 | BE | `fix/course-collab-side-effects` | 완주 부당 승격 차단, 멤버 포크·따라가기, 탈퇴 승계·정리 | BE 문서 Phase 4 |
| 5 응답 확장 | BE | `feat/course-collab-response` | `canEdit`·`members`·`isMember`·`version`, 상세 GET ETag/304, 내 목록 union·dedup | BE 문서 Phase 5 |
| 5-B 링크 공개 | BE → FE·랜딩 | `feat/course-visibility` / FE는 이어서 | `visibility` 3단계·`share_token`·`by-share`, 공유 시트 전환, noindex | BE 문서 5-B · FE 문서 5-B |
| 6 서버 연동 | FE | `feat/course-collab-ui` | API 함수 5개, `isMine` 38곳 분류, 화면 연결 | FE 문서 Phase 6 |
| 7 동기화 | FE | 이어서 | 8초 폴링(If-None-Match/304), 실패 규칙, 충돌 6경로(`expectedOrder`), 무음 실패 4곳, 빈 일차 | FE 문서 Phase 7 |
| 8 통합 검증 | 기기 2대 | | 13항목 | FE 문서 Phase 8 |

**착수 순서**: 0 → 1 → baseline → 2 → 3 → 4 → 5 → 5-B → 6 → 7 → 5-B(FE) → 8. FE 브랜치는 **main에서** 판다(dev가 main보다 뒤 — FE 문서 0절).

### 계획 문서 주장 대조 결과 (2026-08-20 실측, 기록용)

문서가 근거 없이 단언하던 항목을 코드로 확인했다. 7건 중 6건은 정확했고 1건이 크게 달랐다.

| 주장 | 결과 |
|---|---|
| `MemberRestrictionReader.validateCanWrite` 재사용 가능 | ✅ 존재. 4곳에서 사용 중 |
| `MemberReader.getMember`가 탈퇴자에 예외를 던진다 | ✅ `deletedAt != null` → `MEMBER_DELETED` |
| `useLoginGate()`가 root에 마운트돼 있다 | ✅ `app/_layout.tsx` `LoginGateProvider` |
| `SaveToCourseSheet`에 이식할 중복 처리 패턴이 있다 | ✅ `COURSE400_1`을 실패로 안 세는 코드(`:187~208`) |
| `CourseProgress` 복합키 선례를 따르면 된다 | ✅ 엔티티 2개 + 마이그레이션 존재 |
| `InviteCompanionSheet`가 fake-door다 | ✅ "곧 나와요" 배지 + `PREVIEW` 배열 |
| **`isMine`을 `canEdit`로 치환하면 된다** | ❌ **38곳이다**(9/3). 한 줄 작업이 아니다 — FE 문서 6-2 |

---

## 절대 하지 말 것 (V1 범위 밖)

웹소켓/SSE/실시간 인프라 · Redis · presence(접속 중 표시) · OT/CRDT·필드 병합 · **모든 편집에 낙관적 락 충돌 UI를 띄우는 것(@Version 전면 도입)** · 뷰어/에디터 역할 · 초대 승인·거절 절차 · 강퇴 · 토큰 revoke UI · 가게별 공통 메모 · 메모 답글/이모지 · 편집 변경 푸시(알림 자체가 V1 밖) · 편집 히스토리/undo · `app/course/[id].tsx` 리팩터·파일 분할 · react-query 전면 이전 · 닉네임 변경 기능(코스 내 이름으로 대체) · Universal Link/App Link 등록(스킴 폴백 랜딩으로 충분, 2차)

> **@Version 전면 도입을 왜 안 하나** — 재논의 방지용 근거.
> ① 대부분의 쓰기는 stale 데이터를 보내지 않는다. 가게 추가는 서버가 현재 DB 기준으로 맨 뒤에 붙이고, 삭제는 `storeId` 지정이라 멱등에 가깝다.
> ② 단일 값(제목·날짜·방문시간)에 "다른 사람이 먼저 바꿨어요, 다시 시도하세요"를 띄우면 오히려 UX가 나빠진다. 후저장 승리로 둔다. 메모는 사람별로 쪼개 충돌 자체를 없앴다.
> ③ 유일하게 "내 화면 전체"를 보내는 순서 변경은 **집합 검증 + `expectedOrder` CAS**로 정확히 그 경우만 잡는다(2026-09-03 추가). version 비교로 하면 무관한 쓰기(메모 저장)에도 거부되는 가짜 충돌이 생긴다.
>
> **ETag는 쓴다** — 단, 충돌 제어가 아니라 **폴링을 싸게 만드는 용도**(304)다. 예전 문구 "낙관적 락/ETag 금지"는 충돌 UI를 말한 것이었고, 조건부 GET은 그와 무관하다(2026-09-03 정정).
>
> 필요한 건 **비관적 락 1개(500 방지) + 순서 CAS + 충돌 안내 6경로 + 304 폴링**이고, 전부 Phase 2·5·7에 들어 있다.

## V2로 넘어가는 신호 (참고)

| 신호 | 대응 |
|---|---|
| "친구가 바꾼 게 늦게 보인다" 반복 | 폴링 주기 단축 → 웹소켓 검토 |
| 동시 세션 수백 개로 폴링이 서버 지표에 잡힘 | 웹소켓 전환. 서버 1대라 외부 브로커 불필요, nginx 업그레이드 헤더 + 배포 시 재연결만 해결하면 됨 |
| **"정렬한 게 자꾸 되돌아간다"** — 두 사람이 동시에 *순서만* 재배치하면 개수가 같아 검증을 통과하고 나중 저장이 이긴다 (V1이 감수하는 유일한 lost update) | 순서 저장에만 낙관적 락(버전 비교) 도입 → "일행이 먼저 옮겼어요" 안내 |
| "지금 누가 보고 있는지" 요구 | presence — 웹소켓과 함께 |
| "일행 전체 메모 칸이 없다" | 가게별 공통 메모 1칸 |

**V2 원칙**: 웹소켓은 "변경됐음" 신호를 보내는 **초인종으로만** 쓰고, 데이터는 V1의 재조회를 재사용한다. 패치 프로토콜·순서 보장·유실 복구를 만들지 않는다.

---

## 2026-09-03 점검 결과 — 착수 전 결정 19개와 보강 8개

8/20 이후 온보딩 1.4.0·일차 자동 나누기·FE `dev` 브랜치 신설로 계획서와 코드가 어긋난 곳을 다시 쟀고(위 phase별로 반영), 계획서가 안 다루던 예외를 담당자와 결정했다.

### 결정 (담당자 확답, 2026-09-03)

| # | 항목 | 결정 |
|---|---|---|
| 1 | 공개 코스 → 비공개 전환 시점 | **첫 수락 시(BE).** FE는 링크 만들 때 안내만 |
| 2 | 방장의 코스 내 이름 | **방장도 갖는다.** 행은 첫 초대 링크 생성 시, 기본값 닉네임 |
| 3 | 합류·탈퇴·삭제 알림 | **V1 없음.** 푸시·인앱 모두 V2 (같은 날 저녁에 인앱에서 제외로 변경) |
| 4 | 편집 튜토리얼·코치마크 | **방장만** (`isMine` 유지) |
| 5 | 탈퇴한 방장의 메모 | **삭제** (나간 멤버와 동일) |
| 6 | Phase 5-B 순서 | **Phase 5 직후** |
| 7 | 만료 임박 초대 토큰 | **남은 기간 1일 미만이면 재발급** |
| 8 | 미설치자 초대 | **설치 후 재탭 감수.** 랜딩에 안내 문구. Airbridge deferred는 V2 |
| 9 | 코스 내 이름 길이 | **10자**, 공백만 거부 |
| 10 | 밴 계정 | **초대 생성·수락 모두 차단** |
| 11 | 내 빵투어 정렬 | **소유·참여 섞어 최근 수정순** |
| 12 | DB 스키마 | **ERD 검토·승인 후에만 마이그레이션 작성** |
| 13 | 메모 키 (ERD 1) | **정류장 행 `(course_store_id, member_id)`.** 가게 빼면 메모 전원 삭제, 재추가해도 안 돌아옴 |
| 14 | 알림 테이블 (ERD 2) | **변경 없음.** 알림 자체를 V1에서 뺐다 |
| 15 | 초대 무효화 (ERD 3) | **soft delete(`deleted_at`).** 만료는 `expires_at`, 하드 삭제 없음 |
| 16 | 이름 10자 (ERD 4) | **DB `VARCHAR(10)`으로도 막는다** + DTO `@Size` |
| 17 | `courses` 3컬럼 (ERD 5) | **마이그레이션 하나**로, Phase 2에서 |
| 18 | 폴링 비용 | **`courses.version` + 상세 GET ETag/304.** 낙관적 락 충돌 UI와는 별개 (9/3 저녁) |
| 19 | 순서 lost update | **`expectedOrder` CAS → 409.** version이 아니라 순서 자체를 비교해 가짜 충돌 없음 (9/3 저녁) |

### 보강 (계획서에 없던 예외, 각 phase에 반영함)

| 항목 | 반영 위치 |
|---|---|
| 게스트 이관에 새 테이블 3개 `moveOwner` | 2-1 · Phase 3 |
| 수락·초대 생성도 비관적 락 (정원 8명·활성 링크 1개의 경합) | 2-2 |
| 응답 `memo` 필드 유지 (구버전 앱 호환) | Phase 3 |
| 폴링 실패는 조용히, 404·403 연속이면 안내 후 뒤로 | 7-1 |
| 방장 아닌 멤버의 회원 탈퇴 처리, 탈퇴 방장 정리 | Phase 4 |
| 순서 충돌 경로 5 → 6 (`applyAutoSplit`) | 7-2 |
| 신규 설치 콜드스타트 딥링크가 온보딩을 건너뛰는 경로 검증 | Phase 0 · Phase 8 |
| Phase 8 알림 검증 제거(알림 V1 제외), 메모 삭제 검증 추가 | Phase 8 |

### 실측 갱신 (2026-09-03)

| 항목 | 8/20 | 9/3 |
|---|---|---|
| `app/course/[id].tsx` | 3966줄 | 4305줄 |
| `isMine` (그 파일) | 34곳 | 38곳 |
| `reorderCourseStores` 호출 | 5곳 | 6곳 |
| `AppState` 선례 | 0건 | 3파일 |
| `AppToast` (그 파일) | 14곳 | 16곳 |
| BE `validateOwner` 호출 | 9곳 | 9곳 (변화 없음) |
| BE `dev` | | 마지막 커밋 8/21, `main`과 동일 |
| policy 프로덕션 배포 | | 8/18이 마지막 — 랜딩 브랜치(8/20)는 미배포 |
| 계획서 | 1파일 | 3파일(총괄 · BE 지시서 · FE 작업서) |

---

## ERD — ✅ 검토 완료 (2026-09-03, 검토 포인트 5개 답 반영)

담당자가 검토 포인트 5개에 답해 확정된 스키마다. **Phase 2 착수 시 이 표 그대로 Flyway 파일을 만든다.** 여기서 벗어나는 변경(테이블·컬럼·키·인덱스)은 만들기 전에 다시 검토받는다.

기존 선례를 그대로 따랐다: 복합 PK·부분 인덱스는 `course_progress`·`course_store_visits`, 알림은 `course_save_notification`, 타임스탬프(`created_at NOT NULL`, `updated_at`, `deleted_at`)는 최신 테이블과 동일. DB는 PostgreSQL.

### 새 테이블 3개

**1. `course_members` — 코스의 편집자 (방장 포함)**

| 컬럼 | 타입 | 제약 | 뜻 |
|---|---|---|---|
| course_id | BIGINT | PK(1), FK courses | |
| member_id | BIGINT | PK(2), FK member | |
| display_name | VARCHAR(10) | NOT NULL | 코스 안에서 쓰는 이름. 기본값 닉네임. **DB 길이 제약으로도 막는다**(확정) + DTO `@Size(max = 10)`·공백만 거부 |
| joined_at | TIMESTAMP | NOT NULL | 참여 순서(메모 정렬·승계 순서의 기준) |
| created_at / updated_at / deleted_at | TIMESTAMP | created NOT NULL | deleted_at = 나가기·탈퇴 |

- 인덱스: `(member_id) WHERE deleted_at IS NULL` — "내가 참여한 코스" 목록
- 없는 것: role(전원 편집자), 초대 상태(수락 = insert). 방장 여부는 `courses.member_id`로만 판정
- 방장 행은 첫 초대 링크 생성 시 생긴다. 협업이 없는 코스엔 행이 없다 → **기존 코스 이관 없음**
- 나갔다 다시 들어오면 같은 PK라 `deleted_at`을 비우고 `joined_at`·`display_name`을 갱신한다(새 행 아님)

**2. `course_invites` — 초대 링크**

| 컬럼 | 타입 | 제약 | 뜻 |
|---|---|---|---|
| id | BIGSERIAL | PK | |
| course_id | BIGINT | FK courses, NOT NULL | |
| token | VARCHAR(64) | UNIQUE NOT NULL | SecureRandom 32B → base64url(43자) |
| created_by | BIGINT | FK member, NOT NULL | 만든 사람(방장 또는 멤버). 게스트 이관 대상 |
| expires_at | TIMESTAMP | NOT NULL | 생성 + 7일 |
| deleted_at | TIMESTAMP | | 재생성으로 무효화 = soft delete(확정, 다른 테이블과 같은 관례) |
| created_at | TIMESTAMP | NOT NULL | |

- 인덱스: `(course_id) WHERE deleted_at IS NULL` — 활성 링크 조회. "코스당 활성 1개"는 DB 제약이 아니라 **락 안의 서비스 로직**으로 보장한다(`expires_at > now()`는 부분 인덱스 조건에 쓸 수 없다)
- 하드 삭제 없음. 만료는 `expires_at < now()`로 판정하고 행은 그대로, 무효화는 `deleted_at`. 수가 문제 되면 V2에서 정리

**3. `course_store_memos` — 가게 × 사람 메모**

| 컬럼 | 타입 | 제약 | 뜻 |
|---|---|---|---|
| course_store_id | BIGINT | PK(1), FK course_stores | |
| member_id | BIGINT | PK(2), FK member | |
| content | VARCHAR(200) | NOT NULL | |
| created_at / updated_at / deleted_at | TIMESTAMP | created NOT NULL | deleted_at = 나가기·탈퇴 시 삭제 |

- 키는 `course_stores.id`(정류장)로 **확정**. 메모는 "그 정류장"에 붙고, 가게를 빼면 `removeStore`가 정류장 행과 함께 메모를 전원 soft delete 한다. 다시 담으면 새 정류장 행이 생기므로 메모는 돌아오지 않는다. `course_store_visits`가 `(course_id, store_id)` 자연키로 "남기되 숨김"인 것과 **의도적으로 다르다**
- 이관: `course_stores.memo`가 비어 있지 않고 코스가 `CUSTOM`·`FORKED`이며 `courses.member_id IS NOT NULL`인 행을 `(course_store_id, courses.member_id, memo)`로 복사. **CURATED는 복사하지 않고 `course_stores.memo`를 그대로 쓴다**(어드민 CLI 경로). 컬럼은 지우지 않는다
- 게스트 이관 `moveOwner` 대상

### 기존 테이블 변경 — `courses`

| 컬럼 | 타입 | 제약 | phase | 뜻 |
|---|---|---|---|---|
| day_count | INT | NOT NULL DEFAULT 1 | 7-5 | 빈 일차 포함 일차 수. **늘어나기만 한다.** 이관: `GREATEST(1, max(course_stores.day))` |
| visibility | VARCHAR(16) | NOT NULL | 5-B | `PUBLIC` / `UNLISTED` / `PRIVATE`. 이관: `is_public → PUBLIC/PRIVATE` |
| share_token | VARCHAR(64) | UNIQUE, NULL | 5-B | UNLISTED로 처음 바꿀 때 발급. 만료 없음, 왕복해도 보존 |
| version | BIGINT | NOT NULL DEFAULT 0 | 2 | 코스·가게·메모·멤버를 바꾸는 **모든 쓰기에서 +1**(`UPDATE ... SET version = version + 1` — 행 단위 원자적이라 락 없는 경로에서도 유실 없음). 상세 GET의 ETag. **9/3 저녁 승인** |

- 네 컬럼은 **마이그레이션 하나**로 넣는다(확정, `version`은 9/3 저녁 추가 승인). Phase 2의 마이그레이션 세트에 포함 — `day_count`·`visibility`·`share_token` 모두 기본값·NULL 허용이라 미리 있어도 해가 없다
- `is_public`은 **한 릴리즈 동안 남긴다**(구버전 앱이 읽는다). 쓰기는 `visibility`와 동기화, 다음 릴리즈에서 drop
- `courses.member_id`는 그대로 방장. 승계는 이 컬럼 UPDATE

### 건드리지 않는 것

`course_stores.memo`(CURATED가 계속 쓴다) · `course_progress` · `course_store_visits`(이미 `member_id`가 있어 협업에서 멤버별로 분리된다) · `saved_courses`(참여 시 행 삭제만) · `member` · **`alarm` 패키지의 알림 테이블 전부**(`course_save_notification` 포함 — 알림은 V1 범위 밖)

### 검토 결과 (2026-09-03, 담당자 답)

| # | 질문 | 답 |
|---|---|---|
| 1 | `course_store_memos` 키 | **정류장 행 `(course_store_id, member_id)`.** 가게를 빼면 메모 전원 삭제, 다시 담아도 안 돌아옴 |
| 2 | 협업 알림 테이블 | **알림 자체를 V1에서 제외.** `course_save_notification` 등 기존 알림 테이블 변경 없음 |
| 3 | 만료·무효 초대 행 | **soft delete(`deleted_at`).** 하드 삭제 없음 |
| 4 | 이름 10자 | **DB `VARCHAR(10)`으로도 막는다** |
| 5 | `courses` 3컬럼 | **마이그레이션 하나** |
| 6 (추가) | `courses.version` | **승인.** 폴링 ETag용. 같은 마이그레이션에 |

### 참고 — 방문 체크는 가게를 빼도 남는다 (코드로 확인, 2026-09-03)

메모와 대비되는 기존 동작이라 적어둔다. `CourseCommandService.removeStore`는 정류장 행만 `softDelete()`하고 `course_store_visits`는 건드리지 않는다. 상세(`CourseQueryService:74~84`)는 활성 정류장을 돌며 `store_id`로 방문을 찾으므로 뺀 가게의 체크는 안 보이다가 다시 담으면 돌아온다. 진행률 집계(`CourseProgressRepository.findMemberAggregates`)는 활성 `course_stores`와 조인해서 뺀 가게의 방문은 세지 않는다. 즉 **"남기되 숨김"**. 메모는 이와 달리 지운다.
