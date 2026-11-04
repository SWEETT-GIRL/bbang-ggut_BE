# trace 수집 스택

앱(오텔 에이전트) → Collector(민감 값 제거) → Tempo(3일 보관) → Grafana

Collector 를 거치지 않으면 외부 API 호출의 `url.full` 에 API 키가 그대로 저장되므로 앱에서 Tempo 로 바로 보내지 않는다.

## 모니터링 서버 적용

1. 여유 확인: Collector 192MB + Tempo 384MB 를 더 쓴다. `free -m`, `df -h /`
2. 이 폴더를 서버로 옮겨 `docker compose up -d`
3. 보안그룹: 4318 인바운드를 WAS 보안그룹에만 허용. 3200 은 열지 않는다
4. 기존 Grafana 에 Tempo 데이터소스 추가 (Grafana 가 호스트 네트워크가 아니면 주소를 맞춘다)

## 앱에서 켜고 끄기

| 환경 | 저장소 변수 | 적용 |
|---|---|---|
| 운영 | `PROD_OTEL_ENABLED=true`, `PROD_OTEL_ENDPOINT=http://<모니터링 사설 IP>:4318` | CD - Production 수동 실행 |
| staging | `STAGING_OTEL_ENABLED=true`, `STAGING_OTEL_ENDPOINT=...` | CD - Staging 재실행 |

끄려면 `*_OTEL_ENABLED=false` 로 두고 CD 를 다시 실행한다. Collector 가 죽어도 요청은 실패하지 않고 trace 만 유실된다.

## 켠 뒤 확인

- Tempo 에서 실제 span 을 열어 검색어·API 키·IP 가 남지 않았는지 본다
- 에이전트 켜기 전후 메모리와 기동 시간을 비교한다 (CD 헬스체크 한도 180초)

## 로컬 확인

```bash
docker compose -f compose.yaml -f compose.local.yaml up -d   # Grafana http://localhost:3000
```

앱을 로컬에서 띄우면 스케줄러가 외부 API 를 부를 수 있다. 측정 전에 로컬 설정의 API 키를 확인한다.
