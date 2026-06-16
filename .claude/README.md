# Claude Code 설정 가이드 (빵긋 BE)

이 프로젝트의 Claude Code 설정 사용법입니다.

## 구조

```
.claude/
├── ../CLAUDE.md   # 프로젝트 가이드 (항상 로드, 루트에 위치)
├── commands/      # 슬래시 커맨드 (/code-review 등)
├── skills/        # 자동 참조 스킬 (자연어로 호출)
└── planning/      # 작업별 spec/plan 저장 (브랜치명 단일 파일)
```

> 코드 컨벤션은 **`code-convention` 스킬**에 정리되어 있다. (DDD + CQRS 기준)

---

## 워크플로우

새 기능 개발 시:

```
"~~ 기능 구현해줘"  →  자동으로 spec → plan → 구현 순서 진행
```

| 단계 | 설명 |
|------|------|
| 1. Spec | 요구사항 명세. 모호한 점 질문 후 `.claude/planning/{브랜치명}.md` 생성 |
| 2. Plan | 구현 계획 수립. **사용자 승인 대기** |
| 3. 구현 | 승인 후 plan 순서대로 (Service → 테스트 → Controller → 테스트) |
| 4. 리뷰 | `/code-review`로 변경사항 점검 |

**팁:** 코드리뷰는 구현한 세션과 다른 새 세션에서 하면 더 객관적.

---

## Skills (자동 참조)

자연어로 요청하면 description 기반으로 자동 참조됩니다.

| 스킬 | 자동 적용 시점 |
|------|---------------|
| `code-convention` | Entity/Service/Controller/DTO/예외 작성 시 |
| `test-guide` | 테스트 코드 작성 시 |
| `spec` | 새 기능 요구사항 명세 시 |
| `plan` | 구현 계획 수립 시 |
| `git-workflow` | 커밋·브랜치·Worktree 관리 시 |

---

## 슬래시 커맨드

| 커맨드 | 설명 |
|--------|------|
| `/code-review` | 현재 브랜치 변경사항 코드 리뷰 (CONVENTIONS 기준) |
| `/api-test` | 변경된 API 자동 호출·검증 |
| `/guide` | 작업 결과 가이드 생성 (front/back/po) |
| `/review-apply` | PR 리뷰 코멘트 반영 |
| `/test-summary` | PR 테스트 커버리지 요약 |

---

## 커스터마이징

`.claude/skills/`에 `.md` 파일 추가:

```yaml
---
name: my-skill
description: 스킬 설명 (Claude가 자동 호출 판단에 사용)
---
```

**description이 중요** — Claude가 사용자 요청과 매칭해 스킬 로드 여부를 결정한다.
