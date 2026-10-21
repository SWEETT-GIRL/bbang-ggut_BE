#!/usr/bin/env bash
set -Eeuo pipefail

release_dir=$(cd -- "$(dirname -- "$0")" && pwd)
deploy_root=$(dirname -- "$release_dir")
exec 9>"$deploy_root/.deploy.lock"
flock -n 9 || { echo 'Another Redis deployment is running.' >&2; exit 1; }

docker info >/dev/null
docker compose version
test -s "$release_dir/users.acl"
compose() { docker compose --project-name sweetgirl-prod-redis -f "$1/compose.yaml" "${@:2}"; }
compose "$release_dir" config --quiet
# 이미지를 먼저 가져오므로 다운로드 실패는 기존 컨테이너에 영향을 주지 않는다.
compose "$release_dir" pull redis
docker volume create sweetgirl-prod-redis-data >/dev/null

previous=''
if [ -L "$deploy_root/current" ]; then previous=$(readlink -f "$deploy_root/current"); fi
rollback() {
  status=$?
  trap - ERR
  if [ -n "$previous" ] && [ "$previous" != "$release_dir" ]; then
    echo 'Deployment failed; restoring the previous configuration.' >&2
    compose "$previous" up -d --no-deps --force-recreate --wait --wait-timeout 120 redis ||
      echo 'Rollback failed; operator intervention required.' >&2
  else
    echo 'First deployment failed; inspect Redis container logs.' >&2
  fi
  exit "$status"
}
trap rollback ERR
# bind mount한 redis.conf만 바뀌어도 Redis가 다시 읽도록 재생성한다.
compose "$release_dir" up -d --no-deps --force-recreate --wait --wait-timeout 120 redis
ln -sfn "$release_dir" "$deploy_root/current.next"
mv -Tf "$deploy_root/current.next" "$deploy_root/current"
trap - ERR
compose "$release_dir" ps
