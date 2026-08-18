# prod 단일 서버 전환 (ALB 제거 · bastion Nginx · WAS 1대)

브랜치: `chore/prod-single-server` · 커밋 `8add5d8f` (배포 파일)

## 결정 사항

| 항목 | 결정 |
|------|------|
| ALB | **삭제**. bastion이 Nginx로 TLS 종료 + 리버스 프록시 |
| 웹 계층 | **기존 bastion 겸용** (신규 인스턴스 없음) |
| WAS | 프라이빗 서브넷에 **was-1 1대만**. was-2 종료 |
| TLS | Let's Encrypt, **DNS-01 챌린지** (다운타임 0초) |
| 배포 | WAS 한 대 안에서 **blue-green** (bastion Nginx upstream 전환) |
| NAT GW | **유지** — WAS가 프라이빗에 남으므로 아웃바운드에 필요 |

## 실제 리소스 (2026-08-18 조사)

```
                         IGW igw-0b356756e076820a2
                                    │
  public-a  subnet-0868fd42becbbd0b2 (10.7.0.0/20, AZ-2a)
   ├─ bastion  i-03d13aa5e4df4beff  t3.small  10.7.9.128
   │           EIP 3.34.246.201 (eipalloc-0a3ca9a693de8d775)
   │           SG sg-0863b7f6cf5d7da9b "isg-sweetgirl-prod-webserver"
   ├─ NAT GW   nat-0fa5cf7e4d0f63f05  (EIP 15.165.14.133)
   └─ ALB ENI  EIP 54.180.214.27 (eipalloc-007c937c97b28ba10)

  public-c  subnet-0b9521489eb82d1a6 (10.77.16.0/20, AZ-2c)
   └─ ALB ENI  EIP 54.116.112.230 (eipalloc-043348e54d55be563)

  private-a subnet-0ec33d5bbb19cb5de (10.7.16.0/20, AZ-2a)  → NAT
   ├─ was-1   i-069b4d5f8ffbb1938  t3.medium  10.7.27.165
   │          SG sg-0600e6d2452067d9d "isg-sweetgirl-prod-was"
   └─ RDS ENI EIP 52.79.119.46 (퍼블릭 액세스 — 아래 보안 항목)

  private-c subnet-0078f9aaf3051fcd3 (10.77.0.0/20, AZ-2c)  → NAT
   └─ was-2   i-082ac4c4189166145  t3.medium  10.77.15.213   ← 종료 대상

  monitoring-private-a subnet-0f640e6b93607409a (10.7.32.0/20) → NAT
   └─ monitoring i-06b64d59533001ba8  10.7.32.74  (Prometheus)
      SG sg-063f83377c212084a
```

| 리소스 | 값 |
|---|---|
| VPC | `vpc-0803b59a5d5f68c51` (10.7.0.0/16 + 보조 10.77.0.0/16) |
| ALB | `alb-sweetgirl-prod` / `...loadbalancer/app/alb-sweetgirl-prod/880088370e56cfc4` |
| 타겟그룹 | `tg-sweetgirl-prod-was` / `...targetgroup/tg-sweetgirl-prod-was/88279da90257ebed` |
| ALB SG | `sg-0a1b5098fa0b57e9e` |
| Route53 존 | `Z0439460BK9FWESJV0HD` (bbang-ggut.cloud) |
| ACM(서울) | `...certificate/e8096c27-7fdc-482d-9de1-9661388da2eb` — ALB 전용 |
| CloudFront | `d1ks8r990zyykg` (cdn.bbang-ggut.cloud) — 오리진 **S3만**, ALB 무관 |
| RDS | `db-sweetgirl-prod` db.t4g.small 400GB, SG `sg-054aed7fda63b3c84` |

## 이미 갖춰져 있어 작업이 필요 없는 것

