#!/usr/bin/env bash
# bastion에서 실행. Nginx upstream을 지정한 색으로 전환하고 reload 한다.
#
#   sudo ./switch-upstream.sh <blue|green> <WAS_PRIVATE_IP>
#
# 활성 색의 단일 진실 공급원(source of truth)은 이 스크립트가 쓰는
# /etc/nginx/conf.d/bbang-upstream.conf 의 ACTIVE_COLOR 주석이다.
# 배포 워크플로우는 그 값을 읽어 다음 색을 정한다.
set -euo pipefail

UPSTREAM_CONF=/etc/nginx/conf.d/bbang-upstream.conf

COLOR="${1:?사용법: switch-upstream.sh <blue|green> <WAS_PRIVATE_IP>}"
WAS_HOST="${2:?사용법: switch-upstream.sh <blue|green> <WAS_PRIVATE_IP>}"

case "$COLOR" in
    blue)  APP_PORT=8080 ;;
    green) APP_PORT=8082 ;;
    *)     echo "알 수 없는 색: $COLOR (blue 또는 green)" >&2; exit 1 ;;
esac

# Nginx가 upstream을 바라보기 전에 대상 포트가 실제로 열려 있는지 확인한다.
# 앱 포트에는 actuator가 없으므로(관리 포트 분리) TCP 연결 가능 여부로 판단한다.
# 여기서 걸러내면 잘못된 전환으로 전체 트래픽이 죽는 상황을 막을 수 있다.
if ! timeout 5 bash -c "cat < /dev/null > /dev/tcp/${WAS_HOST}/${APP_PORT}" 2>/dev/null; then
    echo "❌ ${WAS_HOST}:${APP_PORT} 에 연결할 수 없다 — 전환 중단" >&2
    exit 1
fi

cat > "$UPSTREAM_CONF" <<UPSTREAM
# 이 파일은 switch-upstream.sh 가 생성한다. 직접 수정하지 말 것.
# ACTIVE_COLOR=${COLOR}
upstream bbang_app {
    server ${WAS_HOST}:${APP_PORT} max_fails=3 fail_timeout=10s;
    keepalive 32;
}
UPSTREAM

nginx -t
systemctl reload nginx

echo "✅ upstream 전환 완료 → ${COLOR} (${WAS_HOST}:${APP_PORT})"
