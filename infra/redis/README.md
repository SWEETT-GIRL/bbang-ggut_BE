# 운영 Redis 배포

대상: Redis EC2 3대. 캐시 Redis(6379)와 메모 초안 Redis(6380)는 각각 마스터 1 · 복제본 1 이고,
두 Redis 를 같은 Sentinel 3개가 함께 감시한다(마스터 이름 `cache` · `draft`). Cluster 구성은 아니다.
WAS와 다른 EC2에 배포하며, 기존 운영 배포의 배스천을 경유한다.

| 서버 | 역할(`deploy.sh <role>` = compose profile) | 뜨는 것 |
| --- | --- | --- |
| `ec2-sweetgirl-prod-redis-kafka-1` (`PROD_REDIS_HOST`) | `master` | 캐시 redis 6379(마스터) · 초안 redis-draft 6380(마스터) · 감시자 26380 |
| `ec2-sweetgirl-prod-redis-2` (`PROD_REDIS_REPLICA_HOST`) | `replica` | 캐시 redis 6379(복제본) · 초안 redis-draft 6380(복제본) · 감시자 26380 |
| `ec2-sweetgirl-prod-redis-3` (`PROD_REDIS_SENTINEL_HOST`) | `sentinel` | 감시자 26380 |

## 실행 조건

