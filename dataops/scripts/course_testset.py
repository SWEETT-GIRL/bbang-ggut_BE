"""코스 추천 테스트셋 추출 — 로컬 스냅샷 필드 보유 + 운영 분류 메뉴를 갖춘 가게를 상권별 정원으로 뽑는다.

입력
- 로컬 dataops DB `scratch.store_fields_<date>` (가게별 최신 스냅샷에서 뽑은 필드 요약 — 세션 SQL로 생성)
- 운영 DB 읽기 (dataops_reader, 터널 localhost:15432, 컨테이너에서 host.docker.internal)

선정 규칙
- 상권: 빵여권 도장 칸 중 11곳(서울 6동 + 강릉·춘천·속초·전주·군산). 네이버 지번주소로 매칭.
- 카테고리: 베이커리·카페,디저트·케이크전문·전문점(베이글·도넛·샌드위치…)만. 양식·호떡·아이스크림 등 제외.
- 프랜차이즈 제외(이름 정규식). 지점명(…점)은 감점만.
- tier A(votedKeyword 3↑·리뷰언급메뉴 3↑·keywordList·소개글·사진 8장↑·분류 메뉴 3↑·리뷰 30↑)부터 채우고
  정원 미달이면 B→C→D로 완화. 카테고리 그룹 라운드로빈(베이커리 우선)으로 다양성 확보.

사용
    python scripts/course_testset.py                          # matching/course_testset_<오늘>.tsv
    python scripts/course_testset.py --fields-table scratch.store_fields_20260904 --out matching/x.tsv
"""
import argparse
import csv
import datetime as dt
import io
import math
import os
import re
import subprocess

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SPOTS = [  # (sido, spot, 네이버 지번주소 시도 접두 정규식)
    ("서울", "성수동", "^서울"), ("서울", "연남동", "^서울"), ("서울", "망원동", "^서울"), ("서울", "한남동", "^서울"),
    ("서울", "공덕동", "^서울"), ("서울", "서교동", "^서울"),
    ("강원", "강릉시", "^강원"), ("강원", "춘천시", "^강원"), ("강원", "속초시", "^강원"),
    ("전북", "전주시", "^(전라북도|전북)"), ("전북", "군산시", "^(전라북도|전북)"),
]
QUOTA = {"성수동": 6, "서교동": 4, "연남동": 4, "망원동": 4, "한남동": 3, "공덕동": 2,
         "강릉시": 2, "전주시": 2, "춘천시": 1, "속초시": 1, "군산시": 1}
FRANCHISE = re.compile(
    r"파리바게|뚜레쥬르|파파이스|던킨|크리스피|닥터로빈|배스킨|투썸|스타벅스|이디야|컴포즈|메가커피|빽다방|폴바셋|할리스|커피빈"
    r"|탐앤탐스|카페베네|엔제리너스|파스쿠찌|서브웨이|맥도날드|버거킹|롯데리아|노브랜드|아티제|브레댄코|앤티앤스|명랑핫도그|성심당"
    r"|런던베이글|노티드|카페드파리|공차|설빙|디저트39|와플대학|고봉민|홍루이젠")
GROUP_ORDER = ["베이커리", "카페디저트", "케이크", "전문점"]
TIERS = [
    ("A", lambda r: r["voted_n"] >= 3 and r["an_menus_n"] >= 3 and r["kw_n"] >= 1 and r["descr_len"] > 0
        and r["top_photo_cnt"] >= 8 and r["classified"] >= 3 and r["reviews"] >= 30),
    ("B", lambda r: r["voted_n"] >= 3 and r["an_menus_n"] >= 1 and r["top_photo_cnt"] >= 5 and r["classified"] >= 3),
    ("C", lambda r: r["voted_n"] >= 1 and r["top_photo_cnt"] >= 5 and r["classified"] >= 1),
    ("D", lambda r: True),
]
INT_COLS = ("reviews", "top_photo_cnt", "total_images", "menu_cnt", "recommend_cnt", "voted_n", "an_menus_n", "kw_n",
            "descr_len", "menus_prod", "classified")
