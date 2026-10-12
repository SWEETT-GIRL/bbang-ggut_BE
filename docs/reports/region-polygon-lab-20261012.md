# 법정구역 폴리곤 로컬 검증 결과

2026-10-12 KST · 상태: 공식 SHP 확보·로컬 적재·시범 가게 매칭 완료, 운영 미반영

**공식 경계의 도형 복구본에서 수원·성남·고양·세종 적격 가게 2,802곳 전부가 하나의 법정구역에 매칭됐다.** 원본을 그대로 적재한 결과와 복구 결과를 분리했다. 이는 지역 소속 판정 실험이며 추천 품질이나 추천 노출 커버리지 100%를 뜻하지 않는다. 운영 DB와 앱 코드는 변경하지 않았다.

| 로컬 표본 | 적격 가게 | 원본의 유효 도형으로 매칭 | 복구본으로 매칭 | 복구본 미매칭/복수 매칭 |
|---|---:|---:|---:|---:|
| 수원시 | 1,015 | 1,015 | 1,015 | 0 / 0 |
| 성남시 | 692 | 674 | 692 | 0 / 0 |
| 고양시 | 835 | 818 | 835 | 0 / 0 |
| 세종 | 260 | 229 | 260 | 0 / 0 |

[원본 매칭 결과](region-polygon-real-20261012.json) · [복구 도형 진단](region-polygon-repair-20261012.json) · [복구본 매칭 결과](region-polygon-repaired-match-20261012.json)

## 공식 자료

- 브이월드 [법정구역정보](https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?svcCde=NA&dsId=21). 2026-10-12 화면에서 조회된 최신 전체 데이터는 기준일·갱신일 2026-09-09, 전국 SHP 약 120MB다. 이후 변동분은 별도로 존재하므로 이 전체 파일을 10월 현재의 완전한 경계라고 부르지 않는다.
- 제공기관 국토교통부, 라이선스 CC BY. 실제 `.prj`의 EPSG:5186을 확인하고 DBF에서 CP949 한글 디코딩을 확인했다. `.cpg`는 없다.
- 2026-08-19 컬럼 정의서와 실제 DBF에서 A1=8자리 읍면동 코드, A2=읍면동명을 확인했다. 실제 파일은 A0~A4이며 정의서의 변동 레이어 A0~A6과 같지 않다. SIG·EMD·LIO 중 EMD만 판정에 사용했다.
- 사용자가 로그인한 뒤 브라우저의 정상 다운로드로 `AL_D001_00_20260909.zip` 125,828,758바이트를 확보했다. SHA-256: `3f8952dfda7763c2c594adb7a9f7ace1f40b996235fae6ef0d0f90f794dbce97`.
- 원본 ZIP, EMD 구성 파일, 컬럼 정의서는 `/Users/akkobji/bbanggut-lab/region-polygons-20261012/`에 보존했다. 대용량 원본은 Git에 넣지 않는다.

## 원본 품질과 별도 복구본

EMD 원본은 5,373행이다. 코드 형식 불일치 306행, 코드 형식은 맞지만 도형 자기 교차가 있는 55행을 원본 판정에서 제외했다. 남은 5,012행만 원본 결과의 판정에 썼다. 코드를 이름으로 추정하거나 숫자로 강제 변환하지 않았다.

시범 구역 코드 186개 중 원본 도형이 무효인 곳은 세종 반곡동·고운동·세종동·용호동, 성남 판교동, 고양 덕이동·가좌동의 7개다. 이 도형들을 제외한 것이 위 원본 미매칭 66곳의 원인이었다. 가게 주소 오류로 분류하지 않았다.

