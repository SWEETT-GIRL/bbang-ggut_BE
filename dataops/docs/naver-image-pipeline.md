# 네이버 이미지 파이프라인 — 저장 구조 · 도메인별 사용 정책 · 오염 정리

작성: 2026-08-14 (본 앱 코드·운영 DB 실측 기반) · 관련: [field-tracks-plan.md](./field-tracks-plan.md) ④

> **✅ dataops 이미지 백필 완료 (2026-08-14)**: 스냅샷의 ldb-phinf 원본 URL 을
> `POST /admin/images/{domain}/{id}/from-url`(서버가 다운로드→S3→행 생성, 람다 완결)로 공급 —
> **메뉴 사진 65,085장(DONE 99.98%) · 가게 사진 15,401장(사진 0장 가게 12,819→6,313, 6,506곳 해소)**.
> 실패 47건(0.06% — 원본 유실·타임아웃). 워커: `scripts/image_backfill_worker.py` (멱등 — 재크롤 후 재실행
> 시 증분 채움). 잔여 0장 가게 6,313곳은 네이버에 업체 사진이 없는 곳 — 화이트리스트 정책상 의도된 잔여.

## 1. 이미지 저장 구조 (본 앱)

```
[수집]  Python 크롤러 (scripts/naver_menu_scraper.py, Playwright)
          └─ 플레이스 사진 탭 ?filterType=업체 진입 → img[src*=pstatic] 수집 → canonical dedup → 최대 5장
[전달]  백엔드 저장 API (source_url 멱등 — 같은 원본 URL 재수집 시 중복 저장 안 함)
[저장]  ImageDownloader → S3 원본 업로드
          └─ S3 트리거: 검증 + 리사이징 → resized_key_320 / 640 / 1024
[기록]  store_image_file (aws_key, source_url, image_status, processing_status, display_order …)
```

- `source_url` 이 **출처 추적의 핵심** — 어느 네이버 도메인에서 왔는지로 사진 성격을 사후 판별할 수 있다 (§3).
- 트렌드 썸네일·상세 노출은 DB(store_image_file/menu_image_file) 경유 — **검색 재색인과 무관**.

## 2. 크롤 트리거 — 두 경로가 있다 (같은 크롤러, 같은 큐)

| 트리거 | 실행 위치 | 모드 | 큐 |
|---|---|---|---|
| **어드민 [일괄 매칭 시작] 버튼** | 백엔드 서버가 Python 크롤러를 subprocess(--loop)로 실행 (`NaverEnrichOrchestrator`) | 풀 enrich (매칭+메뉴+사진) | `naver-enrich-queue` 등 |
| **GitHub Action** `naver-photos-backfill.yml` | Actions 러너 (매일 04:00 KST + 수동 dispatch) | `--photos-only` (사진 보충) | `naver-photos-backfill-queue` (매칭됐지만 사진 0장) |

- 큐 3종: `naver-enrich-queue`(미매칭) / `naver-photos-backfill-queue`(사진 0장) / `naver-enrich-backfill-queue`(메뉴 또는 사진 결손).
- 재시도 규약: 실패 마킹 시 7일 윈도우, 후보 0건 누적 시 영구 파킹.
- ⚠ 어드민 버튼과 Action 이 **겹쳐 돌 수 있다** — Action 쪽은 concurrency 가드가 있지만 어드민 subprocess 와의 상호 배제는 없음 (같은 가게 이중 처리 위험, source_url 멱등이 최후 방어).

## 3. 네이버 이미지 도메인 분류 — 우리가 쓸 수 있는 것 (2026-08-14 운영 실측 99,556장)

| 도메인 | 장수 | 성격 | 사용 정책 |
|---|---:|---|---|
| `ldb-phinf.pstatic.net` | 80,164 | **업체/플레이스DB 사진** — 점주 등록 (가게·메뉴) | ✅ **수집 대상 (유일 기본)** |
| `naverbooking-phinf.pstatic.net` | 2,185 | 네이버예약 상품 사진 — 업체성 | 🟡 선택 (점주 등록물이라 낮은 위험 — 팀 결정) |
| `pup-review-phinf.pstatic.net` | 9,123 | **방문자 리뷰 사진** — 얼굴·초상권 위험 핵심 | ❌ 금지 + 기존분 정리 |
| `blogfiles / postfiles` | 3,424 | 블로그 본문 이미지 — 저작권+얼굴 | ❌ 금지 + 정리 |
| `clip-service-phinf` / `video-phinf` | 1,871 | 클립·영상 썸네일 (UGC) | ❌ 금지 + 정리 |
| `myplace` / `pup-post-phinf` | 514 | 방문자 포스트 | ❌ 금지 + 정리 |
| `blogpfthumb-phinf` | 160 | **블로그 프로필 썸네일 = 아바타 얼굴** | ❌ 금지 + 정리 |
| `phinf` (generic) / `g-place` / `ssl` 등 | ~2,100 | 불명·UI 리소스 혼재 | ⚠ 표본 확인 후 분류 |
| (source_url 없음) | 80 | 구버전 크롤·수동 업로드 | ⚠ 눈검사 |

**원칙 한 줄**: *점주가 네이버에 등록한 사진(ldb-phinf, 예약 사진)만 쓴다. 방문자·블로거가 올린 것은 저작권+초상권 이중 리스크라 수집·보관 모두 금지.*

## 4. 얼굴 사진 유입 원인 (진단 확정)

1. **`filterType=업체` 필터가 후행 도입** — 도입 이전 크롤분(어드민 버튼 경로 포함)이 무필터 사진 그리드에서 수집됨.
2. **현행 코드도 재발 가능**: 필터 진입 후 `img[src*="pstatic"]` 를 도메인 구분 없이 전부 수집. 필터가 안 먹은 경우의
   '업체' 탭 클릭 폴백은 실패해도 조용히 넘어가서, 그때 화면의 방문자 사진이 그대로 들어온다.

## 5. 조치 계획

| # | 조치 | 상태 |
|---|---|---|
| 1 | **크롤러 도메인 화이트리스트** — canonical 단계에서 `ldb-phinf`(+예약 사진 결정 시 naverbooking)만 통과. 필터 실패와 무관하게 구조적 차단 | 패치 준비 (팀 결정 후 PR) |
| 2 | **기존 오염분 정리** — (a) 핵심 위험군 12,816장(리뷰·블로그·myplace, 5,711곳) 최소선 / (b) UGC·불명 포함 ~17,200장 보수선. 정리 시 **사진 0장 되는 가게 441곳** → `naver-photos-backfill-queue` 재등록으로 업체 사진 재수집 | 범위 결정 대기 |
| 3 | 정리 방식: `image_status` 비활성 전환(소프트) → S3 원본·리사이즈 키 제거는 후속 배치 | 본 앱 실행 |
| 4 | dataops 메뉴 사진 트랙(93k 무사진 메뉴): 스냅샷 보유 URL 이 전수 `ldb-phinf` 원본 화질이라 같은 정책과 정합 — **저작권 법률 검토 통과 후** 파일럿 | 법률 게이트 |

## 6. 운영 참고

- 오염 재측정 쿼리: `SELECT ... CASE WHEN source_url LIKE '%ldb-phinf%' ... FROM store_image_file GROUP BY 1`
- 어드민 버튼으로 수동 크롤을 돌릴 때도 §3 정책이 적용되도록 크롤러 패치(조치 1)가 공통 지점 — 트리거가 어디든 같은 스크립트를 쓰기 때문.
