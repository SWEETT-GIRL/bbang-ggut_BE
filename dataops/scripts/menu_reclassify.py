"""'빵'·'기타'·종류 없음 재분류 — 운영 메뉴 중 뭉뚱그려진 종류에 있는 이름을 끝단어 분류기(MenuCategoryClassifier)로 다시 분류해
구체 종류로 옮긴다. 기본은 드라이런(plan TSV 만 생성). --execute 로 운영 UPDATE (writer 자격) + 로컬 apply_log.

왜 다시 도나 (2차, 2026-09-19 · dataops #11)
- 최신화 루프는 이미 있는 메뉴의 종류를 비교하지 않는다(ReconcileService.reconcileMenus — 가격·설명만). 종류는 새 메뉴를 넣을 때
  한 번 정해지고(ProposalCommandService → classify), 그 뒤 사전이 늘어도 기존 메뉴는 그대로다.
- 09-04 1차 재분류(26,485건) 뒤에 Java 사전에 24종(단팥빵·카스테라·호두과자·한과·초콜릿 …)과 별칭 넷이 더해졌다.
- 분류기가 못 푼 새 메뉴는 종류 없음(NULL)으로 들어가 본 앱 AI 스케줄러를 기다리는데, 그 스케줄러가 운영에서 09-09 부터 꺼져 있다.

규칙
- 대상: deleted_at IS NULL, 종류 '빵' · '기타' · NULL, 가게 ACTIVE
- 분류: scripts/menu_classifier.py — 사전을 Java 파일에서 직접 읽는다(1차 때는 손으로 베낀 사본이라 어긋났다). 결과가 None 이거나
  지금 종류와 같으면 변경 없음
- 제외 ①: 사용자가 직접 등록한 메뉴(created_by_member_id 있음) — 사용자가 고른 종류를 덮어쓰지 않는다 (plan 에 user_added=Y 로만 남김)
- 제외 ②: 음료 제품타입 단어가 종류 단어와 같거나 더 뒤에서 끝나는 이름(요거트스무디, 녹차라떼 …) — 음료 소프트삭제 트랙에 넘긴다 (beverage=Y)
- 검토 ③: 판정을 믿기 어려우면(menu_classifier.doubt) review 에 이유를 적고 기본 적용에서 뺀다 — 한 글자·'샌드' 단어(키슈→슈 ·
  1번 세트→번 · 버터샌드→샌드위치), 닭강정→한과, 핫초콜릿→초콜릿, 식사 오믈렛, 끝말이 ○○빵인데 속재료가 종류가 된 것(인절미빵→떡 ·
  슈크림찐빵→슈). Java 에도 같은 오판이 있다. 눈으로 보고 --include-review 로 넣는다
- 적용: menu.menu_category_id 를 바꾸되 현재 값이 plan 의 from 과 같을 때만(NULL 포함, 그 사이 바뀌었으면 건너뜀). 500건 단위 커밋.
  운영 청크를 커밋한 뒤 실제로 바뀐 메뉴만 로컬 apply_log 에 target='menu.category' 로 남긴다. 바뀐 id 는 로컬 기록 전에 applied TSV 에
  먼저 적으므로, 로컬 기록이 실패해도 그 파일로 다시 남길 수 있다 (localdata_region_fix 와 같은 규율 예외 — 판정층 proposal 이 아닌 일회성 정정)

산출
- matching/menu_reclassify_plan_<date>.tsv : menu_id, store_id, from_bucket, from_category_id, to_kind, to_category_id, name, matched_word,
  user_added, beverage, review
- 표준 출력: 출처별·종류별 건수, 제외·검토 건수, "분류 메뉴 3개 이상 가게" 전후

사용
    python scripts/menu_reclassify.py                                                        # 드라이런 → plan (.env 의 PROD_DB_* 읽기 전용)
    python scripts/menu_reclassify.py --execute --plan matching/menu_reclassify_plan_20260919.tsv   # .env 의 DATAOPS_WRITER_PASSWORD
"""
import argparse
import csv
import datetime as dt
import io
import json
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from menu_classifier import classify, doubt  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 음료 제품타입 단어 — 종류 단어와 같거나 더 뒤에서 끝나면 음료로 보고 재분류에서 뺀다 (BeverageFilter 끝단어 원리 축약)
BEVERAGE_WORDS = [
    "라떼", "라테", "아메리카노", "커피", "에스프레소", "콜드브루", "카푸치노", "마끼아또", "마키아토", "마끼야또", "플랫화이트",
    "에이드", "주스", "쥬스", "스무디", "쉐이크", "셰이크", "프라페", "프라푸치노", "밀크티", "아이스티", "뱅쇼", "코코아", "핫초코",
    "미숫가루", "아샷추", "소다", "맥주", "와인", "음료", "드링크", "하이볼", "칵테일", "사이다", "탄산수", "레모네이드",  # "콜라"는 쇼콜라(갸또 쇼콜라) 오탐이라 뺌
    "블렌디드", "카모마일", "히비스커스", "얼그레이", "페퍼민트", "루이보스", "핸드드립", "더치", "우유", "밀크",
]
BEVERAGE_END_ONLY = ["차", "티"]  # 짧아서 이름 끝에서만 인정 (유자차·밀크티) — 녹차케이크는 케이크가 뒤에서 끝나 무사
PAREN = re.compile(r"\([^()]*\)")
NO_AXIS = {"빵", "기타", "요거트"}
PLAN_FIELDS = ["menu_id", "store_id", "from_bucket", "from_category_id", "to_kind", "to_category_id", "name", "matched_word",
               "user_added", "beverage", "review"]


