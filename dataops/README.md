# 빵긋 DataOps — 데이터 최신화

> **2026-10-21 이 레포(bbang-ggut_BE)로 옮겨 왔다.** 원래 `SWEETT-GIRL/bbang-ggut_dataops_BE`.
> - Java 판정 서버: `batch/src/main/java/sweetgirl/dataops` — batch 모듈 JAR 안의 별도 앱(설정 `dataops.properties`)
>   - 로컬 실행: `./gradlew :batch:bootJar` 후 `java -Dloader.main=sweetgirl.dataops.DataopsApplication -cp batch/build/libs/batch-*.jar org.springframework.boot.loader.launch.PropertiesLauncher`
>   - 마이그레이션: `batch/src/main/resources/db/dataops-migration` (운영은 RDS `dataops` 스키마)
> - Python(크롤·승인·등록): 이 `dataops/` 폴더. 의존성 `dataops/requirements.txt`
>   - 서버 호출에 `DATAOPS_API_KEY` 환경 변수가 있으면 `X-Dataops-Key` 헤더를 붙인다(배치 EC2 서버는 필수)
> - `agent/`(응답 구조 대응)는 **옮기기만 했다** — 경로·빌드·PR 대상이 옛 레포 기준이라 모노레포에 맞춘 재작업 전에는 돌지 않는다
> - Windows 러너(`*.bat`·`*.ps1`)는 옛 집 PC 운영 기록으로 남긴다. 배치 EC2 실행은 SSM 이 맡는다
> - 아래 본문의 `./gradlew bootRun`·`src/...` 경로는 옛 레포 기준이다

운영 DB에 적재된 가게 45,444개(네이버 매칭 ~30,900개)의 가게 정보·메뉴를
**주체적으로 최신 상태로 유지**하는 파이프라인. 첫 전체 스윕이 곧 최초 수집 누락 백필이다.
로컬 세션형으로 실행하며, **운영 DB에는 어떤 테이블도 만들지 않는다.**

> 구현 기준: [설계서 v2](docs/data-freshness-design.md) · [스펙](docs/data-freshness-spec.md) ·
> [구현 플랜](docs/data-freshness-plan.md) · [레포 전략](docs/repo-strategy-comparison.md)

> **2026-09-25 개선안 v3 (구현 전)**: [안정화·운영화 spec](docs/data-freshness-v3-spec.md) ·
> [단계별 구현 plan](docs/data-freshness-v3-plan.md).
> 관측·신호 분리, 승인/등록 복구, 후보 영속화, 추천 데이터 반영과 운영화를 다룬다.
> 아래 본문과 기존 문서는 초기 구조의 설명이며, 바뀌는 목표 요구사항은 v3를 참조한다.

## 아키텍처 원칙

- **주 DB**: 로컬 PostgreSQL(Docker, 15433) — 최신화 상태 전부 여기에 (Flyway 관리):
  `store_sync_state`(워크리스트) · `place_snapshot`(원본층) · `store_field_provenance` ·
  `change_proposal` · `apply_log` · `new_store_candidate`
- **운영 DB**: 스키마 불가침 — 현재값 SELECT + 판정 통과분의 소량 DML만.
  운영 DML은 `ServingWriter` 단일 통로, 모든 쓰기는 `apply_log` 선기록, 물리 DELETE 금지
- **판정 분리**: 크롤 워커는 원본만 제출하고 판정하지 않는다 — diff·병합·반영은 서버 판정층 담당
- **큐 없음**: `store_sync_state.next_due_at`이 곧 워크리스트 — 별도 작업 큐·스케줄러·복구 로직 없음

## 실행

```bash
docker compose up -d        # 로컬 PostgreSQL 기동
cp .env.example .env        # 운영 DB 계정 기입 (없어도 서버는 뜸 — 운영 조회만 비활성)
./gradlew bootRun           # http://localhost:8090 (Swagger: /swagger-ui.html)
```

## 명령어

```bash
./gradlew spotlessApply     # Naver 포맷 적용
./gradlew test              # 전체 테스트
./gradlew build             # 빌드
```

## 크롤 워커 (scripts/sync_worker.py)

due 목록(`GET /api/sync-states/due`)을 폴링해 네이버 플레이스 모바일 페이지를
requests로 fetch(1.5초/req)하고, `__APOLLO_STATE__` 원본을 `POST /api/snapshots`로 제출한다.

- 판정·파싱하지 않는다 — 원본 전달자
- fetch 실패는 `POST /api/sync-states/{id}/failure` 보고 → 서버가 백오프
- 차단 감지 시 exit 3로 즉시 종료 — 복구 로직 없음(안 긁힌 가게는 due로 남는다)
- 세션형: 언제 켜든 가장 밀린 가게부터, 도중에 꺼도 정리할 것 없음