| 변경 | 결과 |
| --- | --- |
| develop-v2에 infra/redis/** 또는 cd-redis-prod.yml 변경 | Redis 검증 후 Redis EC2에만 배포 |
| develop-v2 대상 Redis 변경 PR | Redis 설정 검증만 실행, Redis 배포는 하지 않음 |
| 백엔드 코드만 main에 머지 | 기존 WAS 배포만 실행 |
| develop-v2에서 WAS·Kafka 배포와 통합 | 팀원 워크플로에도 Redis 변경 제외 조건 및 중복 배포 여부 확인 필요 |
| Actions → CD - Redis Production → Run workflow → develop-v2 | Redis만 수동 배포 |

develop-v2의 ci-v2.yml은 가벼운 변경 감지 job을 실행하고, Redis 전용 변경이면 Java build job을
생략한다. cd-v2.yml도 Redis 설정과 배포 분리 설정만 바뀐 PR은 WAS 빌드·배포를 생략한다. 워크플로 전체를 경로 필터로 건너뛰어 필수 검사가 Pending으로 남는 것을 피한다.
Redis 검증을 새 필수 검사로 지정하려면 백엔드만 수정한 PR에서도 완료되는 집계 검사 등으로
구성해야 하므로 현재는 별도 필수 검사로 등록하지 않는다.

## EC2 최초 준비

1. Ubuntu ARM64에 Docker Engine과 Docker Compose v2를 설치한다.
   `--wait`, `--wait-timeout`을 지원하는 최신 Compose v2를 사용한다.
   https://docs.docker.com/engine/install/ubuntu/
2. 배포 사용자는 기본 `ubuntu`이며 sudo 없이 `docker info`가 실행되어야 한다.
   Docker 그룹 권한 추가 후에는 새 SSH 세션에서 확인한다.
3. 배스천에서 Redis EC2의 사설 IP로 SSH 접속이 되어야 한다.
   Redis 보안그룹: 22는 배스천 SG, 6379·6380은 WAS SG, **6379·6380과 26380은 Redis SG 자기 자신**(복제·감시자 투표),
   **26380은 WAS SG**(앱이 감시자에게 마스터 주소를 묻는다)를 허용한다. 세 서버 모두 같은 SG 를 쓴다.
4. EC2에서 Docker Hub로 접속 가능해야 한다. 퍼블릭 IP가 없다면 NAT 등 경로를 준비한다.
5. Redis 권장 호스트 설정 `vm.overcommit_memory=1`을 `/etc/sysctl.d/`에 저장하고 적용한다.

## GitHub Actions 설정

Settings → Secrets and variables → Actions에서 등록한다.

### 기존 Secret 재사용

- `PROD_BASTION_HOST`: 배스천 주소
- `PROD_SSH_USER`: 배스천 접속 사용자
- `PROD_SSH_KEY`: 배스천 SSH 개인 키. Redis 키를 별도로 지정하지 않으면 Redis에도 사용한다.

### 새 Secret

- `PROD_REDIS_HOST`: 마스터 서버(redis-kafka-1)의 **사설 IPv4**. 감시자 announce 주소로도 쓰여 DNS 는 안 된다
- `PROD_REDIS_REPLICA_HOST` / `PROD_REDIS_SENTINEL_HOST`: 복제본 서버, 감시자 전용 서버의 사설 IPv4
- `PROD_REDIS_REPLICATION_PASSWORD`: 32자 이상, 공백·줄바꿈 없는 무작위. 복제본과 감시자가 마스터에 붙을 때 쓰는
  **제한 계정(replica · sentinel)** 의 비밀번호다. 앱 비밀번호와 달라야 하며, 이것만 서버 파일에 평문으로 간다
- `PROD_REDIS_PASSWORD`: 32자 이상, 줄바꿈 없는 무작위 비밀번호
- `PROD_REDIS_KNOWN_HOSTS`: 배스천과 Redis 서버 **3대**의 확인된 SSH 공개 호스트 키를
  OpenSSH known_hosts 형식으로 등록한다. 각 줄의 호스트 필드는 위 Secret의 실제 주소와
  같아야 한다. 신뢰할 수 있는 콘솔/관리 경로에서 지문을 검증한 키를 사용한다.
- `PROD_REDIS_SSH_KEY`: Redis EC2가 기존 키 페어와 다를 때만 추가한다.

### 선택 Variable

- `PROD_REDIS_SSH_USER`: 기본 `ubuntu`

앱 비밀번호 원문은 서버 파일이나 Compose 환경변수에 저장하지 않는다.
Actions에서 SHA-256 해시를 생성해 릴리스별 `users.acl`로 전달한다. 계정은 넷이다 —
`default`(앱, 전체 권한) · `health`(PING만) · `replica`(복제 프로토콜만) · `sentinel`(Sentinel 최소 권한).
복제·감시 계정의 비밀번호 원문은 Redis 구조상 서버의 `replication.conf`(masterauth)와 `sentinel.conf`(auth-pass)에
들어간다 — 제한 계정이라 그 파일이 새도 앱 데이터는 읽을 수 없다.
users.acl · replication.conf · replication-cache.conf · 채워진 sentinel.conf 는 Git에 커밋하지 않는다.

## 동작과 데이터

- 공식 ARM64 이미지 `redis:7.4.11-bookworm` 사용. 버전 변경은 별도 검토 후 진행한다.
- 캐시 Redis maxmemory 512MB, 컨테이너 메모리 상한 768MiB. 복제본이 t4g.small(2GB)에 초안 Redis·감시자와 같이 뜨기 때문이다.
- 캐시 용도에 맞춰 `allkeys-lru` 사용. 세션, 큐, 락처럼 키 유실을 허용하지 않는 용도라면
  메모리 정책과 저장소 분리를 다시 설계한다.
- AOF everysec + RDB를 사용한다. AOF everysec는 장애 시 최근 약 1초 쓰기 유실 가능성이 있다.
- 데이터는 고정된 외부 Docker 볼륨 `sweetgirl-prod-redis-data`에 보관한다.
  컨테이너를 다시 만들어도 유지되지만 EC2/EBS 손실에 대한 백업은 아니다.
- 설정은 `~/redis/<commit>-<run>-<attempt>/`로 전달하며 성공한 릴리스는 `~/redis/current`로 가리킨다.
- 설정 파일만 바뀌어도 재생성하여 적용하므로 잠깐 접속 중단이 발생한다.
- 다운로드 실패 시 기존 컨테이너는 유지한다. 기동/상태 확인 실패 시 이전 성공 릴리스로 복구를 시도한다.
  첫 배포는 복구 대상이 없으며, 이미지 버전 변경으로 데이터 형식이 바뀌면 자동 복구가
  보장되지 않는다. 버전 업그레이드 전 별도 백업과 호환성 검증이 필요하다.
- 배포 시 volume 삭제, compose down, 이미지 일괄 정리는 실행하지 않는다.
  오래된 릴리스는 current와 복구에 필요한 릴리스를 보존하며 추후 정리한다.

## 메모 초안 Redis

- `redis-draft`는 6380. 저장 전 글의 원본이라 `noeviction`이고, maxmemory 256MB, 컨테이너 상한 384MiB다. 캐시와 볼륨도 나눈다.
- 비밀번호는 캐시와 같은 `users.acl`을 쓴다. 앱은 `COURSE_DRAFT_REDIS_*`로 연결한다.
- **Sentinel**: 마스터 이름 `draft`, quorum 2, 5초 무응답이면 승격(`sentinel.conf`). `min-replicas-to-write`는 켜지 않는다 —
  복제본이 1대라 켜면 복제본 하나만 죽어도 모든 쓰기가 막힌다. 유실 0은 앱의 재전송 약속이 맡는다(설계 결정 12).
- 감시자는 host 네트워크로 떠서 서버 IP 를 서로에게 알린다. 비밀번호는 없고 보안그룹(26380)으로만 막는다.
- 초안 Redis 설정 파일은 읽기 전용 마운트라 Sentinel 의 `CONFIG REWRITE` 는 실패한다. 승격·강등(REPLICAOF)은 그대로 적용되고,
  컨테이너가 다시 뜨면 `replication.conf` 의 역할(마스터 서버 = 마스터)로 시작했다가 Sentinel 이 현재 마스터에 맞춰 곧 바로잡는다.
  그 몇 초 동안 앱은 감시자가 가리키는 마스터에만 쓰므로 엇갈린 쓰기는 생기지 않는다.
- 배포 순서는 마스터 → 복제본 → 감시자 전용. 마스터 배포는 redis-draft 를 재생성해 몇 초 503 이 나고, 앱은 PATCH 로 물러난다.
- 서버 1대로 돌아가려면 Secret 을 지우는 게 아니라 WAS 쪽 `COURSE_DRAFT_REDIS_MODE` 를 `standalone` 으로 되돌리면 된다. Redis 쪽은 그대로 둬도 된다.

### WAS 를 Sentinel 로 돌리기 (Parameter Store `/sweetgirl/prod/v2/was/env/`)

| 키 | 값 |
| --- | --- |
| `COURSE_DRAFT_REDIS_MODE` | `sentinel` |
| `COURSE_DRAFT_REDIS_SENTINEL_MASTER` | `draft` |
| `COURSE_DRAFT_REDIS_SENTINEL_NODES` | `마스터IP:26380,복제본IP:26380,감시자IP:26380` |
| `COURSE_DRAFT_REDIS_PASSWORD` | 그대로(앱 계정 `default`) |

`HOST`·`PORT` 는 sentinel 모드에서 읽지 않는다. 바꾼 뒤 v2 를 한 번 배포해야 반영된다.

## 캐시 Redis Sentinel

- 초안과 같은 방식이다. CD 가 서버마다 `replication-cache.conf`(포트 6379)를 만들고, 복제본 서버만 `replicaof` 로 시작한다.
- 감시자 설정의 `sentinel monitor cache` 가 마스터 이름 `cache`, quorum 2, 5초 무응답 승격이다.
- 캐시는 유실돼도 DB 로 다시 채우므로 `min-replicas-to-write` 는 켜지 않는다.

### WAS · 배치를 Sentinel 로 돌리기

Spring Boot 기본 Redis 연결을 쓰므로 환경변수만 넣으면 된다. `SPRING_DATA_REDIS_SENTINEL_MASTER` 가 있으면 `REDIS_HOST` 는 읽지 않는다.

| 키 (v2 WAS `/sweetgirl/prod/v2/was/env/`, 배치도 같은 이름) | 값 |
| --- | --- |
| `SPRING_DATA_REDIS_SENTINEL_MASTER` | `cache` |
| `SPRING_DATA_REDIS_SENTINEL_NODES` | `마스터IP:26380,복제본IP:26380,감시자IP:26380` |
| `REDIS_PASSWORD` | 앱 계정(`default`) 비밀번호 |

**빈 값으로 만들지 않는다.** 키가 있는데 비어 있으면 Spring 이 Sentinel 모드로 뜨려다 실패한다. 안 쓸 때는 키를 지운다.
WAS SG 와 배치 SG 에서 Redis SG 의 26380 으로 가는 길이 열려 있어야 한다.

### 재배포와 페일오버

페일오버로 복제본 서버가 마스터가 된 뒤 Redis CD 가 다시 돌면, 감시자는 템플릿을 새로 복사하고 각 Redis 는
`replication*.conf` 의 처음 역할로 뜬다. 그래서 **처음 구성(마스터 서버 = 마스터)으로 되돌아간다.**
로컬 실험(Docker, 이 폴더의 설정 그대로)에서 승격 7초, 재배포 뒤 처음 구성 복귀와 승격 중 쓴 키 보존을 확인했다.
다만 마스터 서버 배포와 복제본 서버 배포 사이에 승격된 쪽에 쓴 값은, 복제본 서버가 다시 동기화하면서 사라질 수 있다.

## 첫 배포 확인

Secret 등록 → 변경 사항을 develop-v2에 반영 → Actions의 Redis 배포 성공 여부 확인.
서버에서 다음으로 상태를 확인할 수 있다.

```bash
cd ~/redis/current
docker compose ps
docker compose logs --tail 100 redis
```

WAS 연결은 별도 작업이다. 현재 WAS Compose는 REDIS_HOST, REDIS_PASSWORD를 전달하지 않는다.
WAS에 해당 환경변수를 연결하고 자동완성 캐시 등의 사용 스위치를 켜려면 기존 WAS 배포가
한 번 필요하다. 이후 Redis 설정만 바꾸는 배포는 WAS 배포를 실행하지 않는다.

기존 운영 CD의 Slack 알림은 이 워크플로에 추가하지 않았다.
