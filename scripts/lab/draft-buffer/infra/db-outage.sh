#!/usr/bin/env bash
# DB 를 죽였다 살리기 — 부하 중 Lab 의 PostgreSQL 을 DOWN 초 멈춘다. 버퍼가 그동안 타자를 받는가, 복귀 뒤 밀린 것을 얼마 만에 비우는가, 잃는 글이 있는가.
#   JAR=api-relay.jar LABEL=db-1 [VUS=1280] [DOWN=60] [APP_ARGS=..] bash db-outage.sh
set -uo pipefail
S=${DRAFT_LAB_WORK:?}; source "$(dirname "$0")/net.sh"
JAR=${JAR:-api-relay.jar}; L=${LABEL:-db-outage}; VUS=${VUS:-1280}; DOWN=${DOWN:-60}; DUR=$((40 + DOWN + 60))
(cd ${DRAFT_LAB_REPO:?} && node scripts/perf/generate-tokens.mjs $S/lab-k6/member-ids.txt $S/lab-k6/tokens.csv >/dev/null)
rsh $LAB "[ -f ~/lab/$JAR ] || cp ~/lab/api-0.0.1-SNAPSHO?.jar ~/lab/$JAR"; up $S/$JAR ubuntu@$LAB:lab/$JAR || exit 1
up $S/lab-k6/sample-db.sh ubuntu@$LAB:lab/ || exit 1
up $S/lab-k6/tokens.csv $S/lab-k6/courses.csv $S/lab-k6/lib.js $S/lab-k6/loss.js $S/lab-k6/drive-loss.sh ubuntu@$GEN:k6/ || exit 1
rm -f $S/lab-k6/tokens.csv
rsh $LAB "sudo systemctl start postgresql; cp ~/lab/$JAR ~/lab/api-0.0.1-SNAPSHO?.jar" || exit 1
rsh $LAB "cd ~/lab && HEAP_PCT=50 APP_EXTRA='${APP_ARGS:-} --server.tomcat.max-connections=100000 --server.tomcat.accept-count=10000 --server.tomcat.threads.max=${TOMCAT_THREADS:-400} --logging.level.org.springframework.web=INFO --logging.level.org.springframework.messaging=INFO --logging.level.org.hibernate.type.descriptor.sql=INFO --logging.level.org.hibernate.SQL=INFO --spring.jpa.show-sql=false' ./run-app.sh sentinel none | tail -1" || exit 1
echo "===== $L jar=$JAR 편집자=$VUS DB 정지=${DOWN}초  $(date +%T)"
rsh $LAB "chmod +x ~/lab/sample-db.sh; nohup ~/lab/sample-db.sh $L $((DUR + 150)) > /dev/null 2>&1 < /dev/null & echo 표본" >/dev/null
rsh $GEN "chmod +x ~/k6/drive-loss.sh; nohup ~/k6/drive-loss.sh $LAB:8080 $L $VUS ${DUR}s > /dev/null 2>&1 < /dev/null & echo 부하" >/dev/null
sleep 40
T0=$(rsh $LAB "date +%s%3N; sudo systemctl stop postgresql"); echo "  DB 정지 t0=$T0"
sleep $DOWN
T1=$(rsh $LAB "sudo systemctl start postgresql; date +%s%3N"); echo "  DB 시작 t1=$T1 (정지 $(( (T1 - T0) / 1000 ))초)"
for i in $(seq 1 60); do rsh $GEN "test -f ~/k6/results/$L/DONE" 2>/dev/null && break; sleep 5; done
# 밀린 초안이 다 내려갈 때까지(backlog 0) 최대 2분
for i in $(seq 1 40); do B=$(rsh $LAB "curl -s -m 3 localhost:8091/actuator/prometheus | awk '/^draft_backlog/{print int(\$2)}'"); [ "${B:-1}" = "0" ] && break; sleep 3; done; echo "  부하 종료 뒤 backlog=$B"
rsh $GEN "cd ~/k6 && python3 -c \"import json; t=open('results/$L/k6.txt').read(); k=json.loads(t[t.index('{'):]); print('  k6: 재시도', k.get('retries'), '· 포기', k.get('givenUp'), '· 503', k.get('unavailable503'))\"; gzip -c results/$L/console.log > /tmp/console-$L.gz"
rsync -e "ssh $SSHO -o ProxyCommand='$PJ'" --partial --timeout=30 -q ubuntu@$GEN:/tmp/console-$L.gz /tmp/console-$L.gz && up /tmp/console-$L.gz ubuntu@$LAB:/tmp/
rsh $LAB "T0=$T0 T1=$T1 L=$L python3 - <<'PY'
import os,re,gzip,subprocess,json
L=os.environ['L']; t0=int(os.environ['T0']); t1=int(os.environ['T1'])
rows=[l.rstrip('\n').split('\t') for l in open('/tmp/db-%s.tsv'%L) if l.strip()]
rows=[(int(r[0]),int(r[1]),float(r[2]),int(r[3]),float(r[4]),int(r[5]),int(r[6]),int(r[7]),int(r[8]),int(r[9]),int(r[10])) for r in rows]
print('  초(정지 기준)  DB  200/s  503/s  503평균ms  backlog  Hikari쓰는중/대기  플러시/s  바쁜스레드')
first503=last503=None; drained=None; maxbacklog=0; ok_down=un_down=0
for a,b in zip(rows,rows[1:]):
    s=(b[0]-t0)/1000.0; dt=(b[0]-a[0])/1000.0 or 1; ok=(b[1]-a[1])/dt; un=(b[3]-a[3])/dt; avg=1000*(b[4]-a[4])/max(1,b[3]-a[3]); fl=(b[8]-a[8])/dt
    if un>0: first503=s if first503 is None else first503; last503=s
    if t0<=b[0]<=t1: ok_down+=b[1]-a[1]; un_down+=b[3]-a[3]
    maxbacklog=max(maxbacklog,b[5])
    if b[0]>t1 and drained is None and b[5]==0 and a[5]==0: drained=(b[0]-t1)/1000.0
    down=(t1-t0)/1000.0
    if (-4<=s<=down+45 and int(s)%3==0) or (down-2<=s<=down+12): print('  %8.0f %5s %6.0f %6.0f %9.0f %8d %8d/%-6d %7.1f %9d'%(s,'살아있음' if b[10] else '죽음',ok,un,avg,b[5],b[6],b[7],fl,b[9]))
