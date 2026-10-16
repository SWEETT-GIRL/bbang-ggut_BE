# #742 폴리곤 추천 배포·복귀 절차

2026-10-13. 구현은 `feat/742-polygon-course-lab`, 대상 브랜치는 dev다. 아래는 배포 실행 지침이며 이번 로컬 작업에서 운영 DB/설정을 변경하지 않았다.

## 코드와 데이터의 적용 순서

1. `recommendation.polygon.enabled=false`, `store.region-resolution.enabled=false` 상태로 코드와 Flyway 두 마이그레이션을 적용한다. 기존 추천은 그대로 실행된다. 번호는 20261013190000, 20261013200000이며 upstream의 리워드 마이그레이션과 겹치지 않는다.
2. 공식 경계/기준표의 기준일과 운영 시점 변동분을 확인한다. 로컬 검증 자료는 경계 2026-09-09, 기준표 2026-09-26, 가게 최신 갱신 2026-09-19다. 최신 운영 사실로 해석하지 않는다.
3. 검증한 릴리스 SQL과 manifest를 배포용 데이터 저장소에 함께 보관하고 SHA-256을 대조한다. 115MB 자료는 Git에 넣지 않는다. 아래 도구는 로컬 Docker 소켓에서만 내보내며 운영에 접속하지 않는다.
4. staging의 앱 스키마에서 릴리스 SQL을 `psql -X -v ON_ERROR_STOP=1 -f <검증한 SQL>`로 실행한다. 연결 대상은 환경의 기존 배포 수단으로 지정한다. SQL은 한 트랜잭션이며 기존 버전 중복/지역 ID 의미 차이/없는 법정코드가 있으면 실패한다. 실패 자료를 고쳐 같은 버전으로 덮어쓰지 말고 새 버전으로 검증한다.
5. `store.region-resolution.version-key`를 manifest의 정확한 `version_key`로 설정하고 갱신 배치를 활성화한다. 기본 500곳/30초이다. 초기 적재가 끝날 때까지 추천 플래그는 끈다. 동시 인스턴스는 SKIP LOCKED로 분배한다.
6. 아래 준비 상태 조회에서 pending=0, 버전 ready, 활성 지역 바인딩 누락=0인지 확인한다. NO_MATCH/AMBIGUOUS를 RESOLVED로 강제 바꾸지 않는다. 복수 코드가 모두 선택 영역 안이면 후보가 될 수 있다.
7. staging에서 칩→최초 추천→가게 담기→같은 시트 재진입→다시 받기 2회→다음 동네를 확인한다. 한 일차의 마지막 가게부터 연결되며 다른 일차 가게를 기준으로 삼지 않는지도 확인한다. 이유 문장은 기존 Gemini 경로로 생성된다.
8. 동작·응답 지연·직선거리로 놓칠 수 있는 도로 단절(강/고속도로/섬)을 검수한 후 추천 플래그를 활성화한다. 이 구현은 도보 라우팅이 아니므로 도보 이동 가능 시간을 보장하지 않는다. 운영 반영은 동일 검증 후 별도 배포한다.

```properties
# 두 version-key는 export manifest와 정확히 일치해야 한다.
store.region-resolution.enabled=true
store.region-resolution.version-key=<manifest.version_key>
store.region-resolution.batch-size=500
recommendation.polygon.enabled=true
recommendation.polygon.version-key=<manifest.version_key>
recommendation.polygon.distance-per-leg-m=500
recommendation.polygon.single-store-approach-m=1500
recommendation.polygon.retry-radius-m=1200
```

초기 총 직선거리 상한은 2/3/4/5/6곳에 500/1000/1500/2000/2500m이며 코스 끝부터 첫 추천까지도 예산에 포함한다. 1곳은 연결 거리 1500m, 모든 구간은 지역의 max_hop_m 이하이다. 운영에서 조정할 수 있고 저장된 재추천은 당시 정책을 유지한다. 최초 경로가 기본 재추천 반경보다 넓으면 경로가 전부 들어가도록 반경을 늘려 저장한다. exactArea 요청은 기존 중심/반경을 고정한다.

