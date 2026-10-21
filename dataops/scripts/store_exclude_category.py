"""카테고리 제외 정리 — 네이버 카테고리가 빵집이 아닌 업종인 운영 가게를 숨긴다(store.status DELETED).

왜 (2026-09-19)
- LOCALDATA 신규 유입이 카테고리 경로 부분일치 · 메뉴 한 줄(찐빵)로 만두집을 빵집으로 받아들였다. 메뉴 제외 정리로 만두 메뉴가
  지워지자 찐빵만 남아 빵집 점수가 0.5 를 넘는 가게까지 생겼다(v5 초안, 만두집 약 80곳). 사용자 결정: 만두집은 전부 숨긴다.
- 숨기는 방식은 최신화의 서비스 제외 정리와 같다(ServingWriter.softDeleteStore — status·updated_at 만). 되돌릴 수 있다
  (ServingWriter.reactivateStore). 메뉴·사진·빵 기록은 건드리지 않는다.

규칙
- 대상: 로컬 place_snapshot 최신본의 PlaceDetailBase.category 가 --leaves 중 하나인 ACTIVE 가게 (기본: 만두 · 칼국수,만두)
- plan 에는 빵 기록·코스·좋아요 수와 빵처럼 보이는 메뉴를 같이 적는다(검수용). 보류 없음 — 업종 단위 결정이다
- 적용: 지금 ACTIVE 인 것만(그 사이 바뀌었으면 건너뜀). 바뀐 id 를 applied TSV 에 먼저 적고 로컬 apply_log 에 store.status 로 남긴다

사용
    python scripts/store_exclude_category.py                                         # 드라이런 → plan (운영 읽기 전용)
    python scripts/store_exclude_category.py --execute --plan <plan.tsv> --limit 5   # 시험 반영 5곳
    python scripts/store_exclude_category.py --execute --plan <plan.tsv>             # 나머지 전부 (applied 에 있는 곳은 건너뜀)
"""
import argparse
import csv
import datetime as dt
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from menu_reclassify import local_exec, sql_json, writer_csv, read_env  # noqa: E402
from store_tags import prod_csv  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_LEAVES = ["만두", "칼국수,만두"]
BREADISH = ("빵", "찐빵", "도넛", "도너츠", "고로케", "꽈배기", "케이크", "카스테라", "만쥬", "호떡")
FIELDS = ["store_id", "name", "naver_name", "category", "source", "created_at", "address",
          "records", "courses", "likes", "menus", "breadish_menus"]


def local_csv(sql):
    out = subprocess.run(["docker", "exec", "-i", "bbanggut-dataops-db", "psql", "-U", "dataops", "-d", "dataops",
                          "--csv", "-c", sql], capture_output=True, text=True, check=True).stdout
    return list(csv.DictReader(out.splitlines()))


def counts(sql, env):
    return {r["store_id"]: int(r["n"]) for r in prod_csv(sql, env)}


def dry_run(args):
    leaves = ", ".join("'" + x.replace("'", "''") + "'" for x in args.leaves)
    cats = local_csv(
        "WITH latest AS (SELECT DISTINCT ON (store_id) store_id, payload FROM place_snapshot WHERE source = 'NAVER' "
        "ORDER BY store_id, fetched_at DESC) "
        "SELECT l.store_id, b.value->>'name' AS naver_name, b.value->>'category' AS category "
        f"FROM latest l, jsonb_each(l.payload) b WHERE b.key LIKE 'PlaceDetailBase:%' AND b.value->>'category' IN ({leaves});")
    by_id = {r["store_id"]: r for r in cats}
    if not by_id:
        raise SystemExit("대상 없음")
    ids = ",".join(by_id)
    stores = prod_csv(f"SELECT id, name, status, source, created_at::date AS created_at, address FROM store "
                      f"WHERE id IN ({ids}) AND status = 'ACTIVE' ORDER BY id;", args.env)
    records = counts(f"SELECT store_id, count(*) n FROM bread_record WHERE deleted_at IS NULL AND store_id IN ({ids}) GROUP BY 1;", args.env)
    courses = counts(f"SELECT store_id, count(*) n FROM course_stores WHERE deleted_at IS NULL AND store_id IN ({ids}) GROUP BY 1;", args.env)
    likes = counts(f"SELECT m.store_id, count(*) n FROM menu_like l JOIN menu m ON m.id = l.menu_id WHERE m.store_id IN ({ids}) GROUP BY 1;", args.env)
    menus = {}
    for r in prod_csv(f"SELECT store_id, name FROM menu WHERE deleted_at IS NULL AND store_id IN ({ids}) ORDER BY store_id, id;", args.env):
        menus.setdefault(r["store_id"], []).append(r["name"])

    plan = args.plan or os.path.join(REPO_ROOT, "matching", f"store_exclude_category_plan_{dt.date.today():%Y%m%d}.tsv")
    with open(plan, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS, delimiter="\t")
        w.writeheader()
        for s in stores:
            sid, names = s["id"], menus.get(s["id"], [])
            w.writerow({"store_id": sid, "name": s["name"], "naver_name": by_id[sid]["naver_name"],
                        "category": by_id[sid]["category"], "source": s["source"], "created_at": s["created_at"],
                        "address": s["address"], "records": records.get(sid, 0), "courses": courses.get(sid, 0),
                        "likes": likes.get(sid, 0), "menus": len(names),
                        "breadish_menus": " · ".join(n for n in names if any(k in n for k in BREADISH))[:300]})
    print(f"네이버 카테고리 {args.leaves} 가게 {len(by_id):,}곳 중 운영 ACTIVE {len(stores):,}곳 → {plan}")


