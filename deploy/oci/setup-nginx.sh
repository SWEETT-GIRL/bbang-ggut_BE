#!/usr/bin/env bash
# OCI 운영 서버 1회 세팅. Nginx + certbot 설치, 방화벽 80/443 개방, 배포용 sudo 권한 등록.
# deploy/bastion/setup-nginx.sh 의 단일 서버판이다 — Nginx 와 앱이 같은 호스트에 있다.
#
#   sudo ./setup-nginx.sh <DEPLOY_SSH_USER>
#   예: sudo ./setup-nginx.sh ubuntu
#
# 전제 조건: /etc/letsencrypt/live/bbang-ggut.cloud/ 에 인증서가 있어야 한다.
#   AWS bastion 은 Route53 DNS-01 로 발급했지만 여기에는 그 IAM 역할이 없다.
#   도메인이 아직 AWS 를 가리키는 동안에는 HTTP-01 로도 발급할 수 없으므로,
#   컷오버 전에 bastion 의 /etc/letsencrypt 를 그대로 옮겨 온다(README 참고).
#   DNS 가 이 서버를 가리킨 뒤의 갱신은 HTTP-01(webroot)로 한다.
set -euo pipefail

DEPLOY_USER="${1:?사용법: setup-nginx.sh <DEPLOY_SSH_USER>}"
DOMAIN=bbang-ggut.cloud
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "$EUID" -ne 0 ]; then
    echo "root로 실행해야 한다: sudo $0 $*" >&2
    exit 1
fi

# --- 1. 인증서가 먼저 있어야 한다 ---
# 없는 채로 설정을 올리면 nginx -t 가 실패해 Nginx 가 아예 뜨지 않는다.
if [ ! -f "/etc/letsencrypt/live/${DOMAIN}/fullchain.pem" ]; then
    echo "❌ /etc/letsencrypt/live/${DOMAIN}/ 에 인증서가 없다." >&2
    echo "   bastion 의 /etc/letsencrypt 를 옮겨 온 뒤 다시 실행할 것 (deploy/oci/README.md)." >&2
    exit 1
fi

# --- 2. 패키지 설치 ---
apt-get update
apt-get install -y nginx certbot
mkdir -p /var/www/certbot

# --- 3. 방화벽 ---
# OCI 의 Ubuntu 이미지는 iptables INPUT 끝에 REJECT 가 있어 22 외에는 막혀 있다.
# REJECT 앞에 넣어야 하므로 -A 가 아니라 22 규칙 바로 뒤에 끼워 넣는다.
# VCN 보안 목록(콘솔)에서도 80/443 을 열어야 바깥에서 닿는다 — 이 스크립트가 해 주지 않는다.
for PORT in 80 443; do
    if ! iptables -C INPUT -p tcp -m state --state NEW -m tcp --dport "$PORT" -j ACCEPT 2>/dev/null; then
        SSH_RULE=$(iptables -L INPUT --line-numbers -n | awk '/dpt:22/ {print $1; exit}')
        iptables -I INPUT "$((${SSH_RULE:-1} + 1))" -p tcp -m state --state NEW -m tcp --dport "$PORT" -j ACCEPT
    fi
done
if command -v netfilter-persistent >/dev/null 2>&1; then
    netfilter-persistent save
else
    echo "⚠️  netfilter-persistent 가 없다 — 재부팅하면 80/443 규칙이 사라진다. iptables-persistent 를 설치할 것" >&2
fi

# --- 4. Nginx 설정 배치 ---
# 배포판 기본 사이트가 80 을 먼저 잡지 않게 링크만 끊는다(원본은 sites-available 에 남는다).
if [ -L /etc/nginx/sites-enabled/default ]; then
    unlink /etc/nginx/sites-enabled/default
fi
install -m 644 "${SCRIPT_DIR}/../nginx/${DOMAIN}.conf" "/etc/nginx/conf.d/${DOMAIN}.conf"

# upstream 초기값을 green 으로 적어 둔다 → 첫 배포가 blue(8080)로 올라간다.
# 앱이 아직 없어 전환 스크립트의 포트 확인은 통과할 수 없으므로 설정 파일만 직접 쓴다.
# CD 는 이 파일의 ACTIVE_COLOR 를 읽어야 첫 배포를 시작할 수 있다.
install -d -m 755 /opt/bbang
install -m 755 "${SCRIPT_DIR}/../bastion/switch-upstream.sh" /opt/bbang/switch-upstream.sh
if [ ! -f /etc/nginx/conf.d/bbang-upstream.conf ]; then
    cat > /etc/nginx/conf.d/bbang-upstream.conf <<UPSTREAM
# 이 파일은 switch-upstream.sh 가 생성한다. 직접 수정하지 말 것.
# ACTIVE_COLOR=green
upstream bbang_app {
    server 127.0.0.1:8082 max_fails=3 fail_timeout=10s;
    keepalive 32;
}
UPSTREAM
fi

nginx -t
systemctl enable --now nginx
systemctl reload nginx

# --- 5. 배포 계정에 필요한 sudo 권한만 부여 ---
cat > /etc/sudoers.d/bbang-deploy <<SUDOERS
${DEPLOY_USER} ALL=(root) NOPASSWD: /opt/bbang/switch-upstream.sh
SUDOERS
chmod 440 /etc/sudoers.d/bbang-deploy
visudo -c

# --- 6. 인증서 갱신을 HTTP-01(webroot)로 바꾼다 ---
# 옮겨 온 갱신 설정은 dns-route53 인증을 쓰도록 적혀 있어 여기서는 실패한다.
RENEWAL_CONF="/etc/letsencrypt/renewal/${DOMAIN}.conf"
if [ -f "$RENEWAL_CONF" ] && grep -q "dns-route53" "$RENEWAL_CONF"; then
    cp "$RENEWAL_CONF" "${RENEWAL_CONF}.route53.bak"
    sed -i -e 's/^authenticator = dns-route53/authenticator = webroot/' "$RENEWAL_CONF"
    grep -q '^webroot_path' "$RENEWAL_CONF" \
        || sed -i '/^authenticator = webroot/a webroot_path = /var/www/certbot,' "$RENEWAL_CONF"
    grep -q '^\[\[webroot_map\]\]' "$RENEWAL_CONF" \
        || printf '[[webroot_map]]\n%s = /var/www/certbot\n' "$DOMAIN" >> "$RENEWAL_CONF"
fi
systemctl enable --now certbot.timer 2>/dev/null \
    || echo "⚠️  certbot.timer를 찾지 못했다 — 갱신 스케줄을 직접 확인할 것"

echo
echo "✅ 서버 세팅 완료"
echo "   - 초기 활성 색: green → 첫 배포는 blue(8080)로 올라간다"
echo "   - upstream 전환: sudo /opt/bbang/switch-upstream.sh <blue|green> 127.0.0.1"
echo "   - 갱신 리허설은 DNS 가 이 서버를 가리킨 뒤에: sudo certbot renew --dry-run"
echo
echo "다음: DNS 를 바꾸기 전에 이 서버를 직접 찔러 검증할 것"
echo "   curl -sv --resolve ${DOMAIN}:443:<이 서버의 퍼블릭 IP> https://${DOMAIN}/menu-categories"
