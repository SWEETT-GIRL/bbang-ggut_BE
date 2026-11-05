# 오텔 trace 수집

브랜치 `feat/otel-tracing` · base `dev`

## 목표
v1 운영 트래픽의 요청 하나가 어느 구간(DB 등)에서 시간을 쓰는지 Grafana 에서 본다. 메트릭·로그 수집은 바꾸지 않는다.

## 구성
- 에이전트 v2.32.0 을 이미지에 포함, `-javaagent` 는 스위치를 켤 때만 붙인다(기본 꺼짐)
- 앱 → Collector → Tempo(3일) → Grafana. Collector 가 쿼리 문자열·IP·UA·DB 계정을 지우고 헬스체크를 버린다
- 로그에 `trace_id` 추가, `requestId` 유지

## 로컬 확인 (2026-11-04, 랩 DB)
- 가짜 API 키·검색어·IP 를 담은 span → Tempo 에 남지 않음, 헬스체크 span 버려짐
- HTTP → JPA → SQL 구간이 한 trace 로 보이고 SQL 값은 `?` 로 가려짐, 로그에 trace_id 찍힘
- Redis(Lettuce) span 은 잡히지 않았다 — 원인 미확인

## 남은 일
- [ ] 모니터링 서버 여유 확인 후 Collector·Tempo 적용, Grafana 데이터소스 추가
- [ ] staging 에서 켜서 메모리·기동 시간 비교 (Scouter 에이전트와 함께 동작하는지 포함)
- [ ] Redis span 이 안 잡히는 원인 확인
- [ ] 운영 스위치 켜기, 실제 span 개인정보 검수

## 측정 결과
(여기서 다음 스텝을 정한다)
