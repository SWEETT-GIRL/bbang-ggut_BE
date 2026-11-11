# 끊기는 회선용 도우미. source 해서 쓴다.
K=${DRAFT_LAB_KEY:?}
SSHO="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR -o ServerAliveInterval=5 -o ServerAliveCountMax=3"
PJ="ssh $SSHO -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_LAB_GEN:?}
# rsh <host> <명령> — 실패하면 최대 8번 다시
rsh(){ local h=$1; shift; local i; for i in 1 2 3 4 5 6 7 8; do ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$h "$@" && return 0; sleep 3; done; echo "rsh 실패: $h" >&2; return 1; }
# up <파일...> <host>:<경로> — 끊겨도 이어서 올린다
up(){ local i; for i in $(seq 1 30); do rsync -e "ssh $SSHO -o ProxyCommand='$PJ'" --partial --inplace --timeout=20 -q "$@" && return 0; sleep 3; done; echo "업로드 실패" >&2; return 1; }
