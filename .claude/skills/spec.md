---
name: spec
description: 요구사항 명세 작성. 새 기능 개발 시작 시 사용
---

# Spec (요구사항 명세)

기능 요구사항을 명확하게 정의한다.

## 실행 프로세스

1. **현재 브랜치 확인**
   ```bash
   git branch --show-current
   ```

2. **코드베이스 탐색** — `Task(subagent_type=Explore)`로 관련 파일 탐색 (메인 컨텍스트 오염 없이 결과만 반환)
   ```
   Task(subagent_type=Explore, prompt="user 도메인의 Controller/Service/Repository/Entity와 핵심 메서드를 파일 경로와 함께 요약")
   ```

3. **요구사항 명확화**
   - 모호한 점은 **반드시 질문**하고 확인 (AskUserQuestion)
   - 추측하지 않음

4. **spec 파일 저장**
   - **경로**: `.claude/planning/{브랜치명}.md`
   - Write 도구로 생성

## Spec 문서 형식

```markdown
# [기능명] Spec & Plan

## 1. 요구사항 (Spec)

### 핵심 기능
- 기능 설명

### 상세 동작
- API 명세 (메서드, URL, Request/Response, 에러)
- 비즈니스 규칙

### 관련 파일
- 수정/생성 대상 파일 목록 (Controller / Service / Repository / Entity / DTO)

### 결정 사항
- 논의 후 결정된 내용

### 제외 사항
- 이번 작업에서 하지 않는 것

---

## 2. 구현 계획 (Plan)

(plan 단계에서 작성)
```

## 다음 단계

spec 작성 완료 후:
- "플랜 작성해줘"로 구현 계획 수립 (권장)
- 또는 현재 세션에서 계속 진행
