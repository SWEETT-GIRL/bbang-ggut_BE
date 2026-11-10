#!/usr/bin/env bash
# 모니터링 서버의 grafana 컨테이너 안 provisioning/alerting 에 초안 알림 파일을 넣고 재시작한다.
#   bash provision_alerts.sh <모니터링서버 사설IP> <알림 json 경로>
set -euo pipefail
MON=${1:?모니터링 서버 사설 IP}; FILE=${2:?알림 json 경로}; test -s "$FILE"
K=${DRAFT_LAB_KEY:?}; B=${DRAFT_LAB_BASTION:?}
O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"
J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@$B"
echo "1) 파일 올리기"; scp $O "$J" "$FILE" ubuntu@$MON:/tmp/course-draft-rules.json
echo "2) 컨테이너에 넣고 재시작"
ssh $O "$J" ubuntu@$MON 'set -e
  docker cp /tmp/course-draft-rules.json grafana:/etc/grafana/provisioning/alerting/course-draft-rules.json; rm -f /tmp/course-draft-rules.json
  docker exec grafana ls -la /etc/grafana/provisioning/alerting/
  docker restart grafana >/dev/null; echo "   grafana 재시작"
  for i in $(seq 1 30); do curl -sf http://127.0.0.1:3000/api/health >/dev/null 2>&1 && break; sleep 2; done
  echo "3) 로그에서 provisioning 결과"; docker logs --since 2m grafana 2>&1 | grep -i -E "provision|course-draft|alert" | grep -v -i "level=debug" | tail -12 | cut -c1-200'
echo "끝. Grafana → Alerting → Alert rules → 폴더 CourseDraft 에 3개가 보이면 성공."
