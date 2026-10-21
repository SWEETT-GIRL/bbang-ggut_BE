"""제외 필터 정리 — 운영의 살아 있는 메뉴 중 BeverageFilter(음료·식사·비식품)에 걸리는 것을 삭제 표시(deleted_at)한다.

왜 (2026-09-19 · dataops #11)
- 서비스는 베이커리·디저트만 올린다(병합 정책 5장). 새 메뉴는 BeverageFilter 가 막지만(2026-08-12~14 신설), 그 전에 들어온 메뉴는
  그대로 남아 있다 — 운영 '빵' 종류 11만 건 중 2만 5천 건이 유자차·카페모카 같은 음료였다. 같은 판정(scripts/beverage_filter.py —
  어휘를 Java 에서 읽는다)으로 찾아 지운다. 필터가 막으므로 최신화가 다시 넣지 않는다(ServingReader 는 deleted_at IS NULL 만 읽고,
  빠진 메뉴를 menu.add 로 다시 제안해도 BeverageFilter 에서 걸린다).
- 지우는 방식은 최신화와 같다(ServingWriter.softDeleteMenu — deleted_at·updated_at 만). 되돌릴 수 있다. 사진(menu_image_file · S3)은
  건드리지 않는다 — S3 는 되돌릴 수 없어 사람이 따로 지운다(--s3-keys 로 목록만 뽑는다).

규칙
- 대상: ACTIVE 가게의 deleted_at IS NULL 메뉴 중 beverage_filter.exclusion 이 걸리는 것
- 빼고 plan 에 기록만: 사용자가 직접 등록한 메뉴(created_by_member_id) — 회원이 올린 것은 지우지 않는다
- 보류(hold, 기본 적용 안 함): 좋아요 있음 · 빵 기록에 쓰임 · 구체 종류(빵·기타·NULL·요거트가 아닌 것)로 분류돼 있음(단 '슈' 의 ○○슈페너는
  아인슈페너 등 커피라 적용) · 표본에서 나온 필터 오탐 — '소문의 가든'(가게 이름 속 '문의') · 테이크아웃 N입 묶음 · 디저트+음료 묶음 ·
  '음료(괄호 안)' 인데 괄호 밖이 디저트("샌드 카스테라(녹차)" · "두바이쫀득볼(말차)")
- 적용: 현재 deleted_at IS NULL 인 것만(그 사이 지워졌으면 건너뜀) 500건 단위. 바뀐 id 를 applied TSV 에 먼저 적고 로컬 apply_log 에
  menu.<id>.removed 로 남긴다(최신화의 메뉴 삭제 기록과 같은 모양 + reason)

사용
    python scripts/menu_exclude_cleanup.py                                               # 드라이런 → plan (운영 읽기 전용)
    python scripts/menu_exclude_cleanup.py --execute --plan matching/menu_exclude_plan_20260919.tsv
    python scripts/menu_exclude_cleanup.py --s3-keys --plan matching/menu_exclude_plan_20260919.tsv   # 지운 메뉴의 S3 키 목록(읽기 전용)
"""
import argparse
import csv
import datetime as dt
import os
import re
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import beverage_filter as bf  # noqa: E402
from menu_classifier import GENERIC_KINDS, classify, doubt  # noqa: E402
from menu_reclassify import local_exec, sql_json, writer_csv, read_env  # noqa: E402
from store_tags import prod_csv  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GENERIC = {"빵", "기타", "NULL", "요거트"}
PLAN_FIELDS = ["menu_id", "store_id", "kind", "name", "price", "reason", "hold"]


DESSERTISH = re.compile(r"볼|젤리|쿠키|케이크|케익|떡|빵|과자|마카롱|스콘")
TAKEOUT_PACK = re.compile(r"\d+\s*입")
BAKERY_WORDS = re.compile(r"빵|케이크|케익|쿠키|타르트|스콘|마카롱|떡|과자|와플|크로플|도넛|머핀|베이글|레터링|생일")   # 식사 확장(2026-09-19) 보류용


def dessert_part(text):
    kind, _, word = classify(text)
    return kind is not None and kind not in GENERIC_KINDS and not doubt(text, kind, word)   # 아인슈페너의 '슈' 같은 오판은 세지 않는다