def execute(args):
    writer_pw = os.environ.get("DATAOPS_WRITER_PASSWORD") or read_env(args.env, "DATAOPS_WRITER_PASSWORD")
    if not writer_pw:
        raise SystemExit("DATAOPS_WRITER_PASSWORD 필요 (환경변수 또는 --env 파일)")
    rows = list(csv.DictReader(open(args.plan, encoding="utf-8"), delimiter="\t"))
    applied_path = args.plan[:-len(".tsv")] + "_applied.tsv"
    done_before = set()
    if os.path.exists(applied_path):
        with open(applied_path, encoding="utf-8", newline="") as f:
            done_before = {row[0] for row in csv.reader(f, delimiter="\t") if row}
    rows = [r for r in rows if r["store_id"] not in done_before]
    if args.limit:
        rows = rows[:args.limit]
    print(f"plan {args.plan}: 이번 적용 {len(rows):,}곳 (이미 적용 {len(done_before):,}곳 제외)")
    if not rows:
        return
    reason = "카테고리 제외 정리 — 네이버 카테고리 " + "·".join(sorted({r["category"] for r in rows}))
    arr = "{" + ",".join(str(int(r["store_id"])) for r in rows) + "}"
    done = {row["id"] for row in writer_csv(
        "UPDATE store SET status = 'DELETED', updated_at = now() "
        f"WHERE id = ANY('{arr}'::bigint[]) AND status = 'ACTIVE' RETURNING id;", writer_pw)}
    ok = [r for r in rows if r["store_id"] in done]
    with open(applied_path, "a", encoding="utf-8", newline="") as log:   # 로컬 기록보다 먼저 남긴다
        out = csv.writer(log, delimiter="\t")
        for r in ok:
            out.writerow([r["store_id"], r["name"], r["category"]])
    if ok:
        local_exec("INSERT INTO apply_log (store_id, target, old_value, new_value, origin, applied_at) VALUES " + ", ".join(
            f"({int(r['store_id'])}, 'store.status', {sql_json('ACTIVE')}, "
            f"{sql_json({'status': 'DELETED', 'evidence': reason})}, 'PROPOSAL', now())" for r in ok) + ";")
    print(f"완료 — 숨김 {len(ok):,}곳, 건너뜀(이미 ACTIVE 아님) {len(rows) - len(ok):,}곳 · 목록 → {applied_path}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--env", default=os.path.join(REPO_ROOT, ".env"))
    ap.add_argument("--leaves", nargs="+", default=DEFAULT_LEAVES, help="네이버 카테고리(마지막 분류 표기 그대로)")
    ap.add_argument("--plan", help="드라이런: 출력 경로 / --execute: 적용할 plan")
    ap.add_argument("--execute", action="store_true", help="운영 반영 (dataops_writer)")
    ap.add_argument("--limit", type=int, help="--execute 로 이번에 적용할 최대 가게 수 (시험 반영)")
    args = ap.parse_args()
    if args.execute:
        if not args.plan:
            raise SystemExit("--execute 에는 --plan 이 필요하다")
        execute(args)
    else:
        dry_run(args)


if __name__ == "__main__":
    main()
