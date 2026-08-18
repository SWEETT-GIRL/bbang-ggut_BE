#!/usr/bin/env bash
# bastion 1회 세팅 스크립트. Nginx + certbot 설치, 인증서 발급, 배포용 sudo 권한 등록.
#
#   sudo ./setup-nginx.sh <WAS_PRIVATE_IP> <DEPLOY_SSH_USER>
#
# 전제 조건 (실행 전에 반드시 확인):
#   1. bastion 보안그룹에 0.0.0.0/0 → 80, 443 인바운드가 열려 있어야 한다.
#   2. bbang-ggut.cloud 의 A 레코드가 이 서버의 EIP를 가리켜야 한다.
#      (Let's Encrypt HTTP-01 챌린지가 도메인으로 이 서버에 도달해야 하므로,
#       DNS 전환을 먼저 하거나 — 아래 "무중단 순서" 참고 — DNS-01 방식을 써야 한다.)
#
# 무중단 순서로 하려면 DNS를 아직 ALB에 둔 채로 인증서를 미리 받아야 한다.
# 그 경우 이 스크립트 대신 DNS-01 챌린지를 쓴다:
#   certbot certonly --dns-route53 -d bbang-ggut.cloud
#   (bastion 인스턴스 역할에 route53:ChangeResourceRecordSets 권한 필요)
set -euo pipefail

WAS_HOST="${1:?사용법: setup-nginx.sh <WAS_PRIVATE_IP> <DEPLOY_SSH_USER>}"
DEPLOY_USER="${2:?사용법: setup-nginx.sh <WAS_PRIVATE_IP> <DEPLOY_SSH_USER>}"
DOMAIN=bbang-ggut.cloud
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "$EUID" -ne 0 ]; then
    echo "root로 실행해야 한다: sudo $0 $*" >&2
    exit 1
fi

# --- 1. 패키지 설치 (Amazon Linux / Ubuntu 모두 대응) ---
if command -v dnf >/dev/null 2>&1; then
    dnf install -y nginx certbot python3-certbot-nginx
elif command -v apt-get >/dev/null 2>&1; then
    apt-get update
    apt-get install -y nginx certbot python3-certbot-nginx
else
    echo "지원하지 않는 패키지 매니저 — nginx와 certbot을 수동 설치할 것" >&2
    exit 1
fi

mkdir -p /var/www/certbot

# --- 2. 인증서 발급 전 부트스트랩 설정 ---
# 최종 설정은 아직 없는 인증서 파일을 참조하므로 nginx -t 가 실패한다.
# HTTP-01 챌린지만 받을 수 있는 최소 설정을 먼저 올린다.
cat > /etc/nginx/conf.d/bbang-bootstrap.conf <<'BOOTSTRAP'
server {
    listen 80 default_server;
    server_name bbang-ggut.cloud;
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }
    location / {
        return 503;
    }
}
BOOTSTRAP

# 배포판 기본 설정의 default server와 충돌하지 않게 정리한다.
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl enable --now nginx
systemctl reload nginx

# --- 3. 인증서 발급 ---
if [ ! -d "/etc/letsencrypt/live/${DOMAIN}" ]; then
    certbot certonly --webroot -w /var/www/certbot \
        -d "${DOMAIN}" \
        --non-interactive --agree-tos \
        --register-unsafely-without-email \
        --deploy-hook "systemctl reload nginx"
else
    echo "인증서가 이미 있다 — 발급 건너뜀"
fi

# --- 4. 최종 설정 배치 ---
rm -f /etc/nginx/conf.d/bbang-bootstrap.conf
install -m 644 "${SCRIPT_DIR}/../nginx/${DOMAIN}.conf" "/etc/nginx/conf.d/${DOMAIN}.conf"

# upstream 초기값 — blue로 시작한다.
"${SCRIPT_DIR}/switch-upstream.sh" blue "${WAS_HOST}"

# --- 5. 배포 계정에 필요한 sudo 권한만 부여 ---
# CI가 upstream 전환을 위해 비밀번호 없이 이 스크립트만 실행할 수 있어야 한다.
install -d -m 755 /opt/bbang
install -m 755 "${SCRIPT_DIR}/switch-upstream.sh" /opt/bbang/switch-upstream.sh
cat > /etc/sudoers.d/bbang-deploy <<SUDOERS
${DEPLOY_USER} ALL=(root) NOPASSWD: /opt/bbang/switch-upstream.sh
SUDOERS
chmod 440 /etc/sudoers.d/bbang-deploy
visudo -c

# --- 6. 인증서 자동 갱신 확인 ---
systemctl enable --now certbot-renew.timer 2>/dev/null \
    || systemctl enable --now certbot.timer 2>/dev/null \
    || echo "⚠️  certbot 타이머를 찾지 못했다 — 갱신 크론을 직접 확인할 것"

echo
echo "✅ bastion 세팅 완료"
echo "   - 활성 색: blue (${WAS_HOST}:8080)"
echo "   - upstream 전환: sudo /opt/bbang/switch-upstream.sh <blue|green> ${WAS_HOST}"
echo "   - 인증서: /etc/letsencrypt/live/${DOMAIN}/"
