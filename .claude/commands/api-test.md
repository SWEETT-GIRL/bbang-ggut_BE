# API 자동 테스트 커맨드

현재 브랜치에서 변경/추가된 API를 자동으로 찾아서 호출하고 검증합니다.
변경된 API가 없으면 전체 Controller 목록에서 선택할 수 있습니다.

**사용법:** `/api-test`

**기본 URL:** `http://localhost:8080` (경로는 각 Controller의 `@RequestMapping` 기준)

---

## 진행 순서

### 1. 변경된 Controller 파일 찾기
```bash
# 기본 브랜치(dev — 개발·검증) 대비 변경된 Controller 파일 목록
git diff dev --name-only | grep -E "Controller\.java$"

# dev가 없으면 origin/dev 사용
git diff origin/dev --name-only | grep -E "Controller\.java$"
```

### 1-1. 변경된 Controller가 없는 경우 (Fallback)
1. 프로젝트 내 모든 Controller 검색:
```bash
find src -name "*Controller.java" -type f
```
2. AskUserQuestion으로 테스트할 Controller 선택 요청 (찾은 목록을 옵션으로 제시)

### 2. Controller 분석
변경된 Controller 파일을 읽고 API 엔드포인트 추출:
- `@RestController`, `@RequestMapping` 클래스 레벨 경로
- `@GetMapping`/`@PostMapping`/`@PutMapping`/`@PatchMapping`/`@DeleteMapping`
- 각 메서드의 HTTP 메서드, 경로(클래스+메서드), `@PathVariable`, `@RequestBody` DTO, `@RequestParam`
- 인증 필요 여부(`@AuthenticationPrincipal`) 확인

### 3. Request DTO 분석 및 샘플 데이터 생성
POST/PUT/PATCH는 Request DTO를 읽고 샘플 자동 생성:
- `String` → "테스트"
- `Integer`/`Long` → 1
- `Boolean` → true
- `List<T>` → []
- `LocalDate` → "2024-01-01"
- `LocalDateTime` → "2024-01-01T00:00:00"
- nullable 필드는 생략 가능

**PathVariable:** `{id}`, `{memberId}` 등 숫자 ID → 1

### 4. 앱 상태 확인 및 실행
```bash
# 8080 포트 사용 중인지 확인
lsof -i :8080
```
**사용 중이 아니면 백그라운드 실행:**
```bash
./gradlew bootRun &
```
서버가 응답할 때까지 대기 (최대 60초):
```bash
for i in $(seq 1 30); do
  curl -s -o /dev/null http://localhost:8080/ && break
  sleep 2
done
```
> actuator가 미설정일 수 있으므로 health 엔드포인트 대신 연결 성공 여부로 판단.

### 5. API 순차 호출
의존성을 고려해 순서대로 호출:
1. POST (생성) → 생성된 ID 기억
2. GET (목록/단건) → 생성된 ID 사용
3. PUT/PATCH (수정)
4. DELETE (삭제) → 마지막

```bash
curl -s -w "\nHTTP Status: %{http_code}\n" \
  -X {메서드} "http://localhost:8080{경로}" \
  -H "Content-Type: application/json" \
  [-d '{body}'] | jq .
```
> 인증이 필요한 API는 별도 안내 (토큰 미지원 — 현재는 안내만).

### 6. 결과 요약 보고
```
| API | 메서드 | 경로 | 상태코드 | 결과 |
|-----|--------|------|----------|------|
| 배너 목록 조회 | GET | /banners | 200 | ✅ 성공 |
```
실패한 API는 에러 메시지 분석 + 원인/수정 방향 제시.

## 주의사항
- 변경된 Controller가 없으면 "변경된 API가 없습니다" 출력
- 앱이 이미 실행 중이면 새로 실행하지 않음
- 응답은 `ApiResponse` 래퍼(`{success, code, message, data}`) 형태임을 감안해 판정
