S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; G1=${DRAFT_LAB_GEN:?}; G2=${DRAFT_REDIS_3:?}
(cd ${DRAFT_LAB_REPO:?} && node scripts/perf/generate-tokens.mjs $S/lab-k6/member-ids.txt $S/lab-k6/tokens.csv >/dev/null)
for G in $G1 $G2; do scp $O "$J" -q $S/lab-k6/mass_sockets.py $S/lab-k6/tokens.csv $S/lab-k6/courses.csv ubuntu@$G:~/k6/; done
for G in $G1 $G2; do ssh $O "$J" ubuntu@$G "sudo sysctl -q -w net.ipv4.ip_local_port_range='1024 65535' net.ipv4.tcp_tw_reuse=1; ulimit -n 200000 2>/dev/null; ulimit -n; free -m | awk '/Mem/{print \"gen avail=\"\$7\"MB\"}'"; done
ssh $O "$J" ubuntu@$LAB "cd ~/lab && HEAP_PCT=75 APP_EXTRA='--server.tomcat.max-connections=300000 --server.tomcat.accept-count=20000 --server.tomcat.threads.max=400 --logging.level.org.springframework.web=INFO --logging.level.org.springframework.messaging=INFO --logging.level.org.hibernate.type.descriptor.sql=INFO --logging.level.org.hibernate.SQL=INFO --spring.jpa.show-sql=false' ./run-app.sh sentinel none | tail -1; sudo dmesg -C"
ssh $O "$J" ubuntu@$LAB "[ -f ~/lab/sample.pid ] && kill \$(cat ~/lab/sample.pid) 2>/dev/null; nohup ~/lab/sample.sh big-sockets80k 420 >/dev/null 2>&1 < /dev/null &" &
STEPS=10000,20000,30000,40000
echo "===== 80k sockets: 생성기 2대 × ($STEPS)  $(date +%T)"
ssh $O "$J" ubuntu@$G1 "cd ~/k6 && ulimit -n 200000 && python3 mass_sockets.py $LAB:8080 $STEPS 40 tokens.csv courses.csv 2>&1 | tail -12" > $S/sock80k-g1.txt 2>&1 & P1=$!
ssh $O "$J" ubuntu@$G2 "cd ~/k6 && ulimit -n 200000 && python3 mass_sockets.py $LAB:8080 $STEPS 40 tokens.csv courses.csv 2>&1 | tail -12" > $S/sock80k-g2.txt 2>&1 & P2=$!
wait $P1 $P2
echo "--- 생성기1"; cat $S/sock80k-g1.txt; echo "--- 생성기2"; cat $S/sock80k-g2.txt
ssh $O "$J" ubuntu@$LAB "kill \$(cat ~/lab/sample.pid) 2>/dev/null; echo '--- lab (ts cpu heapMB load1 memAvailMB conns)'; awk -F'\t' 'NR>1 && (NR-2)%2==0{print \$1, \$2, \$3, \$7, \$8, \$9}' ~/lab/samples/big-sockets80k.tsv; awk -F'\t' 'NR>1{if(\$9+0>s)s=\$9; if(\$3+0>h)h=\$3; if(a==\"\"||\$8+0<a)a=\$8} END{print \"MAX conns=\"s\" heap=\"h\"MB memAvail_min=\"a\"MB\"}' ~/lab/samples/big-sockets80k.tsv; sudo dmesg | grep -c 'Killed process' | sed 's/^/lab oom: /'; pgrep -x java | wc -l | sed 's/^/java alive: /'; curl -s -m 3 localhost:8091/actuator/prometheus | grep '^draft_socket_sessions'"
echo SOCK80DONE