def beverage_end(menu_name):
    name = PAREN.sub(" ", (menu_name or "").lower())
    best = -1
    for w in BEVERAGE_WORDS:
        i = name.rfind(w)
        if i >= 0:
            best = max(best, i + len(w))
    stripped = name.rstrip()
    for w in BEVERAGE_END_ONLY:
        if stripped.endswith(w):
            best = max(best, len(stripped))
    return best


def read_env(path, key):
    for line in open(path, encoding="utf-8"):
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    return None


def prod_csv(sql, env_path):
    user, pw = read_env(env_path, "PROD_DB_USERNAME"), read_env(env_path, "PROD_DB_PASSWORD")
    r = subprocess.run(["docker", "exec", "-i", "-e", f"PGPASSWORD={pw}", "-e", "PGSSLMODE=require",
                        "-e", "PGOPTIONS=-c default_transaction_read_only=on", "bbanggut-dataops-db",
                        "psql", "-h", "host.docker.internal", "-p", "15432", "-U", user, "-d", "soboro",
                        "--csv", "-v", "ON_ERROR_STOP=1", "-f", "-"],
                       input=sql, capture_output=True, text=True, encoding="utf-8")
    if r.returncode:
        raise SystemExit("psql 실패: " + r.stderr.strip()[:500])
    return list(csv.DictReader(io.StringIO(r.stdout)))


