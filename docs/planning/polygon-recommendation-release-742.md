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
