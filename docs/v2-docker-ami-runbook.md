# v2 WAS Docker 선설치 AMI 전환 runbook

v2 WAS 는 ASG `sweetgirl-was-auto-scaling`(t4g.small, ARM64) 이 시작 템플릿 `lt-005c5f2006720a57c` 으로 띄운다. 배포(`cd-v2.yml` → `scripts/deploy/v2/deploy.py`)는
digest 를 고정한 user_data 로 시작 템플릿 **새 버전**을 만들고 Instance Refresh 로 EC2 를 교체한다. 새 EC2 는 **일반 Ubuntu ARM64 AMI** 로 부팅해
user_data 에서 `apt-get install docker.io python3-boto3` 를 한 뒤 `bootstrap.py` 가 Parameter Store 를 읽어 컨테이너를 띄운다.

이 문서는 그 AMI 를 **Docker 와 python3-boto3 가 미리 들어간 AMI** 로 바꾸는 절차다. 2026-11-09 에 첫 AMI `ami-025ac2e24452a97b6` 를 만들어 운영에 적용했다(측정 §3.1, 이력 §4).
수치는 2026-11-09 cd-v2 로그·콘솔 조회 기준이며 "기대"라고 적은 값은 추정이다.

```
AMI 전: EC2 시작 ─▶ Ubuntu 부팅 ─▶ user_data: apt-get update/install docker.io python3-boto3 (60~90초) ─▶ bootstrap.py ─▶ readiness ─▶ ALB healthy
AMI 뒤: EC2 시작 ─▶ Ubuntu+Docker 부팅 ─▶ user_data: docker·boto3 있음 → apt 건너뜀 (수초)           ─▶ bootstrap.py ─▶ readiness ─▶ ALB healthy
```

## 0. 왜 지금, 왜 이 순서

- 2026-11-09 에 타깃그룹 등록 해제 지연(300→30초)·헬스체크(10초×2회)·`InstanceWarmup`(300→90초)을 바꿔 deploy 스텝이 **714초 → 283초**가 됐다.
  남은 병목은 새 EC2 가 healthy 가 되기까지 **약 170초**이고, 그중 부팅 때 apt 로 Docker 를 설치하는 **60~90초**가 가장 크다.
