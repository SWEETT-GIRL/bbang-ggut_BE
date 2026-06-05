# Flyway Migration Script Naming Convention

## 기본 원칙

팀 개발에서는 PR과 브랜치가 동시에 올라올 수 있으므로 단순 순번 대신 한국 시간 기준 timestamp 버전을 사용한다.

```text
V{yyyyMMddHHmmss}__{action}_{target}.sql
```

예시:

```text
V20260605143000__create_moderation_appeal.sql
V20260605154510__add_status_to_user_report.sql
V20260606102030__create_member_restriction.sql
V20260606113045__add_index_to_review_created_at.sql
```

## 네이밍 규칙

- `V`는 Flyway versioned migration prefix로 고정한다.
- 날짜와 시간은 한국 시간 기준 `yyyyMMddHHmmss`를 사용한다.
- 버전과 설명 사이에는 반드시 `__` double underscore를 사용한다.
- 설명은 소문자 `snake_case`로 작성한다.
- 설명은 `{action}_{target}` 형태로 작성한다.
- 이미 DB에 적용된 migration 파일은 수정하거나 이름을 바꾸지 않는다.

## Action 이름

주로 아래 동사를 사용한다.

| Action | 용도 |
| --- | --- |
| `create` | 테이블, 시퀀스 등 생성 |
| `add` | 컬럼, 인덱스, 제약조건 추가 |
| `alter` | 컬럼 타입/속성 변경 |
| `drop` | 테이블, 컬럼, 제약조건 삭제 |
| `rename` | 테이블명, 컬럼명 변경 |
| `seed` | 초기 데이터 추가 |

## 파일명 예시

```text
V20260605143000__create_user_report.sql
V20260605144000__add_status_to_user_report.sql
V20260605145000__add_index_to_user_report_created_at.sql
V20260605150000__rename_report_reason_column.sql
V20260605151000__seed_default_report_reasons.sql
```

## 생성 위치

Versioned migration 파일은 아래 경로에 둔다.

```text
src/main/resources/db/migration/
```

Profile별 seed 파일은 migration과 분리해서 관리한다. 자세한 경로 정책은 `src/main/resources/db/README.md`를 참고한다.

## 주의사항

- 같은 timestamp를 사용하는 파일이 생기지 않도록 파일 생성 직전에 현재 한국 시간을 기준으로 버전을 정한다.
- 같은 PR 안에서 여러 migration을 만들 때는 실제 생성 순서대로 timestamp를 증가시킨다.
- 이미 적용된 migration의 SQL 내용이나 파일명을 변경해야 하는 경우에는 기존 파일을 수정하지 않고 새 migration을 추가한다.