OUT_COLS = ["spot", "store_id", "naver_place_id", "prod_name", "category", "tier", "reviews", "score", "top_photo_cnt",
            "total_images", "menus_prod", "classified", "top_kinds", "recommend_cnt", "voted_top3", "review_menus_top5",
            "keywords_top3", "descr_len", "region_3rd", "addr", "snapshot_id", "naver_url"]


def read_env(path, key):
    for line in open(path, encoding="utf-8"):
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    raise SystemExit(f"{path}: {key} 없음")


def psql_csv(args, sql, env=None):
    r = subprocess.run(["docker", "exec", "-i", *env_flags(env), "bbanggut-dataops-db", "psql", *args,
                        "--csv", "-v", "ON_ERROR_STOP=1", "-f", "-"],
                       input=sql, capture_output=True, text=True, encoding="utf-8")
    if r.returncode:
        raise SystemExit("psql 실패: " + r.stderr.strip()[:500])
    return list(csv.DictReader(io.StringIO(r.stdout)))


def env_flags(env):
    return [x for k, v in (env or {}).items() for x in ("-e", f"{k}={v}")]


def local_csv(sql):
    return psql_csv(["-U", "dataops", "-d", "dataops"], sql)


def prod_csv(sql, env_path):
    user, password = read_env(env_path, "PROD_DB_USERNAME"), read_env(env_path, "PROD_DB_PASSWORD")
    return psql_csv(["-h", "host.docker.internal", "-p", "15432", "-U", user, "-d", "soboro"], sql,
                    env={"PGPASSWORD": password, "PGSSLMODE": "require",
                         "PGOPTIONS": "-c default_transaction_read_only=on"})


def local_sql(fields_table):
    spots = ",".join(f"('{s}','{p}','{x}')" for s, p, x in SPOTS)
    return f"""
WITH spots(sido, spot, pfx) AS (VALUES {spots})
SELECT sp.spot, f.store_id, f.name, f.addr, f.category, f.reviews, f.score, f.top_photo_cnt, f.total_images, f.menu_cnt, f.recommend_cnt,
  CASE WHEN jsonb_typeof(f.voted)='array' THEN jsonb_array_length(f.voted) ELSE 0 END AS voted_n,
  CASE WHEN jsonb_typeof(f.an_menus)='array' THEN jsonb_array_length(f.an_menus) ELSE 0 END AS an_menus_n,
  CASE WHEN jsonb_typeof(f.keyword_list)='array' THEN jsonb_array_length(f.keyword_list) ELSE 0 END AS kw_n,
  COALESCE(length(f.description),0) AS descr_len,
  (SELECT string_agg(v->>'displayName', '|') FROM (SELECT v FROM jsonb_array_elements(CASE WHEN jsonb_typeof(f.voted)='array' THEN f.voted ELSE '[]'::jsonb END) v ORDER BY (v->>'count')::int DESC LIMIT 3) t) AS voted_top3,
  (SELECT string_agg(m->>'label', '|') FROM (SELECT m FROM jsonb_array_elements(CASE WHEN jsonb_typeof(f.an_menus)='array' THEN f.an_menus ELSE '[]'::jsonb END) m ORDER BY (m->>'count')::int DESC LIMIT 5) t) AS review_menus_top5,
  (SELECT string_agg(k #>> '{{}}', '|') FROM (SELECT k FROM jsonb_array_elements(CASE WHEN jsonb_typeof(f.keyword_list)='array' THEN f.keyword_list ELSE '[]'::jsonb END) k LIMIT 3) t) AS keywords_top3,
  f.snapshot_id
FROM {fields_table} f JOIN spots sp ON f.addr ~ sp.pfx AND f.addr LIKE '% ' || sp.spot || '%';
"""


