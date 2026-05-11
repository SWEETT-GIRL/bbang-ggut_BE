# Dana (임지빈) 구현 TODO
> 구현 기준: 엔티티 설계가 반영된 `origin/dev`

---

## 구현 순서


### 2. 중복 체크 API 외부 공개 여부 결정

API 후보:

- [ ] `GET /alarm/duplicate-check`

결정 기준:

- 프론트가 직접 호출해야 하면 Controller에 공개
- 서버의 `location-check` 내부 로직으로 충분하면 공개하지 않음

주의:

- 이 API는 우선순위 낮음
- 위치 기반 알림 구현 후 프론트 요구에 따라 추가