def dry_run(args):
    categories = {r["name"]: int(r["id"]) for r in prod_csv("SELECT id, name FROM menu_category;", args.env)}
    menus = prod_csv(
        "SELECT m.id, m.store_id, m.name, COALESCE(mc.name, 'NULL') AS bucket, mc.id AS bucket_id, (m.created_by_member_id IS NOT NULL) AS user_added "
        "FROM menu m LEFT JOIN menu_category mc ON mc.id = m.menu_category_id JOIN store s ON s.id = m.store_id "
        "WHERE m.deleted_at IS NULL AND s.status = 'ACTIVE' AND (mc.name IN ('빵','기타') OR m.menu_category_id IS NULL);", args.env)
    classified_now = {r["store_id"]: int(r["classified"]) for r in prod_csv(
        "SELECT s.id AS store_id, COUNT(*) FILTER (WHERE mc.name NOT IN ('빵','기타','요거트')) AS classified "
        "FROM store s LEFT JOIN menu m ON m.store_id = s.id AND m.deleted_at IS NULL "
        "LEFT JOIN menu_category mc ON mc.id = m.menu_category_id WHERE s.status = 'ACTIVE' GROUP BY s.id;", args.env)}

    plan, missing = [], Counter()
    gains = defaultdict(int)
    for m in menus:
        kind, kind_end, word = classify(m["name"])
        if kind is None or kind == m["bucket"]:
            continue
        if kind not in categories:
            missing[kind] += 1
            continue
        user_added = m["user_added"] in ("t", "true", "True")
        beverage = beverage_end(m["name"]) >= kind_end
        review = doubt(m["name"], kind, word)
        row = {"menu_id": m["id"], "store_id": m["store_id"], "from_bucket": m["bucket"], "from_category_id": m["bucket_id"],
               "to_kind": kind, "to_category_id": categories[kind], "name": m["name"], "matched_word": word,
               "user_added": "Y" if user_added else "", "beverage": "Y" if beverage else "", "review": review}
        if not user_added and not beverage and not review and kind not in NO_AXIS:
            gains[m["store_id"]] += 1
        plan.append(row)

    with open(args.out, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=PLAN_FIELDS, delimiter="\t")
        w.writeheader()
        w.writerows(plan)

    auto = [r for r in plan if not r["user_added"] and not r["beverage"] and not r["review"]]
    held = [r for r in plan if not r["user_added"] and not r["beverage"] and r["review"]]
    by_kind, by_from = Counter(r["to_kind"] for r in auto), Counter(r["from_bucket"] for r in auto)
    before = sum(1 for v in classified_now.values() if v >= 3)
    after = sum(1 for sid, v in classified_now.items() if v + gains.get(sid, 0) >= 3)
    print(f"대상 메뉴 {len(menus):,}건 (ACTIVE 가게, 빵·기타·NULL — {dict(Counter(m['bucket'] for m in menus))}) → plan {len(plan):,}건")
    print(f"  자동 적용 {len(auto):,}건 ({', '.join(f'{b}→ {n:,}' for b, n in by_from.most_common())}) · 제외: 사용자 등록 "
          f"{sum(1 for r in plan if r['user_added']):,} · 음료 {sum(1 for r in plan if r['beverage'] and not r['user_added']):,} · "
          f"검토 대기 {len(held):,} {dict(Counter(r['review'] for r in held))}")
    if missing:
        print(f"  운영 menu_category 에 없는 종류(건너뜀): {dict(missing)}")
    print(f"  분류 메뉴 3개 이상 가게: {before:,} → {after:,} (ACTIVE {len(classified_now):,}곳, {100 * before / len(classified_now):.1f}% → {100 * after / len(classified_now):.1f}%)")
    print("  --- 종류별 (자동 적용분) ---")
    for kind, n in by_kind.most_common():
        print(f"    {kind:8} {n:>7,}")
    print(f"plan → {args.out}")


def writer_csv(sql, password):
    """운영에 dataops_writer 로 SQL 한 문장 — 문장 하나라 psql 자동 커밋 단위가 곧 청크 트랜잭션이다. --csv 결과 행을 돌려준다"""
    r = subprocess.run(["docker", "exec", "-i", "-e", f"PGPASSWORD={password}", "-e", "PGSSLMODE=require", "bbanggut-dataops-db",
                        "psql", "-h", "host.docker.internal", "-p", "15432", "-U", "dataops_writer", "-d", "soboro",
                        "-q", "--csv", "-v", "ON_ERROR_STOP=1", "-f", "-"],
                       input=sql, capture_output=True, text=True, encoding="utf-8")
    if r.returncode:
        raise SystemExit("운영 UPDATE 실패: " + r.stderr.strip()[:500])
    return list(csv.DictReader(io.StringIO(r.stdout)))


def local_exec(sql):
    r = subprocess.run(["docker", "exec", "-i", "bbanggut-dataops-db", "psql", "-U", "dataops", "-d", "dataops", "-q", "-v", "ON_ERROR_STOP=1", "-f", "-"],
                       input=sql, capture_output=True, text=True, encoding="utf-8")
    if r.returncode:
        raise SystemExit("로컬 apply_log 기록 실패(운영은 이미 커밋됨 — applied TSV 로 다시 기록할 것): " + r.stderr.strip()[:500])


