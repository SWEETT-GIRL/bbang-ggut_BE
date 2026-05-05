# Writing Plans

다단계 개발 작업을 위한 상세한 구현 계획을 작성한다.

## 핵심 목적

코드베이스 컨텍스트가 전혀 없다고 가정하고 상세하고 실행 가능한 계획을 생성한다.
각 계획은 파일을 매핑하고, 작업을 2~5분 단위로 분해하며, TDD 원칙과 빈번한 커밋을 따른다.

## 계획 구조

```markdown
# Feature: [기능명]
**Goal:** [목표]
**Architecture:** [아키텍처 설명]
**Tech Stack:** Spring Boot 4.0.6, Java 17, JUnit 5, Lombok

## Task 1: [작업명]
### Files
- Create: `src/test/java/sweetgirl/croissant/.../XxxTest.java`
- Create: `src/main/java/sweetgirl/croissant/.../Xxx.java`

### Steps
1. 실패하는 테스트 작성 (구체적 코드 포함)
2. 테스트 실행 → 실패 확인: `./gradlew test --tests "...XxxTest"`
3. 최소 구현 코드 작성
4. 테스트 실행 → 통과 확인
5. 커밋: `git commit -m "feat: ..."`
```

## 필수 요건

- **플레이스홀더 금지** — "TBD", "~와 유사하게" 같은 표현 사용 불가
- 모든 단계에 **실제 코드**, **정확한 명령어**, **예상 출력** 포함
- 파일은 **단일 책임 원칙** 준수, 기능별로 분리
- 기존 코드베이스 패턴 따르기

## 저장 위치

`docs/superpowers/plans/YYYY-MM-DD-<기능명>.md`

## 자기 검토 게이트

계획 완성 후 확인:
- [ ] 스펙의 모든 요구사항이 태스크에 매핑됨
- [ ] 플레이스홀더 언어 없음
- [ ] 함수명/시그니처가 일관됨

완성 후 실행 방식 선택지 제시: 서브에이전트 or 인라인 실행
