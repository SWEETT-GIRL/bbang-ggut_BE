K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_LAB_GEN:?}
ssh $O "$J" ubuntu@$GEN "cd ~/k6 && ulimit -n 200000 && STAGES='15s:1280,70s:1280' SEARCH_RATE=1 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --compatibility-mode=base buffered.js > results/diag.txt 2>&1" & KP=$!
sleep 40
ssh $O "$J" ubuntu@$LAB 'PID=$(cat ~/lab/app.pid); rm -f /tmp/js*.txt; for i in 1 2 3 4 5 6 7 8; do jstack $PID > /tmp/js$i.txt 2>/dev/null; sleep 1.5; done
top -b -n1 -H -p $PID | sed -n "8,20p" | awk "{print \$9, \$12, \$13}" | sort -rn | head -8
python3 - <<"PY"
import glob,collections
st=collections.Counter(); run=collections.Counter(); wait=collections.Counter(); allrun=collections.Counter()
for f in glob.glob("/tmp/js*.txt"):
    for blk in open(f).read().split("\n\n"):
        if not blk.startswith("\""): continue
        name=blk.split("\"")[1]; lines=blk.split("\n"); state=(lines[1].strip().replace("java.lang.Thread.State: ","") if len(lines)>1 else "?")
        fr=[l.strip()[3:].split("(")[0] for l in lines if l.strip().startswith("at ")]
        grp=name.split("-exec")[0].rstrip("0123456789-") if "exec" in name else name.rstrip("0123456789-")
        if state.startswith("RUNNABLE") and fr and not fr[0].startswith(("sun.nio.ch.EPoll","sun.nio.ch.Net.accept","java.net.PlainSocketImpl","sun.nio.ch.SocketDispatcher.read0","io.netty.channel.epoll.Native.epollWait")):
            allrun[grp]+=1
            if name.startswith("http-nio-8080-exec"): run[" < ".join(fr[:3])]+=1
        if name.startswith("http-nio-8080-exec"):
            st[state]+=1
            if not state.startswith("RUNNABLE"):
                k=[x for x in fr if not x.startswith(("java.util.concurrent","jdk.internal","java.lang"))][:2]; wait[" < ".join(k)]+=1
print("--- http 스레드 상태(8회 합):", dict(st))
print("--- 실제로 CPU 쓰는(RUNNABLE) 스레드 그룹:", allrun.most_common(8))
print("--- RUNNABLE http 스레드 top 프레임"); [print(v,k[:170]) for k,v in run.most_common(10)]
print("--- 대기 중 http 스레드가 기다리는 것"); [print(v,k[:170]) for k,v in wait.most_common(6)]
PY'
wait $KP
