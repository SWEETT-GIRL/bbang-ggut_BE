# DB Migration

Flyway schema migration은 모든 profile에서 같은 순서로 적용되도록 `db/migration`에 둔다.

Profile별 초기/샘플 데이터가 필요하면 seed만 분리한다.

- `db/seed/local`: 로컬 개발 데이터
- `db/seed/staging`: 개발 서버용 데이터

운영 profile은 seed 경로를 추가하지 않는다.
운영 DB는 자동 migration을 실행하지 않도록 `spring.flyway.enabled=false`를 유지한다.
운영 스키마 변경은 별도 승인된 절차로 적용한 뒤 Hibernate `validate`로 검증한다.

로컬 seed를 적용하려면 Git에 올리지 않는 `application-local.properties`에서 아래처럼 경로를 명시한다.

```properties
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration,classpath:db/seed/local
```