- bastion SG에 80·443이 이미 `0.0.0.0/0` 개방 (SG 이름부터 `prod-webserver`)
- bastion에 EIP 이미 부착
- WAS SG에 `10.7.9.128/32 → 8080` (bastion) 이미 허용 → **blue는 규칙 추가 불필요**
- bastion·was-1이 같은 AZ(2a) → AZ 간 전송료·지연 없음
- Route53 A가 ALB **alias** → TTL이 60초 고정, **사전 인하 불필요**

## 최종 아키텍처

```
사용자 ──HTTPS(443)──> bastion 3.34.246.201
                          ├─ Nginx  TLS 종료 + blue/green upstream 전환
                          └─ SSH :22
                                │ 10.7.27.165
                                ▼
                          was-1 (private-a)
                          ├─ blue   호스트 :8080  actuator :8081
                          └─ green  호스트 :8082  actuator :8083
                             (컨테이너 내부는 항상 8080 / 8081)
```

활성 색의 **단일 진실 공급원**은 bastion의
`/etc/nginx/conf.d/bbang-upstream.conf` 안 `# ACTIVE_COLOR=` 주석이다.

## 실행 런북

### Phase 1 — 사전 준비 ✅ 완료 (2026-08-18, 실사용자 영향 0)

| 항목 | 결과 |
|---|---|
| WAS SG 8082 인바운드 (bastion 10.7.9.128만) | `sgr-006966516a88ebff4` |
| WAS SG 8083 인바운드 (monitoring SG만) | `sgr-0f4eabe1e2e18a19d` |
| IAM 역할 | `role-sweetgirl-prod-bastion-certbot` |
| 인스턴스 프로파일 | `profile-sweetgirl-prod-bastion-certbot` → bastion (재시작 없음) |
| 정책 검증 | ACME TXT `allowed` / A 레코드 `implicitDeny` |
| bastion 패키지 | nginx 1.28.3, certbot 4.0.0, python3-certbot-dns-route53 |
| 인증서 | `/etc/letsencrypt/live/bbang-ggut.cloud/` 만료 2026-11-16 |
| Nginx 설정 | `/etc/nginx/conf.d/bbang-ggut.cloud.conf` |
| upstream | blue → 10.7.27.165:8080 (`# ACTIVE_COLOR=blue`) |
| 전환 스크립트 | `/opt/bbang/switch-upstream.sh` + sudoers `ubuntu` NOPASSWD |
| 갱신 | `authenticator = dns-route53`, `certbot.timer` active, 만료 30일 전 갱신 |

**전환 전 검증 결과** (DNS 미변경 상태에서 bastion 직접 호출):

```
curl --resolve bbang-ggut.cloud:443:3.34.246.201 https://bbang-ggut.cloud/menu-categories
  → 200, TLS 검증 0, 0.15s, 실제 앱 데이터 응답
HTTP 80  → 301 https 리다이렉트 정상
/actuator → 404 (외부 차단 정상)
```

참고: `ssl_stapling`은 제거했다 — Let's Encrypt가 OCSP 응답 제공을 중단해
켜도 동작하지 않고 기동 시 경고만 남는다.
참고: bastion의 ufw는 `inactive` — 보안그룹만으로 제어된다.

### Phase 2 — 전환 ✅ 완료 (2026-08-18 16:53 KST, 무중단)

| 항목 | 결과 |
|---|---|
| A 레코드 | ALB alias → `3.34.246.201` (TTL 60) |
| 체인지 ID | `/change/C1022782ZF90D45LEVSQ` — INSYNC |
| 권한 NS 조회 | `3.34.246.201` |
| 8.8.8.8 / 1.1.1.1 | 둘 다 즉시 반영 |
| 실제 도메인 호출 | 200, 접속 IP `3.34.246.201`, TLS 검증 0, 0.065s |
| 실사용자 트래픽 | okhttp(앱 클라이언트) 요청이 bastion nginx 로그에 유입 확인 |
| 갱신 리허설 | `certbot renew --dry-run` **성공** — IAM 역할로 갱신 동작 검증 |

