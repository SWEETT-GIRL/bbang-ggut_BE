# PR 리뷰 반영 커맨드

PR $ARGUMENTS 의 리뷰를 확인하고 코드에 반영해줘.

## 진행 순서

### 1. PR 정보 확인
```bash
gh pr view $ARGUMENTS --json number,title,headRefName,state,reviews,comments
```

### 2. 리뷰 코멘트 조회
```bash
# 현재 저장소 정보 (owner/repo)
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)

# 리뷰 목록
gh api repos/$REPO/pulls/$ARGUMENTS/reviews

# 라인별 리뷰 코멘트
gh api repos/$REPO/pulls/$ARGUMENTS/comments
```

### 3. 브랜치 체크아웃
- PR의 headRefName 브랜치로 체크아웃 후 최신 상태로 pull

### 4. 리뷰 분석 및 반영
각 리뷰 코멘트에 대해:
- 파일 경로와 라인 번호 확인
- 지적 사항 이해 후 코드 수정
- **테스트 코드 수정이 필요하면 함께 수정** (mock 변경, 새 케이스 추가 등)
- 수정이 불필요하거나 논의가 필요하면 사용자에게 확인

### 5. 커밋 & 푸시
- 변경사항 스테이징
- 커밋 메시지는 **이모지 + 타입 접두어** 한글 (예: `♻️ refactor: PR 리뷰 반영 — 구독 중복 체크 로직 수정`)
- pre-commit 훅(spotless/checkstyle) 통과 확인
- 원격 브랜치에 푸시

### 6. PR에 리뷰 반영 결과 코멘트 (필수)
`gh pr comment $ARGUMENTS` 로 결과 코멘트 작성:

```markdown
## 리뷰 반영 완료

### 1. [리뷰 제목/요약]
- 반영 내용 설명
- 수정된 파일: `파일경로`

### 2. [리뷰 제목/요약]
- 반영 내용 설명
- 수정된 파일: `파일경로`
```

## 주의사항
- PENDING 상태의 리뷰는 무시 (미제출)
- APPROVED 리뷰의 코멘트도 확인 (사소한 수정 요청 가능)
- CHANGES_REQUESTED 리뷰의 코멘트는 필수 반영
- **[P1], [P2] 등 우선순위 태그가 붙은 코멘트는 필수 반영**
- 리뷰어 의도가 불분명하면 사용자에게 확인 후 진행
