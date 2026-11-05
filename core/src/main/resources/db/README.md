# DB Migration

Flyway schema migration은 모든 profile에서 같은 순서로 적용되도록 `db/migration`에 둔다.
Migration 파일명은 timestamp 기반 컨벤션을 따른다. 자세한 규칙은 `docs/flyway-migration-naming-convention.md`를 참고한다.

Profile별 초기/샘플 데이터가 필요하면 seed만 분리한다.

- `db/seed/local`: 로컬 개발 데이터
- `db/seed/staging`: 개발 서버용 데이터

운영 profile은 seed 경로를 추가하지 않는다.
운영 DB의 migration은 v2 WAS가 배포 때 Flyway로 적용하고, 이후 Hibernate `validate`로 검증한다.
v1과 DB를 공유하는 동안 migration은 추가만 한다. 삭제·이름 변경·NOT NULL 추가는 v1이 쓰는 스키마를 깨므로 v1 종료 뒤에 한다.
이미 있는 파일은 수정하지 않고, 파일명은 현재 시각으로 짓는다. `Migration Guard` CI가 이 규칙을 검사한다.
v1 영향을 확인한 불가피한 변경만 PR 라벨 `migration-breaking-ok`로 넘길 수 있다. 수정·삭제와 버전 규칙은 라벨로도 넘어갈 수 없다.

로컬 seed를 적용하려면 Git에 올리지 않는 `application-local.properties`에서 아래처럼 경로를 명시한다.

```properties
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration,classpath:db/seed/local
```