def hold_reason(r, likes, recorded):
    """적용하지 않고 남겨 둘 이유. 아래 오탐들은 2026-09-19 plan 표본에서 나왔다 — Java BeverageFilter 에도 같은 오탐이 있다."""
    name = r["name"]
    if int(r["id"]) in likes:
        return "좋아요"
    if int(r["id"]) in recorded:
        return "빵 기록"
    if r["kind"] not in GENERIC and not (r["kind"] == "슈" and "슈페너" in name):
        return f"구체 종류({r['kind']})"
    if "소문의" in name:                                          # "소문의 가든" 체인 — 가게 이름 속 '문의'
        return "오탐: 소문의(가게 이름)"
    if r["reason"] == "용량·테이크아웃" and not bf.re.search(r"\d+(\.\d+)?\s*(ml|리터|l\b)", name.lower()) and TAKEOUT_PACK.search(name):
        return "오탐: 테이크아웃 묶음 상품"                         # "테이크아웃 16입 베스트"
    parts = name.split("+")
    if len(parts) > 1 and any(dessert_part(p) for p in parts[:-1]):
        return "묶음(디저트+음료)"                                   # "[답례품] 팥호두과자+음료 (8개입)"
    if r["reason"] == "음료(괄호 안)" and (bf._last_end(bf._strip_parens(name.lower()), bf.DESSERT_TYPE) >= 0
                                        or DESSERTISH.search(bf._strip_parens(name))):
        return "괄호 밖 디저트"                                       # "두바이쫀득볼(말차)"
    if r["reason"].startswith("식사") and BAKERY_WORDS.search(name):
        return "식사인데 빵·디저트 말"                               # "옛날사라다빵[… 브런치]" · "찐빵 만두 만원팩" — 빵 곁들인 식사도 남는다
    return ""


def ids_with(sql_template, ids, env):
    out = set()
    for s in range(0, len(ids), 5000):
        arr = "{" + ",".join(ids[s:s + 5000]) + "}"
        out |= {int(r["menu_id"]) for r in prod_csv(sql_template.format(arr=arr), env)}
    return out


def dry_run(args):
    menus = prod_csv(
        "SELECT m.id, m.store_id, m.name, m.price, COALESCE(mc.name, 'NULL') AS kind, (m.created_by_member_id IS NOT NULL) AS user_added "
        "FROM menu m JOIN store s ON s.id = m.store_id AND s.status = 'ACTIVE' LEFT JOIN menu_category mc ON mc.id = m.menu_category_id "
        "WHERE m.deleted_at IS NULL;", args.env)
    hits = []
    for m in menus:
        reason = bf.exclusion(m["name"])
        if reason:
            m["reason"] = reason
            hits.append(m)
    ids = [m["id"] for m in hits]
    likes = ids_with("SELECT DISTINCT menu_id FROM menu_like WHERE menu_id = ANY('{arr}'::bigint[]) "
                     "UNION SELECT id FROM menu WHERE id = ANY('{arr}'::bigint[]) AND COALESCE(like_count, 0) > 0;", ids, args.env)
    recorded = ids_with("SELECT DISTINCT menu_id FROM bread_record_tag WHERE menu_id = ANY('{arr}'::bigint[]);", ids, args.env)
    plan = []
    for m in hits:
        user_added = m["user_added"] in ("t", "true", "True")
        plan.append({"menu_id": m["id"], "store_id": m["store_id"], "kind": m["kind"], "name": m["name"], "price": m["price"],
                     "reason": m["reason"], "hold": "사용자 등록" if user_added else hold_reason(m, likes, recorded)})
    with open(args.out, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=PLAN_FIELDS, delimiter="\t")
        w.writeheader()
        w.writerows(plan)
    auto = [r for r in plan if not r["hold"]]
    held = [r for r in plan if r["hold"]]
    print(f"살아 있는 메뉴 {len(menus):,}건 (ACTIVE 가게) → 제외 필터에 걸림 {len(plan):,}건")
    print(f"  적용 {len(auto):,}건 — 이유별 {dict(Counter(r['reason'] for r in auto))}")
    print(f"    지금 종류별 {dict(Counter(r['kind'] for r in auto).most_common(8))}")
    print(f"  보류 {len(held):,}건 — {dict(Counter(r['hold'].split('(')[0] for r in held))}")
    print(f"    구체 종류 보류 상위 {Counter(r['kind'] for r in held if r['hold'].startswith('구체')).most_common(10)}")
    print(f"plan → {args.out}")


