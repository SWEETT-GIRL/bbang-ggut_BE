# OCI 운영 서버

운영은 OCI 인스턴스 한 대에서 돈다. 앱(blue/green) · PostgreSQL · Redis · Nginx 가 같은 호스트에 있다.

```
인터넷 ─443─▶ Nginx(호스트) ─127.0.0.1:8080|8082─▶ 앱(blue|green)
                                                   │  docker 네트워크 bbang-infra
                                                   ├─▶ postgres:5432
                                                   └─▶ redis:6379
```

| 무엇 | 어디 | 누가 관리 |
|---|---|---|
| 앱 | `~/app` · `docker-compose.prod.yml` | CD (`cd-prod.yml`) |
| PostgreSQL · Redis | `~/infra` · `docker-compose.infra.yml` | 사람이 한 번 띄운다. CD 는 건드리지 않는다 |
| Nginx · 인증서 | 호스트 패키지 | `deploy/oci/setup-nginx.sh` 로 한 번 세팅 |

## 서버 1회 세팅

순서를 지킨다 — 네트워크(`bbang-infra`)가 먼저 있어야 앱이 뜬다.

### 1. DB · Redis

```bash
mkdir -p ~/infra && cd ~/infra
# 저장소에서 두 파일을 가져온다: docker-compose.infra.yml, docker/postgres-lab.Dockerfile → postgres.Dockerfile
umask 077 && cat > .env   # POSTGRES_USER, POSTGRES_PASSWORD, REDIS_PASSWORD
docker compose -f docker-compose.infra.yml up -d --build
```

베이스 이미지가 첫 기동 때 `soboro` 에 운영에 없는 확장(postgis_tiger_geocoder · postgis_topology ·
fuzzystrmatch)과 search_path 를 넣는다. 운영과 맞추려면 복원 뒤에 걷어낸다.

```sql
DROP EXTENSION IF EXISTS postgis_tiger_geocoder; DROP EXTENSION IF EXISTS postgis_topology;
DROP EXTENSION IF EXISTS fuzzystrmatch; DROP SCHEMA IF EXISTS tiger; DROP SCHEMA IF EXISTS topology;
ALTER DATABASE soboro RESET search_path;
```

DB 데이터는 운영 DB 를 덤프해 복원한다. 2026-11-26 리허설 기준 덤프 294초 · 복원 158초.

```bash
pg_dump -Fc --no-owner --no-acl -f prod.dump ...        # 원본에서
pg_restore --no-owner --no-acl -j 2 -d soboro prod.dump # 이 서버에서
```

### 2. 인증서

AWS bastion 은 Route53 DNS-01 로 인증서를 받았다. 이 서버에는 그 IAM 역할이 없고, 도메인이
아직 AWS 를 가리키는 동안에는 HTTP-01 로도 받을 수 없다. 그래서 컷오버 전에 bastion 의
`/etc/letsencrypt` 를 통째로 옮겨 온다(심볼릭 링크가 있으므로 tar 로).

```bash
# bastion 에서
sudo tar czf letsencrypt.tgz -C /etc letsencrypt
# 이 서버에서
sudo tar xzf letsencrypt.tgz -C /etc
```

개인 키가 들어 있는 파일이다 — 옮긴 뒤 중간 사본을 지운다.

### 3. Nginx · 방화벽

```bash
sudo ./deploy/oci/setup-nginx.sh ubuntu
```

- 서버 방화벽(iptables)의 80/443 은 스크립트가 연다.
- **VCN 보안 목록의 80/443 은 OCI 콘솔에서 따로 연다.** 둘 다 열려야 바깥에서 닿는다.
- 초기 활성 색을 green 으로 적어 두므로 첫 배포는 blue(8080)로 올라간다. 그 전까지 Nginx 는 502 를 낸다.

### 4. GitHub 설정

Secrets

| 이름 | 값 |
|---|---|
| `OCI_HOST` · `OCI_SSH_USER` · `OCI_SSH_KEY` | 서버 IP(이름 말고 IP — 스모크 테스트가 이 값으로 접속 대상을 고정한다) · 계정 · 배포용 개인 키 |
| `OCI_DB_URL` | `jdbc:postgresql://postgres:5432/soboro` |
| `OCI_DB_USERNAME` · `OCI_DB_PASSWORD` | `~/infra/.env` 의 값과 같게 |
| `OCI_REDIS_PASSWORD` | `~/infra/.env` 의 값과 같게 |
| `OCI_S3_BUCKET` | Object Storage 버킷 이름 |
| `OCI_S3_ACCESS_KEY` · `OCI_S3_SECRET_KEY` | Customer Secret Key |