def prod_sql(ids):
    return f"""
SELECT s.id AS store_id, s.status, s.name AS prod_name, s.naver_place_id, s.region_2nd, s.region_3rd, s.popularity_score,
  COUNT(m.id) FILTER (WHERE m.deleted_at IS NULL) AS menus_prod,
  COUNT(m.id) FILTER (WHERE m.deleted_at IS NULL AND mc.name NOT IN ('빵','기타','요거트')) AS classified,
  (SELECT string_agg(k || ':' || n, '|') FROM (SELECT mc2.name AS k, COUNT(*) AS n FROM menu m2 JOIN menu_category mc2 ON mc2.id=m2.menu_category_id
     WHERE m2.store_id=s.id AND m2.deleted_at IS NULL AND mc2.name NOT IN ('빵','기타','요거트') GROUP BY 1 ORDER BY 2 DESC LIMIT 5) t) AS top_kinds
FROM store s LEFT JOIN menu m ON m.store_id=s.id LEFT JOIN menu_category mc ON mc.id=m.menu_category_id
WHERE s.id IN ({",".join(ids)}) GROUP BY s.id;
"""


def cat_group(category):
    c = category or ""
    if "베이커리" in c or "빵" in c or "크루아상" in c or "소금빵" in c:
        return "베이커리"
    if "케이크" in c:
        return "케이크"
    if c in ("카페,디저트", "디저트카페", "카페", "브런치카페", "브런치", "디저트"):
        return "카페디저트"
    if any(k in c for k in ("베이글", "도넛", "샌드위치", "타르트", "마카롱", "쿠키", "스콘", "와플", "파이", "크레페",
                            "츄러스", "휘낭시에", "구움과자")):
        return "전문점"
    return None


def score(r):
    name = r.get("prod_name") or ""
    return (math.log1p(r["reviews"]) + min(r["classified"], 20) / 8 + (1 if r["recommend_cnt"] > 0 else 0)
            + min(r["top_photo_cnt"], 10) / 10 + min(r["an_menus_n"], 20) / 20
            + (0.5 if cat_group(r["category"]) == "베이커리" else 0)
            - (0.3 if name.rstrip().endswith("점") else 0))


def pick(active):
    picked = []
    for spot, quota in QUOTA.items():
        chosen = []
        for label, pred in TIERS:
            taken = {c["store_id"] for c in chosen}
            cands = sorted([r for r in active if r["spot"] == spot and pred(r) and r["store_id"] not in taken],
                           key=score, reverse=True)
            groups = {}
            for r in cands:
                groups.setdefault(cat_group(r["category"]), []).append(r)
            order = [g for g in GROUP_ORDER if g in groups]
            while len(chosen) < quota and any(groups[g] for g in order):
                for g in order:
                    if groups[g] and len(chosen) < quota:
                        r = groups[g].pop(0)
                        r["tier"] = label
                        chosen.append(r)
            if len(chosen) >= quota:
                break
        pool = sum(1 for r in active if r["spot"] == spot)
        print(f"{spot}: 정원 {quota} → {len(chosen)} (tier {''.join(sorted({c['tier'] for c in chosen}))}, 풀 {pool})")
        picked += chosen
    return picked


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fields-table", default="scratch.store_fields_20260904")
    parser.add_argument("--env", default=os.path.join(REPO_ROOT, ".env"), help="PROD_DB_USERNAME/PASSWORD 가 있는 .env")
    parser.add_argument("--out", default=os.path.join(REPO_ROOT, "matching", f"course_testset_{dt.date.today():%Y%m%d}.tsv"))
    args = parser.parse_args()

    rows = local_csv(local_sql(args.fields_table))
    prod = {r["store_id"]: r for r in prod_csv(prod_sql(sorted({r["store_id"] for r in rows})), args.env)}
    for r in rows:
        r.update(prod.get(r["store_id"], {}))
        for k in INT_COLS:
            r[k] = int(r.get(k) or 0)
    active = [r for r in rows if r.get("status") == "ACTIVE"]
    bakery = [r for r in active if cat_group(r["category"]) and not FRANCHISE.search(r.get("prod_name") or r["name"] or "")]
    print(f"풀: 로컬 {len(rows)} → ACTIVE {len(active)} → 빵집형 카테고리·프랜차이즈 제외 {len(bakery)}")

    picked = pick(bakery)
    for r in picked:
        r["naver_url"] = f"https://m.place.naver.com/restaurant/{r['naver_place_id']}/home"
    with open(args.out, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=OUT_COLS, delimiter="\t", extrasaction="ignore")
        writer.writeheader()
        writer.writerows(picked)
    print(f"{len(picked)}곳 → {args.out}")


if __name__ == "__main__":
    main()
