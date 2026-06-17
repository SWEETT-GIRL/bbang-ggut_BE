---
name: make-plan
description: 구현 계획 수립. spec 작성 후 구현 전 단계 (빌트인 /plan과 구분되는 커스텀 스킬)
---

# Plan (구현 계획)

spec 기반으로 구체적인 구현 계획을 수립한다.

## 실행 프로세스

1. **현재 브랜치 확인 및 기존 문서 읽기**
   ```bash
   git branch --show-current
   ```
   - `.claude/planning/{브랜치명}.md`가 있으면 읽기, 없으면 spec부터 작성

2. **위험 식별**
   - 잠재 문제와 영향받는 다른 컴포넌트
   - 기존 기능 영향 여부

3. **단계별 분해**
   - 파일 경로, 클래스명 명시
   - 의존성 기반 구현 순서 (Command / Query 분리 고려)

4. **Plan 내용을 기존 문서에 추가**
   - `.claude/planning/{브랜치명}.md`의 "2. 구현 계획 (Plan)" 섹션 작성

5. **명시적 승인 대기**
   - AskUserQuestion으로 확인 (`yes` / `no` / `modify`)

**승인 없이 코드 작성 금지.**

## Plan 섹션 형식

```markdown
## 2. 구현 계획 (Plan)

### 위험 요소
- [ ] 기존 기능 영향 여부
- [ ] 마이그레이션 필요 여부
- [ ] 성능 영향 여부

### 구현 순서

#### Phase 1: Domain
| 순번 | 파일 | 변경 내용 |
|------|------|----------|
| 1-1 | `{domain}/entity/XxxEntity.java` | Entity 생성 |
| 1-2 | `{domain}/repository/XxxRepository.java` | Repository 생성 |

#### Phase 2: Application (Service)
| 순번 | 파일 | 변경 내용 |
|------|------|----------|
| 2-1 | `{domain}/service/XxxCommandService.java` | CommandService (쓰기) |
| 2-2 | `test/.../XxxCommandServiceTest.java` | 단위 테스트 |
| 2-3 | `{domain}/service/XxxQueryService.java` | QueryService (조회) |
| 2-4 | `test/.../XxxQueryServiceTest.java` | 단위 테스트 |

#### Phase 3: Presentation (Controller)
| 순번 | 파일 | 변경 내용 |
|------|------|----------|
| 3-1 | `{domain}/controller/XxxController.java` | Controller (ApiResponse) |
| 3-2 | `test/.../XxxControllerTest.java` | @WebMvcTest |

### 테스트 계획
- [ ] XxxCommandServiceTest (성공 + 예외)
- [ ] XxxQueryServiceTest (성공 + 예외)
- [ ] XxxControllerTest

### 승인 요청
위 계획대로 진행해도 될까요?
```

## 다음 단계

plan 승인 후:
- plan 순서대로 구현 진행
- 구현 완료 후 `/code-review` 실행 (권장)
