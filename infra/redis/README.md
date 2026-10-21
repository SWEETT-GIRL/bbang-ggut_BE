# 운영 Redis 배포

대상: `ec2-sweetgirl-prod-redis-kafka-1`의 단일 Redis. Cluster 구성은 아니다.
WAS와 다른 EC2에 배포하며, 기존 운영 배포의 배스천을 경유한다.

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
   Redis 보안그룹: 22는 배스천 SG, 6379는 WAS SG만 허용한다.
4. EC2에서 Docker Hub로 접속 가능해야 한다. 퍼블릭 IP가 없다면 NAT 등 경로를 준비한다.
5. Redis 권장 호스트 설정 `vm.overcommit_memory=1`을 `/etc/sysctl.d/`에 저장하고 적용한다.

## GitHub Actions 설정

Settings → Secrets and variables → Actions에서 등록한다.

### 기존 Secret 재사용

- `PROD_BASTION_HOST`: 배스천 주소
- `PROD_SSH_USER`: 배스천 접속 사용자
- `PROD_SSH_KEY`: 배스천 SSH 개인 키. Redis 키를 별도로 지정하지 않으면 Redis에도 사용한다.

### 새 Secret

- `PROD_REDIS_HOST`: Redis EC2의 사설 IPv4 또는 내부 DNS
- `PROD_REDIS_PASSWORD`: 32자 이상, 줄바꿈 없는 무작위 비밀번호
- `PROD_REDIS_KNOWN_HOSTS`: 배스천과 Redis 서버의 확인된 SSH 공개 호스트 키를
  OpenSSH known_hosts 형식으로 등록한다. 각 줄의 호스트 필드는 위 Secret의 실제 주소와
  같아야 한다. 신뢰할 수 있는 콘솔/관리 경로에서 지문을 검증한 키를 사용한다.
- `PROD_REDIS_SSH_KEY`: Redis EC2가 기존 키 페어와 다를 때만 추가한다.

### 선택 Variable

- `PROD_REDIS_SSH_USER`: 기본 `ubuntu`

비밀번호 원문은 서버 파일이나 Compose 환경변수에 저장하지 않는다.
Actions에서 SHA-256 해시를 생성해 릴리스별 `users.acl`로 전달한다.
상태 확인용 health 계정은 비밀번호 없이 PING만 가능하며 데이터 접근은 불가능하다.
users.acl은 비밀번호 해시를 포함하므로 Git에 커밋하지 않는다.

## 동작과 데이터

- 공식 ARM64 이미지 `redis:7.4.11-bookworm` 사용. 버전 변경은 별도 검토 후 진행한다.
- Redis maxmemory 1GiB, 컨테이너 메모리 상한 1.5GiB. 실제 사용량을 보고 조정한다.
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