print('  DB 가 죽어 있던 동안: PUT 200 %d건 · 503 %d건 (받은 비율 %.1f%%)'%(ok_down,un_down,100.0*ok_down/max(1,ok_down+un_down)))
print('  503 구간: %s ~ %s 초(정지 기준) · 밀린 코스 최대 %d · DB 복귀 뒤 backlog 0 까지 %s 초'%(first503 and round(first503,1), last503 and round(last503,1), maxbacklog, drained and round(drained,1)))
last={}
for line in gzip.open('/tmp/console-%s.gz'%L,'rt',errors='ignore'):
    m=re.search(r'R,(\d+):(\d+):(\d+),(\d+),(\d+)',line)
    if not m: continue
    k=(m[1],m[2],m[3]); seq,acc=int(m[4]),int(m[5]); r=last.setdefault(k,[0,0]); r[0]=max(r[0],seq); r[1]=max(r[1],acc)
q='SELECT cs.course_id,cs.store_id,csm.member_id,csm.seq FROM course_store_memos csm JOIN course_stores cs ON cs.id=csm.course_store_id JOIN courses c ON c.id=cs.course_id WHERE c.title LIKE \x27[초안]코스%\x27 AND csm.deleted_at IS NULL'
out=subprocess.check_output(['psql','host=127.0.0.1 port=5432 dbname=soboro user=sweetgirl','-At','-F,','-c',q],text=True,env=dict(os.environ,PGPASSWORD='lab'))
db={tuple(l.split(',')[:3]):int(l.split(',')[3]) for l in out.splitlines()}
print('  유실 판정:', json.dumps(dict(칸=len(last), 서버가_받았다고_한_글_유실=sum(1 for k,(s,a) in last.items() if a and db.get(k,0)<a), 마지막_글_미반영=sum(1 for k,(s,a) in last.items() if db.get(k,0)<s)),ensure_ascii=False))
PY
grep -c 'Connection is not available' ~/lab/app.log | sed 's/^/  커넥션 못 얻음 로그: /'; grep -c 'DB 반영 실패' ~/lab/app.log | sed 's/^/  플러셔 DB 반영 실패 로그: /'; pgrep -f api-0.0.1 >/dev/null && echo '  앱 살아 있음' || echo '  !! 앱 죽음'"
echo DBOUTAGEDONE