## 준비 상태 조회

아래 `:version_key`는 바인딩 파라미터이며 psql에서 실행할 때는 `:'version_key'` 형태로 사용한다.

```sql
SELECT v.version_key, v.ready,
  (SELECT count(*) FROM region_emd_boundaries b WHERE b.version_id=v.id) boundaries,
  (SELECT count(*) FROM course_area a WHERE a.is_active AND NOT EXISTS (
    SELECT 1 FROM course_area_region_bindings b WHERE b.version_id=v.id AND b.course_area_id=a.id
      AND b.area_signature=jsonb_build_array(a.sido,a.passport_spot,a.region_2nd,a.region_3rd))) missing_bindings,
  (SELECT count(*) FROM store s LEFT JOIN store_region_resolutions r
    ON r.store_id=s.id AND r.version_id=v.id WHERE r.id IS NULL
      OR ST_AsEWKB(r.query_location) IS DISTINCT FROM ST_AsEWKB(s.location::geometry)) pending
FROM region_boundary_versions v WHERE v.version_key=:version_key;

SELECT legal_status, count(*) FROM store_region_resolutions r
JOIN region_boundary_versions v ON v.id=r.version_id
WHERE v.version_key=:version_key GROUP BY legal_status;
```

## 복귀와 버전 갱신

- 즉시 복귀는 `recommendation.polygon.enabled=false`이다. 이후 요청은 기존 추천 경로를 사용한다. 이미 받은 응답은 클라이언트에 남을 수 있다. 기존 버전의 폴리곤 재추천도 플래그를 끄면 기존 경로로 돌아간다.
- 지역 배치는 별도로 끌 수 있다. `store.region-resolution.enabled=false`는 새로운 판정 갱신만 멈추며 원래 가게 테이블에 되돌릴 변경은 없다.
- 경계/매핑 수정은 새 버전으로 적재·판정 후 새 최초 추천에 사용한다. 이전 버전은 저장된 RETRY의 범위를 재현하기 위해 유지한다. 과거 버전의 가게가 이동했으면 기존 판정은 조회에서 즉시 제외되고, 활성 배치는 설정된 한 버전만 갱신한다.
- 같은 시트 재사용은 기존 가게의 자격·현재 좌표와 코스 끝 좌표를 확인한다. 새 세션은 같은 회원·코스·일차에서 이미 본 가게를 제외한다. 전부 소진하면 칩 재진입만 첫 묶음으로 돌아갈 수 있고, RETRY/다음 동네는 본 가게로 되돌리지 않는다.
- 미매칭 좌표 및 선택 구역을 가로지르는 복수 판정은 후보에서 제외한다. 기존 `region_*` 표시 문자열은 수정하지 않는다. 개별 주소/좌표 예외 정비는 근거를 확인한 별도 데이터 작업이다.

## 로컬 재현 도구

- `scripts/lab/load_region_boundaries.py`: 공식 기준표 5,067개와 도형의 완전 일치 확인 후 버전 적재.
- `scripts/lab/load_course_area_bindings.py`: 활성 59행/48칩에 1,761개의 법정동 집합 연결. 서울/세종의 동 선택을 유지하고 인천 옛 중구는 52개 법정동만 연결.
- `scripts/lab/export_region_release.py`: `--load-manifest .../runtime-load.json --output .../region-release.sql`.
- `scripts/lab/verify_region_release.py`: `--release .../region-release.sql --output .../region-release-verification.json`. 실제 두 마이그레이션→SQL 재적재→도형/연결표 대조→DDL까지 롤백.
- 릴리스 재적재 검증: 도형 5,067개·연결 59개·ready=true, 원래 도형/계층/코드 연결 차이 0건.