def sql_json(value):
    return "'" + json.dumps(value, ensure_ascii=False).replace("'", "''") + "'::jsonb"


def execute(args):
    writer_pw = os.environ.get("DATAOPS_WRITER_PASSWORD") or read_env(args.env, "DATAOPS_WRITER_PASSWORD")
    if not writer_pw:
        raise SystemExit("DATAOPS_WRITER_PASSWORD 필요 (환경변수 또는 --env 파일)")
    rows = [r for r in csv.DictReader(open(args.plan, encoding="utf-8"), delimiter="\t")
            if not r["user_added"] and not r["beverage"] and (args.include_review or not r.get("review"))]
    print(f"plan {args.plan}: 적용 대상 {len(rows):,}건 (사용자 등록·음료{'' if args.include_review else '·검토 대기'} 제외)")
    applied_path = args.plan[:-len(".tsv")] + "_applied.tsv"
    applied = skipped = 0
    with open(applied_path, "a", encoding="utf-8", newline="") as log:
        out = csv.writer(log, delimiter="\t")
        for start in range(0, len(rows), args.chunk):
            chunk = rows[start:start + args.chunk]
            values = ", ".join(f"({int(r['menu_id'])}, {int(r['to_category_id'])}, "
                               f"{int(r['from_category_id']) if r['from_category_id'] else 'NULL'}::bigint)" for r in chunk)
            done = {row["id"] for row in writer_csv(
                "UPDATE menu m SET menu_category_id = c.to_id, updated_at = now() "
                f"FROM (VALUES {values}) AS c(menu_id, to_id, from_id) "
                "WHERE m.id = c.menu_id AND m.menu_category_id IS NOT DISTINCT FROM c.from_id AND m.deleted_at IS NULL RETURNING m.id;", writer_pw)}
            ok = [r for r in chunk if r["menu_id"] in done]
            for r in ok:   # 로컬 기록보다 먼저 남긴다 — 로컬이 실패해도 무엇이 바뀌었는지 잃지 않게
                out.writerow([r["menu_id"], r["store_id"], r["from_bucket"], r["to_kind"], r["name"]])
            log.flush()
            if ok:
                local_exec("INSERT INTO apply_log (store_id, target, old_value, new_value, origin, applied_at) VALUES " + ", ".join(
                    f"({int(r['store_id'])}, 'menu.category', "
                    f"{sql_json({'menuId': int(r['menu_id']), 'category': None if r['from_bucket'] == 'NULL' else r['from_bucket']})}, "
                    f"{sql_json({'menuId': int(r['menu_id']), 'category': r['to_kind'], 'name': r['name'], 'reason': '분류기 재분류 v2 (빵·기타·NULL — Java 사전 89종)'})}, "
                    "'PROPOSAL', now())" for r in ok) + ";")
            applied += len(ok)
            skipped += len(chunk) - len(ok)
            print(f"  {start + len(chunk):,}/{len(rows):,} — 적용 {applied:,}, 건너뜀 {skipped:,}", flush=True)
    print(f"완료 — 적용 {applied:,}건, 건너뜀(현재 값이 plan 과 다름) {skipped:,}건 · 바뀐 메뉴 → {applied_path}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", default=os.path.join(REPO_ROOT, ".env"), help="reader(PROD_DB_*)·writer(DATAOPS_WRITER_PASSWORD) 자격이 있는 .env")
    parser.add_argument("--out", default=os.path.join(REPO_ROOT, "matching", f"menu_reclassify_plan_{dt.date.today():%Y%m%d}.tsv"))
    parser.add_argument("--execute", action="store_true", help="plan 을 운영에 적용 (청크 커밋 뒤 applied TSV → 로컬 apply_log)")
    parser.add_argument("--plan", help="--execute 에 쓸 plan TSV (기본: --out)")
    parser.add_argument("--include-review", action="store_true", help="review(검토 대기) 행도 적용 — 눈으로 본 뒤에만")
    parser.add_argument("--chunk", type=int, default=500)
    args = parser.parse_args()
    if args.execute:
        args.plan = args.plan or args.out
        execute(args)
    else:
        dry_run(args)


if __name__ == "__main__":
    main()