원본을 보존한 별도 소스에 `ST_MakeValid` 기본 linework 방식과 polygon 추출을 적용하고 다시 유효성을 검사했다. 전체 55개가 유효해졌고, 코드 형식 불일치 306행은 계속 제외했다. 복구 전후 최대 절대 면적 변화는 약 **0.0121㎡ 미만**, 도형 경계의 Hausdorff 거리 최댓값은 약 **0.000000432m**다(EPSG:5186 계산). 기하 구조 복구이며 좌표를 가게 쪽으로 이동시키지 않았다. 이 수치는 공식 최신성과 도보 접근성을 보장하지 않는다. [ST_MakeValid 문서](https://postgis.net/docs/ST_MakeValid.html)

- 원본 source ID: `043a04d7c07841a1828364572a5ef4d6`
- 복구 후보 source ID: `2f83cb96f1184150859238e4835df12d`
- 복구 후보는 `DERIVED_REPAIR_CANDIDATE`로 표시하고 원본 source ID·PostGIS/GEOS 버전·복구 방법을 기록했다.
- 시범 공식 코드 186개 모두 사용 가능한 도형이 있다. 지역별 적격 가게도 모두 유일하게 매칭되며, 기존 도시 소속과 다른 결과는 0곳이다.

## 로컬 구현과 검증

[실행 도구](../../scripts/lab/region_polygon_lab.py)는 Python 표준 라이브러리와 컨테이너의 `shp2pgsql`을 사용한다. 연결 대상은 `docker exec postgres-lab` 안의 Unix socket, DB `soboro`로 고정했다. `application-local.properties`나 운영 터널을 사용하지 않는다. 쓰기는 별도 `region_polygon_lab_742` 스키마에 한정하며 `public.store`는 조회만 한다.

- SHP 구성 파일 SHA-256, 원본 `.prj`, 출처, 기준일, 인코딩을 기록한다.
- EPSG 변환 후 MultiPolygon을 보존한다. 무효·빈·비정상 범위 도형은 원본 판정에서 제외하고 원본 행을 남긴다. 명시적인 `repair-copy` 작업만 별도 복구 후보를 만들며 가게 좌표 이동은 하지 않는다.
- `ST_Covers`로 가게 좌표를 대조한다. 같은 코드의 여러 조각은 중복 코드로 세지 않고, 서로 다른 코드가 겹치면 모호 상태로 남긴다.
- 8자리 EMD 코드는 뒤에 `00`을 붙여 기존 10자리 법정동 기준표와 대조한다. 데이터 기준일 차이와 매칭되지 않는 코드를 별도로 확인한다.
- 데이터 가게 전수의 잘못된 소속을 찾는 도구는 아니다. 기본 비교 대상은 기존 필드상 수원·성남·고양·세종 적격 가게이며, 다른 지역으로 잘못 표기된 가게까지 탐지했다고 주장하지 않는다.

실행한 검증:

| 검증 | 결과 |
|---|---|
| 내부 점, 외부 점 | 통과 |
| 공유 경계선의 복수 구역 판정 | 통과, 임의 단일 확정 안 함 |
| 폴리곤 내부 구멍 | 통과 |
| MultiPolygon의 두 번째 조각 | 통과 |
| 교차하는 무효 도형 식별 | 통과 |
| EPSG:4326 → 5186 → 4326 왕복 | 통과 |
| 테스트 SHP 생성 → 변환 적재 → 집계 보고서 | 통과, SYNTHETIC_TEST로 표시 |
| 정자동·나성동·성수동2가 공식 코드 관계 | 확인 |

테스트 도형은 실제 구역 외곽선이 아니다. 테스트 파일·집계는 `/Users/akkobji/bbanggut-lab/region-polygons-20261012/synthetic-fixture`에 격리했다. 실제 경계 커버리지로 해석하지 않는다.

로컬 가게 테이블은 53,101행, 최신 `updated_at`은 2026-09-19이며 적격 가게는 37,812곳이다. 운영의 10월 집계와 시점이 다르다. 최종 매칭 보고서에도 입력 시점을 남긴다.

## 지역 계층에서 추가로 확인한 점

기존 `bjd_code_api_20260926.csv`에서 정자동의 상위 관계는 **정자동 → 분당구 → 경기도**다. 성남시 행은 따로 존재하지만 분당구의 `locathigh_cd`가 성남시를 가리키지는 않는다. 코드의 상위 관계를 따라가기만 하면 앱의 성남시 묶음을 찾지 못한다.

도구는 공식 상위 관계를 수정하지 않는다. 검증된 전체 지역명 경로를 바탕으로 별도 앱 범위 매핑을 만들고 코드별로 보존한다. 기준표의 EMD 수원 56·성남 44·고양 53·세종 33개가 연결됐으며 복구본에서 모두 경계 도형을 확인했다. 일반 주소의 4단계, 세종의 중간 단계 생략을 세 필드에 억지로 맞추지 않는다.

## 재현과 이어서 할 작업

```bash
python3 scripts/lab/region_polygon_lab.py check \
  --reference ../bbang-ggut_dataops_BE/reference/bjd_code_api_20260926.csv
```

공식 ZIP의 경로 탈출 여부를 확인해 압축을 풀었다. 실제 EMD 파일의 경로와 확인된 인코딩으로 다음과 같이 실행한다. 각 import는 새 source ID를 생성한다.

```bash
python3 scripts/lab/region_polygon_lab.py import-shp \
  --shp '/Users/akkobji/bbanggut-lab/region-polygons-20261012/official-20260909/EMD/AL_D001_00_20260909(EMD).shp' \
  --source-url 'https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?svcCde=NA&dsId=21' \
  --source-date 2026-09-09 --source-srid 5186 --encoding CP949

python3 scripts/lab/region_polygon_lab.py compare \
  --source-id SOURCE_ID_FROM_IMPORT \
  --reference ../bbang-ggut_dataops_BE/reference/bjd_code_api_20260926.csv \
  --output /absolute/path/to/real-boundary-report.json

python3 scripts/lab/region_polygon_lab.py repair-copy \
  --source-id SOURCE_ID_FROM_IMPORT \
  --output /absolute/path/to/repair-diagnostics.json
# repair-copy가 반환한 새 source ID로 compare를 다시 실행한다.
```

경계와 가게 좌표를 함께 표시한 검수 지도를 만들었다. 표시용 도형만 EPSG:5186에서 20m 허용치로 단순화했으며 매칭은 단순화하지 않은 도형으로 수행했다. 지도는 시범 지역 전체의 읍면동, 가게, 기존 추천 원을 함께 표시한다.

다음 단계는 9월 전체 파일 이후 변동분·운영 시점 데이터 대조, 다른 지역과 무효 코드 행의 범위 검토, 실제 추천 코스 A/B/C/D 비교다. 법정구역 소속이 결정됐다고 같은 동 전체가 도보 코스로 적합한 것은 아니다. 운영 반영과 #742 완료 처리는 하지 않았다.
