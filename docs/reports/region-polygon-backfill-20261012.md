# 지역 소속 저장·자동 갱신 구현 — #742

2026-10-12 · `feat/742-polygon-course-lab` · 로컬 구현·검증, 운영 미반영

## 구현한 동작

- `region_boundary_versions`: 공식 경계 출처·기준일·코드 기준표 해시·투영 버전. 적재 중인 버전은 조회에서 제외한다.
- `region_app_scopes`: 이름 대신 공식 코드로 식별한 앱 지역. 시·군·구의 첫 도시 단위, 세종은 읍면동 단위다.
- `region_emd_boundaries`: 법정 읍면동 코드·공식 이름·실제 상위 관계·폴리곤·앱 지역 관계. 확정한 버전의 내용은 트리거로 변경을 막는다.
- `store_region_resolutions`: 가게·버전별 조회 좌표, 매칭 법정코드 목록, 앱 지역 목록, 단일 앱 지역 코드와 판정 상태.

법정코드가 여럿이어도 모두 같은 앱 지역이면 도시 소속을 사용할 수 있다. 다른 지역이 겹치거나 미매칭인 가게는 임의로 하나를 선택하지 않는다. 기존 region_1st/2nd/3rd를 덮어쓰지 않는다.

좌표가 바뀌면 기존 판정은 조회에서 즉시 제외한다. 배치는 신규·좌표 변경·새 버전의 미처리 가게를 한 번에 500곳씩 처리한다. 미매칭도 처리 완료로 기록해 같은 행에 갇히지 않으며, 처리한 좌표가 다시 바뀌면 재판정한다. 가게 행의 FOR UPDATE SKIP LOCKED로 갱신 간 경합을 피한다.

한 버전 안에서는 가게별 최신 판정 행을 갱신한다. 같은 버전의 과거 좌표 이력을 모두 보존하는 구조는 아니다. 버전별 행과 외부 원본·감사 근거는 별도로 보존한다.

## 전국 로컬 저장 결과

별도 스키마 `region_polygon_runtime_742`에 실제 마이그레이션 구조를 생성했다. 기존 `public.store`는 조회·행 잠금에만 사용했다.

| 항목 | 결과 |
|---|---:|
| 읍면동 경계 | 5,067개 |
| 코드 기반 앱 지역 | 262개 |
| 저장한 가게 판정 | 53,101곳 |
| 단일 법정코드 | 53,040곳 |
| 복수 법정코드 | 45곳 |
| 그중 앱 지역은 하나 | 36곳 |
| 미매칭 | 16곳 |
| 독립 감사와 가게별 법정코드·앱 지역 확정 여부 일치 | 53,101/53,101 |
| 동일 입력 재갱신 | 0곳 |

최초 저장은 마지막 빈 배치를 포함해 108회, 로컬 실행 약 3.26초였다. DB·코드 캐시 등 환경에 의존하며 운영 처리량을 보장하는 수치가 아니다. 원본 가게의 ID·지역 필드·좌표·updated_at을 합친 지문은 전후 같았다. 집계 근거는 [JSON](region-polygon-backfill-20261012.json), 원본 공간 근거는 [전국 감사](region-polygon-nationwide-20261012.md).

## 활성화 조건과 재현

기본은 비활성화다. 검증한 경계가 해당 애플리케이션 스키마에 적재돼 있어야 하며, 아래 설정의 버전은 정확한 version_key여야 한다.

```properties
store.region-resolution.enabled=true
store.region-resolution.version-key=<적재 결과의 version_key>
store.region-resolution.batch-size=500
store.region-resolution.poll-delay=PT30S
```

`enabled=false`면 서비스는 DB에 접근하지 않고 스케줄러 빈을 생성하지 않는다. 활성화 상태에서 버전 누락·미확정 버전·잘못된 배치 크기는 실패시킨다. 이것은 지역 갱신 배치의 설정이며 추천 API를 전환하는 플래그가 아니다.

로컬 적재 도구는 `scripts/lab/load_region_boundaries.py`. Docker `postgres-lab` 소켓과 고정된 랩 스키마만 사용한다. 기준표의 모든 읍면동 코드와 유효 도형이 정확히 대응하는지 검사한 뒤 한 트랜잭션으로 적재·확정한다. 같은 버전 재적재는 확정본을 재사용한다. 개발/운영 적재 도구로 그대로 사용하면 안 된다.

```bash
python3 scripts/lab/load_region_boundaries.py \
  --source-id 2f83cb96f1184150859238e4835df12d \
  --reference /Users/akkobji/Desktop/sw17/bbang-ggut_dataops_BE/reference/bjd_code_api_20260926.csv \
  --output /Users/akkobji/bbanggut-lab/region-polygons-20261012/runtime-load.json

POLYGON_REGION_LOAD=/Users/akkobji/bbanggut-lab/region-polygons-20261012/runtime-load.json \
POLYGON_REGION_EVIDENCE=/Users/akkobji/bbanggut-lab/region-polygons-20261012/nationwide-evidence.json \
POLYGON_REGION_REPORT=/Users/akkobji/bbanggut-lab/region-polygons-20261012/runtime-backfill-repeat.json \
./gradlew test -PlabTests --tests '*StoreRegion*Test'
```

## 검증과 남은 작업

- PostGIS 픽스처: 단일/공유 경계/복수 앱 지역/미매칭/좌표 없음, 배치 제한과 진행, 좌표 변경 즉시 제외·재판정, 버전 분리, 확정 도형·버전 변경 방지.
- 서비스: 기본 비활성, 명시적 버전 필요, 배치 크기 검증, 정상 처리, DB 실패 전파.
- 전국 저장: 가게별 코드 일치, 모든 행 처리, 무변경 재실행 0건, 원본 가게 필드 불변.

이 단계에서는 추천 API·후보 점수 SQL·코스 생성 경로를 바꾸지 않았다. 미매칭·복수 지역의 최신 공식 근거 대조, 표시 차이 분류, 추천 요청 범위와 코드 기반 앱 지역 연결, 서비스 스키마 적재·시범 배포가 남아 있다. 운영 마이그레이션 및 배치 활성화는 하지 않았다.

전체 `./gradlew spotlessApply build -x installGitHooks`가 성공했다. 기본 테스트 2365건, 실패 0, 오류 0, 건너뜀 4건이며 Spotless·Checkstyle도 통과했다. 별도 지역 랩 검증은 서비스 5건·공간 픽스처 6건·전국 저장 1건으로 총 12건이다.
