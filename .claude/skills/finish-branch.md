---
name: finish-branch
description: 브랜치 마감. 구현 완료 후 검증(spotlessApply/build/test) → 최종 체크리스트 점검 → 머지/PR 판단 → PR 본문 초안 → worktree 정리까지 한 번에.
disable-model-invocation: true
allowed-tools: Bash(./gradlew *) Bash(git status *) Bash(git diff *) Bash(git log *)
---

# Finish Branch (브랜치 마감)

구현이 끝난 브랜치를 검증하고 마감 판단까지 돕는다. **사용자가 명시적으로 호출**(`/finish-branch`).

## 실행 프로세스 (순서 게이트)

1. **변경 확인**
   ```bash
   git status --short
   git diff --stat
   ```

2. **검증 실행** — 순서대로, **실패 시 즉시 중단하고 원인 보고** (그 상태로 마감 진행 금지)
   ```bash
   ./gradlew spotlessApply
   ./gradlew build
   ./gradlew test
   ```

3. **최종 체크리스트 점검** (CLAUDE.md 기준)
   ```
   □ 새로 만든 모든 Service에 테스트 파일이 있는가?
   □ 새로 만든 모든 Controller에 테스트 파일이 있는가?
   □ 추가한 모든 public 메서드에 테스트 케이스가 있는가?
   □ 예외 케이스(Exception)에 대한 테스트가 있는가?
   ```
   - 누락 발견 시 마감 보류하고 보완 제안

4. **(권장) `/code-review` 통과 여부 확인** — CRITICAL 이슈가 남아 있으면 마감 보류

5. **머지 vs PR 판단** — 변경 규모·리스크를 요약하고 "바로 머지 가능 / PR 권장"을 제안

6. **PR 본문 초안** (`git-workflow` 규칙)
   - 제목: `[타입] 제목` — 타입 **대문자**(`FEAT`/`FIX`/`DOCS`/`REFACTOR`/`TEST`)
   - 본문: **한글**

7. **worktree 정리 안내** — worktree에서 작업했다면
   ```bash
   git worktree remove ../bbang-ggut_BE-{기능명}
   ```

## 금지

- 검증(build/test) 실패 상태로 마감 진행
- **사용자 승인 없이 커밋 / 푸시 / 머지 실행**

## 다음 단계

PR 생성 후 → 다른 새 세션에서 `/code-review`로 객관적 최종 리뷰 (권장)