- 지금껏 미룬 이유: AWS 리소스를 레포 밖에서 손으로 만들지 않으려는 설계(#964)와 AMI 패치 주기를 팀이 떠안게 되는 점. 둘 다 유효하므로 §1 에서 선택지별로 따진다.
- **코드가 먼저다.** `deploy.py` 의 `user_data()` 는 이제 `command -v docker` 와 `python3 -c 'import boto3'` 가 모두 성공하면 apt 설치를 건너뛴다(`scripts/deploy/batch/deploy.py` 의
  `install_script` 와 같은 패턴). 그래서
  - AMI 를 바꾸기 **전**에도 지금처럼 설치하고,
  - AMI 를 바꾼 **뒤**에는 설치를 건너뛰며,
  - 이전 템플릿 버전(일반 Ubuntu AMI)으로 롤백해도 그대로 뜬다.
  이 코드가 `develop-v2` 에 머지·배포된 뒤에 §2 를 한다. 순서를 바꾸면 실패하진 않지만(이미 설치된 패키지에 `apt-get install` 은 "최신입니다"로 끝난다) `apt-get update` 10~20초는 매번 돈다.

## 1. 선택지 비교

| 항목 | **A. 수동 골든 AMI (추천)** | B. EC2 Image Builder 파이프라인 | C. Docker 가 이미 든 공개 AMI |
|---|---|---|---|
| 무엇을 하나 | 현재 AMI 로 EC2 한 대 띄워 `apt-get install docker.io python3-boto3` → 이미지 생성 | 부모 이미지(Ubuntu arm64) + 컴포넌트(패키지 설치) 레시피를 파이프라인이 주기적으로 빌드·테스트해 AMI 를 낸다 | ECS 최적화 AMI(AL2023 arm64) 등 Docker 가 든 AMI 의 ID 를 그대로 쓴다 |
| 레포 밖에 생기는 리소스 | AMI 1 + EBS 스냅샷 1 (임시 EC2 는 끝나면 종료) | 컴포넌트·이미지 레시피·인프라 구성·배포 구성·파이프라인 5개, 빌드용 IAM 역할·인스턴스 프로파일, (선택) 로그 S3 — 그리고 세대마다 AMI·스냅샷 | 없음(남의 AMI ID 참조) |
| 첫 작업 시간 | 30~40분(콘솔) | 2~3시간(역할·서브넷·테스트 컴포넌트·알림 포함) | 하루 이상 — 아래 "코드 변경" 참조 |
| ARM64(t4g.small) | 지금 쓰는 AMI 를 그대로 베이스로 쓰므로 문제 없음 | 지원. 부모 이미지 `ubuntu-server-*-lts-arm64`, 빌드 인스턴스를 t4g 로 지정 | ECS 최적화 AMI 는 arm64 있음. Marketplace Docker AMI 는 제각각 |
| 패치 주기(누가 떠안나) | **사람**. 분기 1회 또는 Ubuntu 베이스 AMI 갱신·docker.io/containerd 보안 공지 때 §2 반복. 그 사이 보안 패치는 Ubuntu 기본 `unattended-upgrades` 가 부팅 뒤 백그라운드로 받는다(커널·docker.io 메이저 업은 AMI 재생성 때) | **파이프라인**. 일정(예: 월 1회)으로 자동 빌드. 단 자동인 건 "새 AMI → 시작 템플릿 새 버전"까지이고, ASG 가 그 버전을 가리키게 하는 것과 배포는 사람이 한다(§6) | AWS/벤더 주기. 우리가 정하지 못하고, AMI ID 가 바뀔 때마다 시작 템플릿을 따라 바꿔야 한다 |
| AMI ID 갱신 절차 | §2.4~2.6: 시작 템플릿 새 버전 → ASG 버전 지정 → cd-v2 Re-run. 매번 수동 | 배포 구성의 "시작 템플릿 구성"이 새 버전을 자동 생성(기본 버전 지정 옵션). ASG 버전 지정·Re-run 은 수동 | A 와 같은 수동 절차 + 아래 코드 변경 |
| user_data / bootstrap 코드 변경 | **없음**(조건부 설치만, 이미 반영) | 없음 | **큼**: `apt-get` → `dnf`, `python3-boto3` 패키지 없음(pip 설치 필요), ECS 에이전트가 부팅마다 클러스터 등록을 시도해 로그·IAM 오류, `/usr/bin/python3`·uid 10001·systemd 유닛 경로 재검증. 설계 전제(Ubuntu)가 깨진다 |
| 위험 | 재현성이 이 문서와 AMI 태그에 달린다. 실수 지점: 임시 EC2 에 원래 user_data 를 그대로 둬 운영 컨테이너가 뜸(§2.2), 소스 템플릿 버전을 잘못 고름(§2.4), `cloud-init clean` 누락 | 리소스 5개의 수명·IAM 범위 관리, 파이프라인 실패 알림이 없으면 조용히 낡은 AMI 를 쓴다, 빌드 서브넷에 아웃바운드가 있어야 한다 | 벤더 종속, Marketplace 약관·과금, AL2023 기준 재작업 전부가 운영 경로에 들어간다 |
| 비용 | 스냅샷 ~8 GiB 저장(월 1달러 미만) + 임시 EC2 수십 분 | 빌드·테스트 인스턴스 실행 시간(회당 20~30분) + 세대 수만큼의 스냅샷. 서비스 자체는 무료 | 0 또는 Marketplace 시간당 요금 |
| 언제 고르나 | **지금.** 패키지 2개를 넣는 일이고 갱신은 분기 1회면 충분하다 | AMI 갱신이 월 1회 이상 반복되거나, 레시피에 더 넣을 것(이미지 레이어 선 pull, 에이전트, CIS 강화)이 생길 때 — A 의 AMI 를 그대로 첫 레시피로 옮기면 된다 | 고르지 않는다 |

**추천: A.** 바꾸는 것이 "시작 템플릿의 ImageId 한 칸"뿐이고, user_data 가 조건부라 AMI 에 문제가 있어도 이전 버전으로 돌아가면 끝난다. B 는 A 로 두세 번 갱신해 보고 손이 많이 가면 그때 간다.

## 2. 추천안(A) 콘솔 절차

리전 `ap-northeast-2`, 계정 `210447604031`. 필요한 권한: EC2(인스턴스 시작·AMI 생성·시작 템플릿 버전 생성), Auto Scaling(그룹 편집), SSM Session Manager(검증, 선택), GitHub Actions Re-run.
**배포(Instance Refresh)가 진행 중이면 하지 않는다** — `preflight()` 가 막기도 하지만, 중간에 ASG 버전을 바꾸면 진행 중인 교체와 섞인다.

### 2.1 현재 값 적어 두기 (읽기만)

1. EC2 → Auto Scaling 그룹 → `sweetgirl-was-auto-scaling` → 세부 정보 → 시작 템플릿: `lt-005c5f2006720a57c`, **버전 N**(숫자). `deploy.py` 가 배포 때마다 숫자로 고정하므로 숫자여야 정상이다. `$Latest`/`$Default` 로 보이면 누군가 손댄 것이니 시작 템플릿의 최신 버전 번호를 N 으로 쓴다.
2. EC2 → 시작 템플릿 → `lt-005c5f2006720a57c` → 버전 N 에서 적어 둔다: **AMI ID(베이스)**, 인스턴스 유형 `t4g.small`, IAM 인스턴스 프로파일, 보안 그룹 `sg-00c0052096ed30815`, 메타데이터(IMDSv2 필수, 응답 홉 제한 2), 네트워크 인터페이스 없음, 사용자 데이터 있음.
3. EC2 → 타깃 그룹 → `tg-sweetgirl-prod-was-v2`: 등록된 인스턴스가 모두 healthy 인지(§2.6 의 preflight 조건).

### 2.2 임시 EC2 에 Docker·boto3 설치

1. 시작 템플릿 `lt-005c5f2006720a57c` → 작업 → **템플릿에서 인스턴스 시작** → 소스 템플릿 버전 **N**. 템플릿 값(AMI·유형·SG·IAM 프로파일·IMDSv2)을 그대로 쓰면 운영과 같은 아웃바운드 경로로 apt 가 되는지도 함께 확인된다.
   템플릿에는 서브넷이 없어(ASG 가 정한다) 그대로 시작하면 **"기본 VPC 없음"** 오류가 난다. 네트워크 설정에서 서브넷을 ASG 와 같은 `subnet-0078f9aaf3051fcd3` 으로 고른다 — 보안 그룹·IAM 프로파일·IMDSv2 는 템플릿 값이 그대로 남는다(2026-11-09 실측).
2. 고급 세부 정보 → **사용자 데이터를 아래로 교체한다.** 원래 user_data 를 두면 `bbang-v2` 서비스가 운영 Parameter Store 를 읽어 **운영 DB 에 붙는 컨테이너를 띄운다** — 반드시 교체.
   ```bash
   #!/bin/bash
   set -euo pipefail
   export DEBIAN_FRONTEND=noninteractive
   apt-get update -qq
   apt-get install -y docker.io python3-boto3
   systemctl enable --now docker
   apt-get clean
   ```
3. 태그 `Name=bbang-v2-ami-builder-YYYYMMDD`. ASG 밖에서 띄운 인스턴스라 타깃그룹에는 등록되지 않는다.
4. 시작 후 2~3분 뒤 접속해 확인한다. 접속은 SSM Session Manager(인스턴스 역할에 `AmazonSSMManagedInstanceCore` 가 있을 때) 또는 bastion SSH. 세션 대신 Systems Manager → Run Command(`AWS-RunShellScript`)로 4·5 의 명령을 보내도 된다(2026-11-09 는 이 방식). 둘 다 안 되면 EC2 콘솔 → 모니터링 및 문제 해결 → **시스템 로그**에서 cloud-init 종료 줄만 확인한다.
   ```bash
   cloud-init status --wait                       # status: done
   docker --version && systemctl is-enabled docker   # enabled
   python3 -c 'import boto3; print(boto3.__version__)'
   dpkg-query -W docker.io python3-boto3           # §4 이력 표에 적을 버전
   ```
5. 이미지로 굳히기 전 정리(AWS 골든 AMI 관행). `cloud-init clean` 은 이 AMI 로 뜨는 EC2 가 "첫 부팅"으로 인식돼 배포 user_data 를 다시 실행하게 한다.
   ```bash
   sudo apt-get clean
   sudo cloud-init clean --logs --machine-id      # 옛 cloud-init 이라 --machine-id 를 모르면 빼고: sudo truncate -s 0 /etc/machine-id
   sudo rm -f /root/.bash_history /home/ubuntu/.bash_history
   ```
6. 인스턴스 **중지**(종료 아님) → "중지됨"을 확인한다.

### 2.3 AMI 만들기

1. 중지된 인스턴스 → 작업 → 이미지 및 템플릿 → **이미지 생성**.
   - 이름 `bbang-v2-was-docker-YYYYMMDD`, 설명 `Ubuntu <베이스 AMI ID> + docker.io python3-boto3 (docs/v2-docker-ami-runbook.md)`
   - 태그 `Project=bbang-v2`, `Role=was`, `BaseAmi=<베이스 AMI ID>`
2. EC2 → AMI 에서 상태가 **available** 될 때까지(5~10분). 아키텍처 `arm64` 확인. **AMI ID = X** 를 적어 둔다.
3. 임시 인스턴스를 **종료**한다. 루트 볼륨이 "종료 시 삭제"인지 확인(템플릿 기본값).

### 2.4 시작 템플릿 새 버전 (ImageId 만 교체)

1. 시작 템플릿 `lt-005c5f2006720a57c` → 작업 → **템플릿 수정(새 버전 생성)**.
2. **소스 템플릿 버전 = N** (ASG 가 쓰는 번호). 기본으로 뜨는 값이 N 인지 확인한다.
3. 템플릿 버전 설명: `Docker 선설치 AMI X (베이스 <AMI ID>, user_data 변경 없음)`.
4. 애플리케이션 및 OS 이미지 → **내 AMI** → X 선택. **그 외에는 아무것도 바꾸지 않는다.**
5. 생성 전 요약에서 `deploy.py` 의 `preflight()` 가 보는 조건이 그대로인지 맞춰 본다. 하나라도 어긋나면 cd-v2 가 `ValueError` 로 멈춘다(AWS 는 바뀌지 않는다).

   | `preflight()` 검사 | 유지해야 할 값 | 어디서 보나 |
   |---|---|---|
   | `ImageId` 가 `ami-` 로 시작 | X | 시작 템플릿 버전 |
   | `InstanceType` == `t4g.small` | 유지 | 시작 템플릿 버전 |
   | `IamInstanceProfile` 있음 | 유지 — AMI 를 바꿔도 비워지지 않는지 확인 | 시작 템플릿 버전 → 고급 |
   | `MetadataOptions.HttpTokens` == `required`, `HttpPutResponseHopLimit` ≥ 2 | 유지(컨테이너가 인스턴스 역할로 S3 에 가려면 홉 2) | 시작 템플릿 버전 → 고급 → 메타데이터 |
   | `SecurityGroupIds` == `[sg-00c0052096ed30815]` 하나, `NetworkInterfaces` 없음 | 유지 — 네트워크 인터페이스 항목을 **추가하지 않는다** | 시작 템플릿 버전 → 네트워크 |
   | 사용자 데이터 | 유지(preflight 가 보진 않지만, 비면 ASG 가 비정상 인스턴스를 교체할 때 bootstrap 없는 EC2 가 뜬다) | 시작 템플릿 버전 → 고급 → 사용자 데이터 |
   | (ASG) 시작 템플릿 ID == `lt-005c5f2006720a57c`, 혼합 인스턴스 정책 없음 | 유지 | ASG 세부 정보 |
   | (ASG) 서브넷 == `subnet-0078f9aaf3051fcd3`, 타깃그룹 == `tg-sweetgirl-prod-was-v2` 하나 | 유지 | ASG 세부 정보 |
   | (ASG) 상태 확인 유형에 `ELB` 포함, 원하는 용량 ≤ 2, 최대 == 2 | 유지 | ASG 세부 정보 |
   | (ASG) 진행 중 Instance Refresh 없음, 마지막 refresh 가 `RollbackFailed` 아님 | 배포 중이 아닐 것 | ASG → 인스턴스 새로 고침 |
   | (타깃그룹) 8080/HTTP, 헬스체크 포트 8081 경로 `/actuator/health/readiness` | 유지 | 타깃 그룹 |
   | (IAM, preflight 는 안 봄) 배포 역할이 X 로 `ec2:RunInstances` 가능 | `UseOwnBakedImages` 문(아래 7) | IAM → 역할 `role-sweetgirl-prod-github-v2` → 인라인 정책 `policy-sweetgirl-prod-github-v2-deploy` |
   | (인스턴스) 용량 > 0 이면 전부 `InService`·`Healthy`·타깃그룹 healthy | 확인 | ASG 인스턴스 관리 · 타깃 그룹 |

6. 생성 → **새 버전 번호 M**. "기본 버전으로 설정"은 하지 않아도 된다 — `deploy.py` 는 `$Default`/`$Latest` 를 보지 않고 **ASG 가 가리키는 숫자 버전**을 복제한다(§6).
7. **배포 역할 정책(처음 한 번만).** 배포 역할 `role-sweetgirl-prod-github-v2` 의 인라인 정책 `policy-sweetgirl-prod-github-v2-deploy` 는 `ec2:RunInstances` 를 베이스 AMI ID 하나에만 허용한다.
   그대로 두면 `preflight()` 는 통과하지만 §2.6 의 `StartInstanceRefresh` 가 `AccessDenied … not authorized to use launch template` 으로 실패한다(2026-11-09 attempt 2 — refresh 를 시작하는 쪽이 템플릿의 AMI 로 인스턴스를 띄울 권한을 가져야 한다).
   IAM → 역할 → 인라인 정책 → JSON 편집에서 **계정이 소유한 AMI 전부**를 허용하는 문을 추가한다. 이후 X' 로 갱신할 때는 다시 손댈 필요가 없다.
   ```json
   {
     "Sid": "UseOwnBakedImages",
     "Effect": "Allow",
     "Action": "ec2:RunInstances",
     "Resource": "arn:aws:ec2:ap-northeast-2::image/ami-*",
     "Condition": { "StringEquals": { "ec2:Owner": "210447604031" } }
   }
   ```
   기존 문(베이스 AMI ID 줄 포함)은 지우지 않는다 — §2.7 롤백으로 베이스 AMI 버전을 쓸 때 필요하다. 콘솔 JSON 편집기는 괄호를 자동으로 닫으니 저장 전 오류 0 을 확인한다.

### 2.5 ASG 가 M 을 가리키게 하기

1. Auto Scaling 그룹 `sweetgirl-was-auto-scaling` → 세부 정보 → 시작 템플릿 → **편집** → 버전 **M**(숫자. `Latest` 아님) → 업데이트.
2. 이걸로 실행 중인 EC2 가 바뀌지는 않는다. 다음 Instance Refresh(= cd-v2 deploy) 또는 ASG 가 비정상 인스턴스를 스스로 교체할 때부터 X 로 뜬다. 그 사이 ASG 가 교체하더라도 M 의 user_data 는 조건부라 정상 기동한다.

### 2.6 배포로 적용하고 확인

1. GitHub → Actions → **CD - V2 ASG** → 마지막 성공 run → **Re-run all jobs**. (`cd-v2.yml` 은 수동 실행을 두지 않는다. 다음 `develop-v2` 머지를 기다려도 된다.)
   `deploy.py` 가 M 을 소스로 M+1(새 user_data) 을 만들고 Instance Refresh 를 시작한다.
2. 확인할 것과 기대치:

   | 어디 | 무엇 | 기대 |
   |---|---|---|
   | Actions deploy 잡 | `Prepare the next launch template or refresh running instances` 스텝 시간 | 283초 → **약 200초** |
   | ASG → 활동 | `Launching a new EC2 instance` 의 시작~끝 | 지금보다 60~90초 짧게 |
   | 새 EC2 (SSM) | `sudo cloud-init analyze blame \| head -5` 의 `config-scripts-user` | 60~90초 → 수초 |
   | 새 EC2 (SSM) | `grep -c 'Setting up docker.io' /var/log/cloud-init-output.log` | **0**(설치를 건너뜀) |
   | 새 EC2 (SSM) | `sudo journalctl -u bbang-v2 -o short-iso --no-pager \| tail -3` | `v2 container readiness passed` |
   | 새 EC2 (SSM) | `dpkg-query -W docker.io python3-boto3` | §2.2 에서 적은 버전과 같음 |
   | 새 EC2 (SSM 없이) | EC2 → 인스턴스 → **인스턴스 진단 → 시스템 로그**: `running 'modules:final' … Up A seconds` 줄과 바로 다음 `Created symlink … bbang-v2.service` 줄의 초 차이 | 수초(apt 를 건너뜀). `Cloud-init … finished … Up N seconds` 는 이미지 pull·컨테이너 기동까지 기다린 시각이라 100초를 넘어도 정상 |

3. 결과를 §4 이력 표와 §3.1 에 적는다. 운영 인스턴스에 SSM 명령을 보낼 수 없을 때는 위 "SSM 없이" 행과 ASG → 활동의 시각으로 읽는다.

### 2.7 롤백

- **자동(refresh 실패)**: `AutoRollback` 이 ASG 를 M 으로 되돌린다. M 도 AMI X 를 쓰므로, **AMI 자체가 원인이면 자동 롤백으로는 돌아가지 않는다.** 그 경우 아래 수동.
- **수동(AMI 를 되돌릴 때)**:
  1. ASG → 인스턴스 새로 고침에서 진행 중이면 **취소**(상태 `Cancelled` 는 종료 상태라 preflight 를 막지 않는다).
  2. ASG 세부 정보 → 시작 템플릿 → 편집 → 버전 **N**(이전 AMI) → 업데이트. 또는 N 을 소스로 새 버전을 만들어 그걸 가리켜도 같다.
  3. **CD - V2 ASG** 마지막 성공 run → Re-run all jobs. `deploy.py` 가 N 을 소스로 새 버전을 만들어 이전 AMI 로 교체한다.
  4. 마지막 refresh 가 `RollbackFailed` 로 남아 있으면 preflight 가 "Investigate the previous rollback failure first" 로 멈춘다. 콘솔에서 ASG → 인스턴스 새로 고침 → **시작**(시작 템플릿 버전 N, 최소 정상 100%)을 한 번 성공시켜 최신 상태를 `Successful` 로 만든 뒤 3 을 한다.
- X 를 **등록 해제하지 않는다** — 돌아갈 수 있는 시작 템플릿 버전(M, M+1…)이 X 를 참조한다. 이전 AMI(베이스)는 Canonical 공개 AMI 라 그대로 남는다.

## 3. 기대 효과

새 EC2 가 뜨고 healthy 가 되기까지(약 170초)의 구성과 AMI 뒤 기대치. **측정이 아니라 추정이며 §2.6 의 `cloud-init analyze blame` 으로 확인한다.**

| 구간 | 지금 | AMI 뒤(기대) | 비고 |
|---|---:|---:|---|
| EC2 시작 → 타깃그룹 등록(`InService`) | 30~40초 | 같음 | 여기서 warmup 90초 타이머가 돈다 |
| cloud-init user_data — `apt-get update/install` | **60~90초** | **수초** | 이번 변경의 본체 |
| `bootstrap.py` — Parameter Store 조회·`docker pull`·Spring 기동·readiness | 60~80초 | 같음 | 이미지 레이어(temurin JRE)를 AMI 에 미리 pull 하면 10~30초 더 — 다음 단계 |
| ALB healthy 판정(10초×2회) | ~20초 | 같음 | |
| (병렬) `InstanceWarmup` 90초 | 비병목(부팅→healthy 170초 > 90초) | 부팅→healthy 80~110초면 비슷해진다 | 더 줄이려면 warmup 도 내려야 하지만, 기동 직후 크래시 루프를 구 인스턴스가 살아 있는 동안 잡는 창이라 60초 아래는 권하지 않는다 |
| 구 인스턴스 등록 해제 30초 + 종료 ~20초 | ~50초 | 같음 | |
| **deploy 스텝 합계** | **283초** | **약 200초** | cd-v2 워크플로 전체에서는 그 다음 병목이 `build` 잡의 Gradle 3~4.5분 |

### 3.1 첫 적용 측정 (2026-11-09)

cd-v2 run 42601024884 attempt 3, 새 EC2 `i-075262291265f3d3a`(AMI `ami-025ac2e24452a97b6`). 운영 인스턴스라 SSM 은 쓰지 않았고 콘솔 시스템 로그와 ASG 활동(KST)으로 읽었다.

| 구간 | 측정 | 근거 |
|---|---:|---|
| EC2 시작 → ASG `InService` | 94초 | ASG 활동 20:24:16 → 20:25:50 |
| 부팅 → cloud-init `modules:final`(user_data 시작) | 43초 | 시스템 로그 `Up 42.55 seconds`. 그 전은 Ubuntu 첫 부팅(네트워크 15초·config 17초·snapd 시딩) |
| user_data: docker·boto3 확인 → systemd 유닛 등록 | **1.5초** | `Up 42.58` → `Created symlink … bbang-v2.service` 44.11초. **apt 설치를 건너뛴 것 확인** |
| `bbang-v2` 부트스트랩(Parameter Store·`docker pull`·컨테이너 시작) | 72초 | 44초 → `Cloud-init … finished … Up 116.67 seconds` |
| `InService` → healthy(구 인스턴스 종료 시작) | 105초 | ASG 활동 20:25:50 → 20:27:35. Spring 기동 + readiness 10초×2회. warmup 90초는 병렬 |
| 구 인스턴스 종료 | 72초 | ASG 활동 20:27:35 → 20:28:47 |
| **deploy 스텝 합계** | **315초** | 직전 배포들은 222~314초 |

- 설치 건너뛰기는 동작한다. 그러나 deploy 스텝은 이전 범위 안이라 **한 번으로는 효과를 가를 수 없다** — 배포마다 90초쯤 편차가 있다. 다음 배포 몇 번의 스텝 시간을 §4 에 모아 비교한다.
- 시스템 로그의 `Up 116초` 는 부팅 지연이 아니라 cloud-init 가 컨테이너 부트스트랩이 끝나기를 기다린 시각이다. `cloud-init clean --machine-id` 로 인한 첫 부팅 지연은 없었다.
- 다음 후보(미검증): 72초의 `docker pull` — temurin JRE 레이어를 AMI 에 미리 pull 하거나 Docker Hub 대신 같은 리전 ECR; `InService` 까지 94초가 걸리는 구간.

부수 효과:
- 매 부팅마다 Ubuntu apt 미러(`ports.ubuntu.com`)에 의존하던 경로가 배포 경로에서 빠진다. 미러 장애로 user_data 가 실패해 refresh 가 롤백되는 경우가 사라진다.
- 지금은 배포할 때마다 그 시점 저장소의 docker.io 가 깔려 **코드 변경 없이 Docker 버전이 바뀔 수 있다.** AMI 뒤에는 버전이 AMI 에 고정되고, 올리는 일이 §2 의 명시적 작업이 된다(패치가 늦어질 수 있다는 뜻이기도 하다 — §5).

## 4. AMI 이력

| 날짜 | AMI ID | 베이스 AMI | docker.io / python3-boto3 | 시작 템플릿 버전 | 적용 배포(run) | 비고 |
|---|---|---|---|---|---|---|
| 2026-11-09 | `ami-025ac2e24452a97b6` (`bbang-v2-was-docker-20261109`) | `ami-019e063f3fff5d27f` | 29.1.3-0ubuntu4.1 / 1.40.72-1build1 | 24 → **25**(AMI 만). 배포가 27 생성(26 은 IAM 거부로 실패한 attempt 2 의 것) | run 42601024884 attempt 3 | deploy 스텝 315초(§3.1). 배포 역할 정책에 `UseOwnBakedImages` 추가(§2.4-7) |

## 5. 갱신 주기와 정리

- **분기 1회**, 또는 그 전이라도: Ubuntu 베이스 AMI 를 올릴 때, docker.io/containerd/runc 보안 공지가 났을 때. 절차는 §2 반복. 베이스를 최신 Ubuntu 로 올릴 때는 §2.2 에서 템플릿으로 띄우되 AMI 만 AMI 카탈로그의 최신 Ubuntu LTS arm64 로 바꿔 시작한다.
- 이전 X 는 새 X' 로 **배포가 2회 이상 성공한 뒤** 등록 해제하고 스냅샷도 지운다. 그 전까지는 롤백용이다.
- 아래가 되면 B(Image Builder)로 옮긴다: 갱신이 월 1회 이상 필요해지거나, AMI 에 넣을 것이 패키지 2개를 넘어서거나(이미지 선 pull·에이전트), 같은 AMI 를 배치 EC2(`docs/batch-server-runbook.md`)와 공유하게 될 때.

## 6. 참고 — `deploy.py` 가 시작 템플릿을 다루는 방식

이 절차의 순서(새 버전 → ASG 버전 지정 → Re-run)가 필요한 이유다.

- `preflight()` 는 ASG 가 가리키는 시작 템플릿 **버전**을 읽어 그 버전의 `ImageId`·`InstanceType`·IMDSv2·SG·IAM 프로파일을 검사한다. `$Latest`/`$Default` 는 보지 않는다.
- `deploy()` 는 ASG 버전이 `$Latest`/`$Default` 면 먼저 숫자로 고정하고, **그 숫자 버전을 `SourceVersion` 으로** `UserData` 만 바꾼 새 버전을 만든 뒤 그 버전으로 Instance Refresh(`AutoRollback: True`)를 시작한다.
- 그러므로 시작 템플릿에 새 버전을 만들기만 하고 ASG 가 그 버전을 가리키지 않으면 **다음 배포는 여전히 옛 AMI 를 복제한다.** Image Builder 의 "시작 템플릿 자동 갱신"도 같은 이유로 ASG 버전 지정까지 자동화하지 못한다.
- 용량이 0 이면 refresh 없이 ASG 버전만 바꾸고 끝난다(`Prepared only`).