**롤백 방법** (Phase 4-2 전까지 유효):
```bash
aws route53 change-resource-record-sets --hosted-zone-id Z0439460BK9FWESJV0HD \
  --change-batch '{"Changes":[{"Action":"UPSERT","ResourceRecordSet":{
    "Name":"bbang-ggut.cloud.","Type":"A",
    "AliasTarget":{"HostedZoneId":"ZWKZPGTI48KDX",
      "DNSName":"dualstack.alb-sweetgirl-prod-796965184.ap-northeast-2.elb.amazonaws.com.",
      "EvaluateTargetHealth":true}}}]}'
```

**관찰 메모** — ALB 요청량은 전환 전에도 5분당 0~5건 수준이었다.
트래픽이 매우 적은 서비스이므로 전환 리스크는 애초에 낮았다.
다만 이 트래픽 규모에 RDS $590/월·OpenSearch $284/월은 과다해 보인다 (Phase 5 별건).

### Phase 3 — 구 컨테이너 정리 ✅ 워크플로우로 자동화 (수동 작업 없음)

전환 직후 was-1에는 구 방식 컨테이너(compose 프로젝트 `app`, 이름 `bbang-ggut-be`)가
8080을 잡은 채 남는다. 새 워크플로우는 `bbang-blue`/`bbang-green` 프로젝트만 알기 때문에
이를 자기 것으로 인식하지 못하고, 그대로 두면 **2차 배포가 8080 충돌로 실패**한다.

`구 색 정리` 스텝에 아래를 추가해 첫 배포가 자동으로 치우게 했다 (커밋 `417ba648`):
```bash
ssh was "cd ~/app && docker compose -f docker-compose.yml down --remove-orphans 2>/dev/null || true"
```
트래픽이 신규 색으로 넘어간 **뒤에** 실행되므로 다운타임이 없고,
2차 배포부터는 대상이 없어 조용히 넘어간다.

### Phase 4 — 정리 (진행 중)

**4-1. was-2 타겟 등록 해제 ✅** (2026-08-18 17:1x KST)
- `deregistration_delay` 30초, draining 완료 후 타겟그룹에 was-1만 healthy
- 해제 후에도 ALB 직접 호출 200 정상 (was-1이 받음)

**4-2. was-2 중지 ✅** — `i-082ac4c4189166145` stopping

**4-3. ALB 트래픽 드레인 ✅**

> ⚠️ **정정**: 컷오버 직후에도 ALB로 실사용자 트래픽이 들어왔다. 최근 30분 `Target_2XX` 50건,
> bastion 52 / ALB 35로 갈렸다. 원인은 클라이언트 keep-alive 연결과 DNS 캐시.
> 그래서 was-2를 바로 중지하지 않고 **타겟 해제 → ALB는 was-1로 계속 서비스** 순서를 택했다.
> 실제 드레인은 예상보다 빨라 컷오버 20분 뒤 `Target_2XX`가 0으로 수렴했다.
> (잔존 `RequestCount`는 ALB IP를 직접 긁는 스캐너의 4xx였다.)

**삭제 전 점검 — 전부 통과**

| 항목 | 결과 |
|---|---|
| 실사용자 2xx | 0으로 수렴 |
| WebSocket·SSE·멀티파트 | 없음 — nginx keepalive 설정이 깨뜨릴 것 없음 |
| **Lambda 콜백 URL** | `https://bbang-ggut.cloud/callback/images/status` — 도메인 사용, ALB 주소 아님 |
| 레포 내 ALB DNS 하드코딩 | 없음 |

**4-4. ALB·타겟그룹 삭제 ✅** (사용자가 직접 수행)

**4-5. ALB EIP 회수 ✅** — ALB 삭제와 함께 정리됨 (목록에서 사라짐)

