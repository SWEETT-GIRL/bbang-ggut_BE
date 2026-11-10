K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_LAB_GEN:?}
ssh $O "$J" ubuntu@$GEN "cd ~/k6 && ulimit -n 200000 && STAGES='15s:1280,75s:1280' SEARCH_RATE=1 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --compatibility-mode=base buffered.js > results/diag.txt 2>&1" & KP=$!
sleep 30
ssh $O "$J" ubuntu@$LAB 'PID=$(cat ~/lab/app.pid); rm -f /tmp/app.jfr; jcmd $PID JFR.start duration=40s filename=/tmp/app.jfr settings=profile >/dev/null; sleep 46; ls -la /tmp/app.jfr | awk "{print \$5/1048576 \" MB jfr\"}"
jfr print --events jdk.ExecutionSample --json /tmp/app.jfr > /tmp/es.json 2>/dev/null
python3 - <<"PY"
import json,collections
ev=json.load(open("/tmp/es.json"))["recording"]["events"]
top=collections.Counter(); ours=collections.Counter(); lib=collections.Counter(); thr=collections.Counter()
def name(f): m=f["method"]; return m["type"]["name"]+"."+m["name"]
def bucket(frames):
    names=[name(f) for f in frames]
    for key,label in (("io.jsonwebtoken","JWT 파싱(jjwt)"),("ch.qos.logback","로그(logback)"),("com.fasterxml.jackson","JSON(jackson)"),("org.hibernate","Hibernate"),("org.postgresql","JDBC"),("io.lettuce","Redis 클라이언트(lettuce)"),("org.springframework.data.redis","Spring Data Redis"),("org.springframework.security","Spring Security"),("org.springframework.messaging","STOMP/메시징"),("org.springframework.web","Spring MVC"),("org.apache.catalina","Tomcat"),("org.apache.coyote","Tomcat"),("org.apache.tomcat","Tomcat"),("io.micrometer","계측(micrometer)"),("io.opentelemetry","OTel"),("java.util.regex","정규식")):
        if any(n.startswith(key) for n in names[:12]): return label
    return "기타 "+names[0].rsplit(".",2)[0][:40]
for e in ev:
    v=e["values"]; fr=v["stackTrace"]["frames"]
    top[name(fr[0])]+=1; lib[bucket(fr)]+=1; thr[(v.get("sampledThread") or {}).get("javaName","?").rstrip("0123456789").rstrip("-")]+=1
    for f in fr:
        n=name(f)
        if n.startswith("sweetgirl"): ours[n]+=1; break
n=len(ev); print("samples",n)
print("--- CPU 가 쓰이는 곳(스택 상단 12프레임 기준 분류)"); [print(f"{v*100//n:3d}% {k}") for k,v in lib.most_common(12)]
print("--- 스레드 그룹"); [print(f"{v*100//n:3d}% {k}") for k,v in thr.most_common(6)]
print("--- 우리 코드 중 가장 안쪽 프레임"); [print(f"{v*100//n:3d}% {k}") for k,v in ours.most_common(10)]
print("--- 최상단 메서드"); [print(f"{v*100//n:3d}% {k}") for k,v in top.most_common(12)]
PY'
wait $KP