Variables

| 이름 | 값 |
|---|---|
| `OCI_S3_REGION` | `ap-tokyo-1` |
| `OCI_S3_ENDPOINT` | `https://<namespace>.compat.objectstorage.ap-tokyo-1.oraclecloud.com` |
| `PROD_AUTOCOMPLETE_CACHE_ENABLED` · `PROD_TRENDING_ENABLED` | 비워 두면 꺼짐 |
| `PROD_SCHEDULING_ENABLED` | **반드시 `true` 나 `false`.** 비어 있으면 배포가 멈춘다 |

이미지 저장소 값이 앱까지 가는 이름

| GitHub | 컨테이너 환경변수 | 앱 설정 키 |
|---|---|---|
| `OCI_S3_REGION` | `STORAGE_REGION` | `storage.region` |
| `OCI_S3_ENDPOINT` | `STORAGE_ENDPOINT` | `storage.endpoint` |
| `OCI_S3_BUCKET` | `STORAGE_BUCKET` | `storage.bucket` |
| `OCI_S3_ACCESS_KEY` · `OCI_S3_SECRET_KEY` | `STORAGE_ACCESS_KEY` · `STORAGE_SECRET_KEY` | `storage.credentials.access-key` · `secret-key` |
| (CD 가 `true` 로 고정) | `STORAGE_PATH_STYLE` | `storage.path-style-access` |
| `PROD_LAMBDA_CALLBACK_SECRET_KEY` | `IMAGE_CALLBACK_SECRET_KEY` | `image.callback.secret-key` |

이름에 남은 `S3` 는 회사가 아니라 접속 규격이다 — OCI Object Storage 를 S3 호환 API 로 부른다.
코드의 `external.aws` 패키지와 API·DB 의 `awsKey` 는 그대로다(패키지는 이전 뒤 별도 PR, `awsKey` 는 출시된 앱과의 계약).

`PROD_SCHEDULING_ENABLED` 는 예약 작업(`@Scheduled`) 전체의 스위치다. 같은 운영 데이터로 서버가 둘 떠 있는 동안
둘 다 켜져 있으면 푸시가 두 번 나간다(중복 실행을 막는 ShedLock 은 DB 가 다르면 서로를 막지 못한다).

- 이 서버가 아직 운영을 받지 않을 때(이전 전 검증): `false`
- 이 서버가 운영을 받는 순간부터: `true` 로 바꾸고 CD 를 다시 실행한다

꺼져 있으면 앱 기동 로그에 경고가 남고, 배포 알림에도 "⚠️ 꺼짐"으로 나온다.

`JWT_SECRET` · `ENCRYPT_SECRET_KEY` 는 **AWS 때와 같은 값을 그대로 쓴다.** 바뀌면 전원이 다시
로그인해야 하고(JWT), 암호화해 둔 이메일을 읽지 못한다(ENCRYPT).

배포용 SSH 키는 사람이 쓰는 키와 따로 만든다 — 공개 키만 서버의 `~/.ssh/authorized_keys` 에 넣는다.

## 알아둘 점

- **앱 포트는 `127.0.0.1` 에만 연다.** Docker 가 여는 포트는 호스트 방화벽(iptables INPUT)을 거치지
  않는다. 주소 없이 `8080:8080` 으로 쓰면 인터넷에 그대로 열린다.
- **구 색을 내릴 때 `--remove-orphans` 를 쓰지 않는다.** 앱이 붙는 네트워크를 DB · Redis 와 함께 쓴다.
- **Redis 는 캐시 전용이다**(디스크에 남기지 않음, 상한에 닿으면 버림). 메모 초안처럼 잃으면 안 되는
  데이터는 여기에 두지 않는다.
- **DB 백업은 직접 챙긴다.** RDS 자동 백업이 없다 — 아직 스크립트가 없다(TODO).
- 서버가 한 대라 이 호스트가 죽으면 전부 멈춘다.

## 되돌리기

AWS 로 되돌리려면 이 변경을 revert 한다 — AWS 용 시크릿(`PROD_*`)은 지우지 않고 남겨 두었다.
