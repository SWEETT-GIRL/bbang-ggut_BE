---
name: git-workflow
description: Git 워크플로우 가이드. 커밋, 브랜치 관리, Worktree 사용 시 참조
disable-model-invocation: true
---

# Git 워크플로우

## 커밋 규칙

- 커밋 메시지는 **한글**로 작성
- **이모지 + 타입 접두어**:
  - `✨ feat: 관리자 신고 상세 조회 API 추가`
  - `🧪 test: 관리자 신고 상세 조회 테스트 추가`
  - `📝 docs: 신고 운영 정책 문서화`
  - `♻️ refactor: 신고 상세 응답 변환 로직 정리`
- 의미 있는 단위로 커밋
- pre-commit 훅이 `spotlessApply` + `checkstyle`을 자동 실행 (실패 시 커밋 중단 → 이슈 수정 후 재시도)

## PR 규칙

- PR 제목은 **`[타입] 제목`** 형식 — 타입은 **대문자** (`FEAT`, `FIX`, `DOCS`, `REFACTOR`, `TEST` 등)
  - 예: `[FEAT] 관리자 신고 상세 조회 API`
  - 예: `[FIX] 구독 중복 저장 버그 수정`
- PR 본문은 **한글**로 작성

---

## 병렬 작업 (Git Worktree)

여러 기능을 동시에 개발할 때 Git Worktree를 활용한다.

### Worktree 생성

```bash
# 새 브랜치와 함께 worktree 생성
git worktree add ../bbang-ggut_BE-{기능명} -b feature/{기능명}

# 예시
git worktree add ../bbang-ggut_BE-payment -b feature/payment
```

### 폴더 구조

```
workspace/
├── bbang-ggut_BE/             # 원본 (기본 브랜치)
├── bbang-ggut_BE-payment/     # feature/payment 작업용
└── bbang-ggut_BE-search/      # feature/search 작업용
```

### 병렬 작업 방법

각 worktree 폴더에서 별도 Claude Code 세션 실행:

```bash
# 터미널 1
cd ../bbang-ggut_BE-payment && claude

# 터미널 2
cd ../bbang-ggut_BE-search && claude
```

### Worktree 정리

```bash
git worktree remove ../bbang-ggut_BE-payment
git worktree list
```

### 주의사항

- 같은 파일을 여러 worktree에서 동시 수정 금지
- 작업 완료 후 머지하고 worktree 삭제
