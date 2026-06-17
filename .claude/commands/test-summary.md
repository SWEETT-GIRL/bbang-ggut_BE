# 테스트 커버리지 요약 커맨드

PR $ARGUMENTS 의 테스트 파일을 분석하고 테스트 커버리지 요약을 PR에 코멘트로 작성해줘.

## 진행 순서

### 1. PR 정보 확인
```bash
gh pr view $ARGUMENTS --json number,title,headRefName,files
```

### 2. 브랜치 체크아웃
- PR의 headRefName 브랜치로 체크아웃 후 최신 상태로 pull

### 3. 변경된 파일 분석
- 변경된 main 코드 파일 목록 확인
- 변경된 테스트 파일 목록 확인
- 새로 추가된 기능/서비스 파악

### 4. 테스트 파일 분석
변경/추가된 테스트 파일을 읽고 검증 시나리오 파악:
- `@DisplayName` / `@Nested` 구조 분석
- 정상 케이스와 예외 케이스 구분
- Mockito `given`/`then` 설정으로 검증 동작 파악
- 계층별(서비스 단위 / `@WebMvcTest` / `@DataJpaTest`) 구분

### 5. PR에 요약 코멘트 작성
`gh pr comment $ARGUMENTS` 로 아래 형식 코멘트 작성:

```markdown
## 테스트 커버리지 요약

### Service 테스트
| 서비스 | 테스트 시나리오 |
|--------|----------------|
| **XxxCommandService** | 생성 성공, 중복 시 예외 |
| **XxxQueryService** | 조회 성공, 없을 때 예외 |

### Controller 테스트 (@WebMvcTest)
| 컨트롤러 | 테스트 시나리오 |
|----------|----------------|
| **XxxController** | API 정상 호출, validation 실패 |

### 검증된 예외 케이스
- `XxxException.NotFound`: 발생 조건 설명
```

## 주의사항
- 테스트 파일이 없는 PR은 테스트 추가 필요 여부 언급
- 테스트 시나리오는 한글로 간결하게 요약
- 중요한 엣지 케이스(0, 음수, 최대값, null, 권한) 누락 시 언급