## 개발 DB 복구 및 활성화 (2026-10-16)

개발 DB에서 `course_area`, `store_profile`, `store_recommendation_feature`가 모두 0행이었지만
지역 시드 Flyway 이력은 성공 상태였다. 테스트 코스의 `region=경기`는 정상이고 추천 컨텍스트만
`areas=[]`여서, 기존 기준·특성 데이터를 복구했다. 삭제 또는 복원 시점은 확인하지 못했다.
Flyway 이력을 지우거나 예전 마이그레이션을 수정하지 않았다.

- 자료: 이전 로컬 검증 DB의 지역 59행/48칩, 2026-09-21 가게 특성 스냅샷.
- 가게 ID·이름·주소·좌표가 개발 DB와 모두 일치하는 행만 복구했다. 이름/주소가 달라진 7곳은 제외했다.
- 복구 결과: `course_area` 59, `store_profile` 47,617, `store_recommendation_feature` 37,262행.
- SQL은 빈 대상 테이블과 가게 동일성을 다시 검사하며 한 트랜잭션으로 처리한다.
  로컬 임시 테이블 재적재/롤백을 먼저 검증했다. 운영 DB에는 적용하지 않았다.
- 동일 SHA-256의 공식 릴리스 SQL을 재검증한 뒤 개발에 적재했다.
  경계 5,067개, 앱 구역 262개, 지역 연결 59개, ready=true.
- 애플리케이션과 같은 판정 SQL로 53,104곳을 500곳씩 처리했다.
  RESOLVED 53,043 / AMBIGUOUS 45 / NO_MATCH 16, pending=0, missing_bindings=0.
  모호/미매칭을 강제로 정상 판정하지 않았다.

데이터 SQL 실행에는 PR이 필요하지 않다. 활성화 설정이 다음 개발 배포에도 유지되도록
`cd-staging.yml`은 아래 Repository variables를 읽는다. 모두 미설정이면 기존 추천을 유지한다.

| 변수 | 값/용도 |
|---|---|
| `STAGING_POLYGON_RECOMMENDATION_ENABLED` | 데이터 검증 후 `true`, 복귀 시 `false` |
| `STAGING_REGION_RESOLUTION_ENABLED` | 변경된 가게 좌표 판정 배치 활성화 |
| `STAGING_POLYGON_VERSION_KEY` | 검증한 릴리스 manifest의 정확한 `version_key` |

설정 PR 배포 전 개발 서버는 별도의 `docker-compose.polygon-742.yml` 환경변수 오버라이드로
활성화한다. 기존 두 Compose 파일에 이 파일을 마지막으로 추가해 앱 서비스만 재생성했다.
설정 PR을 배포하면 기존 배포 명령(두 Compose 파일)에서도 위 변수로 같은 설정을 생성한다.
PR 배포 전에 다른 개발 배포를 실행하면 임시 오버라이드가 빠질 수 있으므로 재확인한다.
개발 복구 작업은 운영 DB와 운영 런타임 설정을 변경하지 않았다. 운영 배포 변수 연결은 아래 절차로 별도 준비한다.


### 개발 환경 검증 결과

- 전국 경계 5,067개의 도형 유효성 오류 0건. 개발 가게 53,104곳을 `ST_Covers`로 다시
  계산한 법정 코드 집합과 저장된 판정의 차이 0건(현재 좌표 일치도 함께 검사).
- 활성 지역 59행/48칩의 연결 누락 0건. 모든 칩에 ACTIVE·프로필·bakery_score ≥ 0.5를
  충족하는 후보가 4곳 이상 있으며 최소 13곳, 최대 1,015곳이다. 사용자별 기록/코스/노출 제외 전 수치다.
  성남시 692곳, 세종 도담동 13곳, 조치원읍 33곳을 포함한다.
