"""failover.js 콘솔 로그와 DB 를 칸(코스·가게·회원) 단위로 비교한다. 마지막으로 보낸 글과 서버가 200 을 준 글을 따로 센다."""
import csv,re,subprocess,sys,os,json
log,out=sys.argv[1],sys.argv[2]
last={}
for line in open(log,errors='ignore'):
    m=re.search(r'R,(\d+):(\d+):(\d+),(\d+),(\d+)',line)
    if not m: continue
    k=(m[1],m[2],m[3]); seq,acc=int(m[4]),int(m[5])
    r=last.setdefault(k,[0,0]); r[0]=max(r[0],seq); r[1]=max(r[1],acc)
q="""SELECT cs.course_id,cs.store_id,csm.member_id,csm.seq FROM course_store_memos csm JOIN course_stores cs ON cs.id=csm.course_store_id JOIN courses c ON c.id=cs.course_id WHERE c.title LIKE '[초안]코스%' AND csm.deleted_at IS NULL"""
rows=subprocess.check_output(['psql','host=127.0.0.1 port=15433 dbname=soboro user=sweetgirl','-At','-F,','-c',q],text=True)
db={tuple(l.split(',')[:3]):int(l.split(',')[3]) for l in rows.splitlines()}
lostLast=[k for k,(s,a) in last.items() if db.get(k,0)<s]
lostAck=[k for k,(s,a) in last.items() if a and db.get(k,0)<a]
res=dict(cells=len(last),lost_final_text=len(lostLast),lost_acked=len(lostAck))
print(json.dumps(res)); json.dump(res,open(out,'w'))
