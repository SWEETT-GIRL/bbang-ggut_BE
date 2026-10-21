-- ShedLock 공식 스키마. 스케줄 작업 하나당 행 하나 — 이름(name)으로 잡고 lock_until 까지 다른 인스턴스는 건너뛴다.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