- 전국 추천 자격 가게는 37,805곳이며 현재 48칩의 범위에 들어오는 고유 후보는 14,160곳이다.
  전국 경계를 적재했다고 앱에서 모든 시군구·동을 선택할 수 있게 된 것은 아니다.
- 복수 경계 판정 45곳과 미매칭 16곳은 데이터 예외로 남긴다. 복수 판정은 모든 법정 코드가
  선택 범위 안에 들어오는 경우에만 후보로 허용하고 미매칭은 제외한다.
- 시뮬레이터에서 성남 지역 추천 5건 모두 `polygon-hybrid-v1`과 Gemini 이유 생성(fallback=false)을 확인했다.
  최초 추천 재진입은 같은 결과를 재사용했고 재추천 2회까지 총 12곳이 중복되지 않았다.
  1일차 2곳·2일차 4곳을 담은 뒤, 2일차의 최초 추천에는 1일차 출발점이 적용되지 않고
  1일차 추가 추천은 1일차 마지막 가게부터 연결되는 것을 확인했다. 담긴 6곳은 추가 추천에서 제외됐다.
- 다섯 추천 모두 선택 폴리곤 안의 적격 가게 4곳으로 구성됐고 출발점 접근을 포함한 총 직선거리
  1,500m 및 구간 상한을 만족했다. API의 `totalDistanceM`는 추천 가게 사이의 합이며
  경로 예산은 코스 마지막 가게부터의 접근 거리까지 포함한다.
- 전국 검증은 DB 경계·판정·후보 전수 검사다. 48개 지역 각각의 앱 요청과 모든 취향·재추천 조합을
  실행한 것은 아니며, 도로·강·섬을 고려한 실제 도보 경로도 별도 검증 대상이다.


### 운영 설정 선택 반영

운영 플래그 연결은 별도 커밋 `d364e754`이며 `.github/workflows/cd-prod.yml`만 수정한다.
기본값은 꺼짐이다. #934를 dev에 반영한 뒤 운영에는 이 커밋만 체리픽할 수 있다.
폴리곤 구현과 마이그레이션은 #933에 이미 포함돼 있으므로 #933이 main에 머지되면 다시 체리픽하지 않는다.

| Repository variable | 설정 시점 |
|---|---|
| `PROD_POLYGON_VERSION_KEY` | 운영에 적재한 검증 릴리스 manifest의 `version_key` |
| `PROD_REGION_RESOLUTION_ENABLED` | 경계·연결 적재 후 `true`: 운영 가게 좌표 판정 시작 |
| `PROD_POLYGON_RECOMMENDATION_ENABLED` | 판정 pending=0·연결 누락=0 및 후보 검증 후 `true` |

GitHub 저장소 Settings → Secrets and variables → Actions → Variables에서 변경한다.
변수를 바꾼 뒤 Actions의 `CD - Production`을 `main`에서 수동 실행해야 설정이 반영된다.
추천을 되돌릴 때도 `PROD_POLYGON_RECOMMENDATION_ENABLED=false`로 바꾸고 재배포한다.
운영에는 개발 빈 테이블 복구 SQL을 적용하지 않고 공식 경계·지역 연결 SQL과 운영 좌표 판정만 적용한다.

2026-10-16 확인 결과 운영에 적용된 SQL 174개는 main/dev/#933 릴리스 브랜치의
체크섬과 모두 일치했다. 각 브랜치의 버전 중복과 기존 마이그레이션 누락/변경은 0건이다.
새 폴리곤 버전 `20261013190000` → `20261013200000`은 dev와 #933에서 동일하며,
운영 Flyway에 아직 적용되지 않았다. 운영 설정 패치는 당시 main과 #933 릴리스 브랜치에
충돌 없이 적용되는 것을 확인했다. 이후 다른 변경이 들어오면 체리픽 시 재검증한다.
이미 적용된 SQL의 파일명·번호·내용을 변경하거나, 같은 DDL을 별도 번호로 복제하지 않는다.