def execute(args):
    writer_pw = os.environ.get("DATAOPS_WRITER_PASSWORD") or read_env(args.env, "DATAOPS_WRITER_PASSWORD")
    if not writer_pw:
        raise SystemExit("DATAOPS_WRITER_PASSWORD 필요 (환경변수 또는 --env 파일)")
    rows = [r for r in csv.DictReader(open(args.plan, encoding="utf-8"), delimiter="\t") if not r["hold"]]
    print(f"plan {args.plan}: 적용 대상 {len(rows):,}건 (보류·사용자 등록 제외)")
    applied_path = args.plan[:-len(".tsv")] + "_applied.tsv"
    applied = skipped = 0
    with open(applied_path, "a", encoding="utf-8", newline="") as log:
        out = csv.writer(log, delimiter="\t")
        for start in range(0, len(rows), args.chunk):
            chunk = rows[start:start + args.chunk]
            arr = "{" + ",".join(str(int(r["menu_id"])) for r in chunk) + "}"
            done = {row["id"] for row in writer_csv(
                "UPDATE menu SET deleted_at = now(), updated_at = now() "
                f"WHERE id = ANY('{arr}'::bigint[]) AND deleted_at IS NULL AND created_by_member_id IS NULL RETURNING id;", writer_pw)}
            ok = [r for r in chunk if r["menu_id"] in done]
            for r in ok:   # 로컬 기록보다 먼저 남긴다
                out.writerow([r["menu_id"], r["store_id"], r["kind"], r["reason"], r["name"]])
            log.flush()
            if ok:
                local_exec("INSERT INTO apply_log (store_id, target, old_value, new_value, origin, applied_at) VALUES " + ", ".join(
                    f"({int(r['store_id'])}, 'menu.{int(r['menu_id'])}.removed', "
                    f"{sql_json({'name': r['name'], 'price': int(r['price']) if r['price'] else None, 'category': r['kind']})}, "
                    f"{sql_json({'deleted': True, 'reason': '제외 필터 정리 — ' + r['reason']})}, 'PROPOSAL', now())" for r in ok) + ";")
            applied += len(ok)
            skipped += len(chunk) - len(ok)
            print(f"  {start + len(chunk):,}/{len(rows):,} — 삭제 표시 {applied:,}, 건너뜀 {skipped:,}", flush=True)
    print(f"완료 — 삭제 표시 {applied:,}건, 건너뜀(이미 지워짐 등) {skipped:,}건 · 목록 → {applied_path}")


def s3_keys(args):
    applied_path = args.plan[:-len(".tsv")] + "_applied.tsv"
    with open(applied_path, encoding="utf-8", newline="") as f:   # 이름에 줄바꿈이 든 메뉴가 있어 줄이 아니라 TSV 행으로 읽는다
        ids = [row[0] for row in csv.reader(f, delimiter="\t") if row]
    out_path = args.plan[:-len(".tsv")] + "_s3_keys.txt"
    n = 0
    with open(out_path, "w", encoding="utf-8") as f:
        for s in range(0, len(ids), 5000):
            arr = "{" + ",".join(ids[s:s + 5000]) + "}"
            for r in prod_csv(f"SELECT aws_key, resized_key_320, resized_key_640, resized_key_1024 FROM menu_image_file "
                              f"WHERE menu_id = ANY('{arr}'::bigint[]);", args.env):
                for k in r.values():
                    if k:
                        f.write(k + "\n")
                        n += 1
    print(f"지운 메뉴 {len(ids):,}건의 S3 키 {n:,}개 → {out_path} (읽기 전용 — 삭제는 사람이 한다)")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", default=os.path.join(REPO_ROOT, ".env"))
    parser.add_argument("--out", default=os.path.join(REPO_ROOT, "matching", f"menu_exclude_plan_{dt.date.today():%Y%m%d}.tsv"))
    parser.add_argument("--execute", action="store_true", help="plan 의 보류 아닌 행을 운영에 삭제 표시")
    parser.add_argument("--s3-keys", action="store_true", help="지운 메뉴의 S3 키 목록만 뽑는다")
    parser.add_argument("--plan", help="--execute · --s3-keys 에 쓸 plan TSV (기본: --out)")
    parser.add_argument("--chunk", type=int, default=500)
    args = parser.parse_args()
    args.plan = args.plan or args.out
    if args.execute:
        execute(args)
    elif args.s3_keys:
        s3_keys(args)
    else:
        dry_run(args)


if __name__ == "__main__":
    main()
