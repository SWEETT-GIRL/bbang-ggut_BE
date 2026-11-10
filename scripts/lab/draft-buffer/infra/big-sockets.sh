S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_LAB_GEN:?}
(cd ${DRAFT_LAB_REPO:?} && node scripts/perf/generate-tokens.mjs $S/lab-k6/member-ids.txt $S/lab-k6/tokens.csv >/dev/null)
scp $O "$J" -q $S/lab-k6/mass_sockets.py $S/lab-k6/tokens.csv ubuntu@$GEN:~/k6/
ssh $O "$J" ubuntu@$LAB "cd ~/lab && HEAP_PCT=50 APP_EXTRA='--server.tomcat.max-connections=200000 --server.tomcat.accept-count=20000 --server.tomcat.threads.max=400 --logging.level.org.springframework.web=INFO --logging.level.org.springframework.messaging=INFO --logging.level.org.hibernate.type.descriptor.sql=INFO --logging.level.org.hibernate.SQL=INFO --spring.jpa.show-sql=false' ./run-app.sh sentinel none | tail -1; sudo dmesg -C"
ssh $O "$J" ubuntu@$LAB "[ -f ~/lab/sample.pid ] && kill \$(cat ~/lab/sample.pid) 2>/dev/null; nohup ~/lab/sample.sh big-sockets 420 >/dev/null 2>&1 < /dev/null &" &
echo "===== big sockets ${STEPS:-5000,10000,20000,30000,40000}  $(date +%T)"
ssh $O "$J" ubuntu@$GEN "cd ~/k6 && ulimit -n 200000 && python3 mass_sockets.py $LAB:8080 ${STEPS:-5000,10000,20000,30000,40000} 30 tokens.csv courses.csv 2>&1 | tail -20; free -m | awk '/Mem/{print \"gen mem used=\"\$3\"MB\"}'"
ssh $O "$J" ubuntu@$LAB "kill \$(cat ~/lab/sample.pid) 2>/dev/null; echo '--- lab (ts cpu heapMB load1 memAvailMB conns)'; awk -F'\t' 'NR>1 && (NR-2)%2==0{print \$1, \$2, \$3, \$7, \$8, \$9}' ~/lab/samples/big-sockets.tsv; sudo dmesg | grep -c 'Killed process' | sed 's/^/lab oom: /'; pgrep -x java | wc -l | sed 's/^/java alive: /'; grep -c -i 'too many open files' ~/lab/app.log | sed 's/^/too many open files: /'"
echo SOCKDONE
