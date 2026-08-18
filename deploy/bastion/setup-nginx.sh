#!/usr/bin/env bash
# bastion 1회 세팅. Nginx + certbot 설치, DNS-01로 인증서 발급, 배포용 sudo 권한 등록.
#
#   sudo ./setup-nginx.sh <WAS_PRIVATE_IP> <DEPLOY_SSH_USER>
#   예: sudo ./setup-nginx.sh 10.7.27.165 ubuntu
#
# 전제 조건:
#   bastion에 Route53 권한을 가진 IAM 인스턴스 프로파일이 붙어 있어야 한다.
#   (profile-sweetgirl-prod-bastion-certbot — _acme-challenge TXT 레코드만 조작 가능)
#
# DNS-01을 쓰는 이유: 도메인이 아직 ALB를 가리키는 동안에도 발급이 된다.
# HTTP-01은 Let's Encrypt가 도메인으로 직접 접속해 검증하므로 DNS를 먼저
# 넘겨야 하고, 그 사이 인증서가 없어 TLS 핸드셰이크부터 실패한다.
set -euo pipefail

WAS_HOST="${1:?사용법: setup-nginx.sh <WAS_PRIVATE_IP> <DEPLOY_SSH_USER>}"
DEPLOY_USER="${2:?사용법: setup-nginx.sh <WAS_PRIVATE_IP> <DEPLOY_SSH_USER>}"
DOMAIN=bbang-ggut.cloud
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "$EUID" -ne 0 ]; then
    echo "root로 실행해야 한다: sudo $0 $*" >&2
    exit 1
fi

# --- 1. 패키지 설치 ---
if command -v apt-get >/dev/null 2>&1; then
    apt-get update
    apt-get install -y nginx certbot python3-certbot-dns-route53
elif command -v dnf >/dev/null 2>&1; then
    dnf install -y nginx certbot python3-certbot-dns-route53
else
    echo "지원하지 않는 패키지 매니저 — nginx와 certbot을 수동 설치할 것" >&2
    exit 1
fi

# HTTP-01 폴백용 경로. DNS-01로 발급·갱신하는 한 쓰이지 않지만,
# IAM 역할이 사라졌을 때 도메인이 이미 여기를 가리키면 폴백으로 쓸 수 있다.
mkdir -p /var/www/certbot

# --- 2. IAM 역할이 실제로 붙어 있는지 먼저 확인 ---
# 여기서 걸러내지 않으면 certbot이 한참 돌다 권한 오류로 죽는다.
if ! aws sts get-caller-identity >/dev/null 2>&1; then
    if ! command -v aws >/dev/null 2>&1; then
        echo "⚠️  aws CLI가 없다 — certbot의 boto3가 IMDS를 직접 읽으므로 계속 진행한다."
    else
        echo "❌ AWS 자격증명을 얻지 못했다. bastion에 인스턴스 프로파일이 붙어 있는지 확인할 것." >&2
        exit 1
    fi
fi

# --- 3. 인증서 발급 (DNS-01) ---
# 이 단계는 Nginx 설정과 무관하다. 도메인이 어디를 가리키든 발급된다.
if [ ! -d "/etc/letsencrypt/live/${DOMAIN}" ]; then
    certbot certonly --dns-route53 \
        -d "${DOMAIN}" \
        --non-interactive --agree-tos \
        --register-unsafely-without-email \
        --deploy-hook "systemctl reload nginx"
else
    echo "인증서가 이미 있다 — 발급 건너뜀"
fi

# --- 4. Nginx 설정 배치 ---
# 인증서가 이미 있으므로 최종 설정을 바로 올려도 nginx -t 가 통과한다.
rm -f /etc/nginx/sites-enabled/default
install -m 644 "${SCRIPT_DIR}/../nginx/${DOMAIN}.conf" "/etc/nginx/conf.d/${DOMAIN}.conf"

# upstream 초기값. 전환 시점에 was-1에는 구 방식 컨테이너가 8080을 잡고 있으므로
# blue(8080)로 시작해야 트래픽이 그대로 이어진다.
install -d -m 755 /opt/bbang
install -m 755 "${SCRIPT_DIR}/switch-upstream.sh" /opt/bbang/switch-upstream.sh
/opt/bbang/switch-upstream.sh blue "${WAS_HOST}"

systemctl enable --now nginx

# --- 5. 배포 계정에 필요한 sudo 권한만 부여 ---
# CI가 upstream 전환을 위해 비밀번호 없이 이 스크립트만 실행할 수 있어야 한다.
cat > /etc/sudoers.d/bbang-deploy <<SUDOERS
${DEPLOY_USER} ALL=(root) NOPASSWD: /opt/bbang/switch-upstream.sh
SUDOERS
chmod 440 /etc/sudoers.d/bbang-deploy
visudo -c

# --- 6. 자동 갱신 확인 ---
systemctl enable --now certbot.timer 2>/dev/null \
    || echo "⚠️  certbot.timer를 찾지 못했다 — 갱신 스케줄을 직접 확인할 것"
certbot renew --dry-run --dns-route53 || echo "⚠️  갱신 리허설 실패 — IAM 권한을 확인할 것"

echo
echo "✅ bastion 세팅 완료"
echo "   - 활성 색: blue (${WAS_HOST}:8080)"
echo "   - upstream 전환: sudo /opt/bbang/switch-upstream.sh <blue|green> ${WAS_HOST}"
echo "   - 인증서: /etc/letsencrypt/live/${DOMAIN}/"
echo
echo "다음: DNS를 바꾸기 전에 bastion을 직접 찔러 검증할 것"
echo "   curl -sv --resolve ${DOMAIN}:443:3.34.246.201 https://${DOMAIN}/menu-categories"
