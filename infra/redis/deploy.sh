#!/usr/bin/env bash
set -Eeuo pipefail

# 사용: deploy.sh <master|replica|sentinel>  — compose.yaml 의 profile 이름이다.
role=${1:?usage: deploy.sh <master|replica|sentinel>}
case "$role" in master|replica|sentinel) ;; *) echo "unknown role: $role" >&2; exit 2 ;; esac
release_dir=$(cd -- "$(dirname -- "$0")" && pwd)
deploy_root=$(dirname -- "$release_dir")
exec 9>"$deploy_root/.deploy.lock"
flock -n 9 || { echo 'Another Redis deployment is running.' >&2; exit 1; }

docker info >/dev/null
docker compose version
test -s "$release_dir/users.acl"
test -s "$release_dir/sentinel.conf"
[ "$role" = sentinel ] || { test -s "$release_dir/replication.conf" && test -s "$release_dir/replication-cache.conf"; }
# 역할은 릴리스 폴더에 남겨 롤백 때 같은 역할로 되돌린다.
printf '%s\n' "$role" > "$release_dir/role"
compose() { docker compose --project-name sweetgirl-prod-redis --profile "$(cat "$1/role")" -f "$1/compose.yaml" "${@:2}"; }
compose "$release_dir" config --quiet
# 이미지를 먼저 가져오므로 다운로드 실패는 기존 컨테이너에 영향을 주지 않는다.
compose "$release_dir" pull
docker volume create sweetgirl-prod-redis-data >/dev/null
docker volume create sweetgirl-prod-redis-draft-data >/dev/null
docker volume create sweetgirl-prod-redis-draft-sentinel-data >/dev/null

previous=''
if [ -L "$deploy_root/current" ]; then previous=$(readlink -f "$deploy_root/current"); fi
# 역할 파일이 없는 옛 릴리스(단일 서버 시절)는 master 로 본다.
if [ -n "$previous" ] && [ ! -s "$previous/role" ]; then printf 'master\n' > "$previous/role"; fi
rollback() {
  status=$?
  trap - ERR
  if [ -n "$previous" ] && [ "$previous" != "$release_dir" ]; then
    echo 'Deployment failed; restoring the previous configuration.' >&2
    compose "$previous" up -d --no-deps --force-recreate --wait --wait-timeout 120 ||
      echo 'Rollback failed; operator intervention required.' >&2
  else
    echo 'First deployment failed; inspect Redis container logs.' >&2
  fi
  exit "$status"
}
trap rollback ERR
# bind mount한 설정만 바뀌어도 다시 읽도록 재생성한다. 서비스 목록은 profile 이 정한다.
compose "$release_dir" up -d --no-deps --force-recreate --wait --wait-timeout 120
ln -sfn "$release_dir" "$deploy_root/current.next"
mv -Tf "$deploy_root/current.next" "$deploy_root/current"
trap - ERR
compose "$release_dir" ps