**4-6. WAS SG에서 ALB 참조 제거 ✅** — `sgr-05968fc2d193e20a5`(8081), `sgr-0a4fcf23d398a569a`(8080)
> 순서 주의: SG를 참조하는 규칙을 **먼저** 지워야 SG가 삭제된다 (아니면 `DependencyViolation`).
> bastion 경로는 별도 규칙(`10.7.9.128/32`)이라 영향 없음을 확인한 뒤 제거했다.

**4-7. ALB 보안그룹 삭제 ✅** — `sg-0a1b5098fa0b57e9e`

**최종 WAS SG 인바운드** (필요한 것만 남음)
```
  22   ← 10.7.9.128/32          bastion SSH
  8080 ← 10.7.9.128/32          bastion nginx → blue
  8082 ← 10.7.9.128/32          bastion nginx → green
  8081 ← sg-063f83377c212084a   Prometheus → blue actuator
  8083 ← sg-063f83377c212084a   Prometheus → green actuator
```

**남은 것**
- was-2 종료 — 현재 `stopped`(EBS 요금만). 종료는 되돌릴 수 없으므로 스냅샷 여부 결정 후
- 서울 ACM 인증서 삭제 여부 (ALB가 사라져 미사용 상태)
- GitHub Secrets `PROD_WAS_HOST_2` 삭제

### Phase 5 — 후속
**5-1.** Prometheus 스크랩 타겟을 `10.7.27.165:8081`·`:8083` 두 개로 (한쪽 down 허용)
**5-2.** RDS `PubliclyAccessible` 끄기 + EIP 52.79.119.46 회수
**5-3.** bastion SSH 22를 사무실/VPN IP로 제한 (현재 `0.0.0.0/0`)
**5-4.** monitoring SG SSH 22 정리 (현재 `0.0.0.0/0`)
**5-5.** `server.forward-headers-strategy=framework` 추가 검토
**5-6.** HSTS 주석 해제 (안정화 후)
**5-7.** 인증서 갱신 실패 Slack 알림
**5-8.** was-1 디스크 정리 — 6.7G 중 1.6G 여유(77% 사용). 이미지·로그 정리 필요
**5-9.** ALB 액세스 로그가 꺼져 있어 트래픽 출처 추적이 불가했다. 필요 시 활성화 검토

## 위험 요소

| 위험 | 완화 |
|------|------|
| bastion이 SPOF이자 웹서버 (t3.small) | 전환 후 CPU·메모리 관찰, 필요 시 타입 상향. EBS 스냅샷 확보 |
| SSH 관문과 공개 웹이 한 박스 | 5-3으로 SSH 인바운드 제한 |
| 구 컨테이너가 8080 점유 | Phase 3 반드시 수행 |
| DNS 캐시로 구 ALB 접속 지속 | alias TTL 60초 + ALB를 최소 하루 유지 |
| 인증서 자동 갱신 실패 | IAM 역할 유지 + certbot 타이머, 5-7 알림 |
| 배포 실패 | 구 색 스택이 살아있어 upstream만 되돌리면 복구 (워크플로우 자동 롤백) |
| 롤백 | Phase 4-2 전까지는 A 레코드를 ALB alias로 되돌리면 즉시 원복 |

## 조사 중 발견한 별건

- 🔴 **prod RDS가 `PubliclyAccessible: true`** — SG(`sg-054aed7fda63b3c84`)가 WAS·bastion SG로만
  5432를 허용해 실질 노출은 막혀 있으나, SG 실수 하나로 인터넷에 열린다. 5-2에서 처리.
- 🟡 **staging 다운으로 보임** — `bbang-ggut.site` A가 `54.116.12.200`(dev-webserver EIP)를
  가리키는데 그 인스턴스가 stopped. dev-was도 stopped.
- 🟡 dev 환경은 이미 `webserver + was` 2계층 구조 — prod도 같은 모양이 된다.
- ℹ️ Cost Explorer는 SCP로 차단(`ce:GetCostAndUsage` explicit deny) — 리소스별 실비용 조회 불가.
