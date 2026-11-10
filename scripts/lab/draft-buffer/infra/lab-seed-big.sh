echo "lab cpu=$(nproc) mem=$(free -m | awk '/Mem/{print $2}')MB pg=$(systemctl is-active postgresql)"
pkill -f api-0.0.1-SNAPSHOT.jar 2>/dev/null
sudo -u postgres psql -qc "ALTER SYSTEM SET max_connections=300" -c "ALTER SYSTEM SET shared_buffers='2GB'" -c "ALTER SYSTEM SET synchronous_commit=off"; sudo systemctl restart postgresql; sleep 3
export PGPASSWORD=lab; Q="psql -h 127.0.0.1 -U sweetgirl -d soboro -v ON_ERROR_STOP=1 -At"
$Q -c "INSERT INTO store (name, address, location, source, status, naver_enrich_attempts, created_at, updated_at) SELECT '[초안]가게b'||g, '서울 '||g, ST_SetSRID(ST_MakePoint(126.9+random()*0.2, 37.5+random()*0.1),4326)::geography, 'ADMIN_REGISTERED', 'ACTIVE', 0, now(), now() FROM generate_series(1,3000) g WHERE (SELECT count(*) FROM store) < 1000" | tail -1
$Q -v courses=1500 -f ~/lab/seed-draft-courses.sql >/dev/null && echo seed ok
echo "members=$($Q -c "select count(*) from member where nickname like '[초안]회원%'") courses=$($Q -c "select count(*) from courses where title like '[초안]코스%'") course_stores=$($Q -c "select count(*) from course_stores") max_conn=$($Q -c "show max_connections")"
$Q -c "SELECT id FROM member WHERE nickname LIKE '[초안]회원%' ORDER BY id" > /tmp/member-ids.txt
$Q -F, -c "SELECT c.id,(SELECT string_agg(store_id::text,'|' ORDER BY visit_order) FROM course_stores WHERE course_id=c.id AND deleted_at IS NULL),(SELECT string_agg(member_id::text,'|' ORDER BY joined_at) FROM course_members WHERE course_id=c.id AND deleted_at IS NULL) FROM courses c WHERE c.title LIKE '[초안]코스%' AND c.deleted_at IS NULL ORDER BY c.id" > /tmp/courses.csv
wc -l /tmp/member-ids.txt /tmp/courses.csv | head -2
