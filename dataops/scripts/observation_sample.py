#!/usr/bin/env python3
"""v3 P2 관측 표본 — 소멸 판정에 쓸 수 있는 관측이 실제로 들어오는지 확인하는 제한 표본.

왜 기존 /api/sync-states/observation-sample 을 쓰지 않나
- 그 엔드포인트는 clearSchedule 로 NAVER ACTIVE 전체의 next_due_at 을 NULL 로 지워 표본 밖 가게의
  수집을 통째로 보류시킨다(1단계 관찰용). 그리고 80% 를 메뉴 0개 가게로 뽑는다 — 메뉴 완전성을
  검증해야 하는 지금 목적과 반대다.

이 스크립트는 표본 밖 가게의 스케줄을 건드리지 않는다. 표본의 next_due_at 만 과거로 당겨 due 목록
맨 앞에 세운다(due 는 next_due_at 오름차순). 워커를 --max 로 표본 크기만큼만 돌리면 된다.

층화 — 최신 스냅샷(09-20 이후, 새 payload 모양)의 메뉴 탭 유무로 나눈다:
  A 메뉴 있음 (placeMenus 에 items) → 기대: menu_complete = true
  B 메뉴 탭 없음 (placeMenus = null) → 기대: menu_complete = false (부재를 증명할 수 없다)
각 층 안에서는 md5(store_id) 순이라 재실행해도 같은 표본이 나온다.

사용
    DATAOPS_DB_PORT=15434 python scripts/observation_sample.py select --with-menu 80 --no-menu 20
    DATAOPS_DB_PORT=15434 python scripts/observation_sample.py report      # 수집 후 전후 대조
    # 2차 방문 — 같은 가게를 다시(최소 간격 6시간 뒤). 표본을 새로 뽑지 않고 1차 목록을 그대로 쓴다
    DATAOPS_DB_PORT=15434 python scripts/observation_sample.py select --round 2 --reuse dataops-logs/obs_sample_20260926_before.json
    DATAOPS_DB_PORT=15434 python scripts/observation_sample.py report --round 2
출력: dataops-logs/obs_sample_{날짜}[_r{회차}]_before.json (표본 목록 + 수집 전 상태)
"""
import argparse
import datetime as dt
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import local_db  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
NEW_SHAPE_SINCE = "2026-09-20"
EARLY = "2000-01-01 00:00:00"


def resolve(payload, node, depth=8):
    seen = set()
    for _ in range(depth):
        if not isinstance(node, dict) or "__ref" not in node:
            break
        ref = node["__ref"]
        if ref in seen:
            return None
        seen.add(ref)
        node = payload.get(ref)
    return None if isinstance(node, dict) and "__ref" in node else node


def root_id(key):
    if not (key.startswith("placeDetail(") and key.endswith(")")):
        return None
    try:
        args = json.loads(key[len("placeDetail("):-1])
    except ValueError:
        return None
    return str((args.get("input") or {}).get("id") or args.get("id") or "") or None


def menu_stratum(payload, external_id):
    """A=메뉴 있음, B=메뉴 탭 없음, None=판정 불가(표본에서 뺀다)."""
    roots = [resolve(payload, v) for k, v in (payload.get("ROOT_QUERY") or {}).items()
             if root_id(k) == str(external_id)]
    if len(roots) != 1 or not isinstance(roots[0], dict):
        return None, 0
    keys = [k for k in roots[0] if k == "placeMenus" or k.startswith("placeMenus(")]
    if len(keys) != 1:
        return None, 0
    container = resolve(payload, roots[0][keys[0]])
    if container is None:
        return "B", 0
    items = resolve(payload, container.get("items")) if isinstance(container, dict) else None
    if isinstance(items, list) and items and container.get("menuCount") == len(items):
        return "A", len(items)
    return None, 0


def md5_order(store_id):
    return hashlib.md5(str(store_id).encode()).hexdigest()


def before_path(round_no=1):
    suffix = "" if round_no == 1 else f"_r{round_no}"
    return ROOT / "dataops-logs" / f"obs_sample_{dt.date.today():%Y%m%d}{suffix}_before.json"


def state_of(cur, store_ids):
    cur.execute("""
        SELECT s.store_id, s.id, s.external_id, s.visit_count, s.change_count, s.content_hash_version,
               s.next_due_at::text, s.attempt_seq, s.last_success_at::text,
               g.signal_hash, g.signal_observed_at::text, g.payload_retained, g.visitor_reviews_total,
               (SELECT count(*) FROM menu_evidence_observation o WHERE o.store_id = s.store_id) AS observations,
               (SELECT count(*) FROM place_snapshot p WHERE p.store_id = s.store_id AND p.source = 'NAVER') AS snapshots
        FROM store_sync_state s
        LEFT JOIN store_signal g ON g.store_id = s.store_id
        WHERE s.source = 'NAVER' AND s.store_id = ANY(%s)""", (list(store_ids),))
    cols = [d[0] for d in cur.description]
    return {row[0]: dict(zip(cols, row)) for row in cur.fetchall()}


def select(args):
    out = before_path(args.round)
    if out.exists() and not args.dry_run:
        # 이전 회차 기록을 덮어쓰면 그 회차의 전후 대조를 잃는다
        raise SystemExit(f"{out.name} 이 이미 있다 — --round 를 올려라")
    conn = local_db.connect()
    cur = conn.cursor()
    if args.reuse:
        # 2차 이후는 같은 가게를 다시 본다 — 새로 뽑으면 한 번 부재·두 번 부재를 같은 가게로 이을 수 없다
        prior = json.loads(Path(args.reuse).read_text(encoding="utf-8"))
        picked = prior["strata"]
        sample = picked["A"] + picked["B"]
        before = state_of(cur, sample)
        record = {"created_at": dt.datetime.now().isoformat(timespec="seconds"), "round": args.round,
                  "reused_from": str(args.reuse), "purpose": prior["purpose"],
                  "strata": picked, "before": {str(k): v for k, v in before.items()}}
        return _commit_selection(conn, cur, sample, record, out, args,
                                 note=f"{args.reuse} 의 {len(sample)}곳 재사용")
    # 최신 스냅샷이 새 모양인 가게, 활성, 리스가 풀린 것만 (리스 중이면 클레임에서 빠져 표본이 새어 나간다)
    cur.execute("""
        SELECT DISTINCT ON (p.store_id) p.store_id, s.external_id, p.payload::text
        FROM place_snapshot p
        JOIN store_sync_state s ON s.store_id = p.store_id AND s.source = 'NAVER'
        WHERE p.source = 'NAVER' AND s.state = 'ACTIVE' AND s.external_id IS NOT NULL
          AND (s.last_attempt_at IS NULL OR s.last_attempt_at < now() - interval '2 hours')
          AND p.fetched_at >= %s
        ORDER BY p.store_id, p.fetched_at DESC""", (NEW_SHAPE_SINCE,))
    strata = {"A": [], "B": []}
    menus = {}
    for store_id, external_id, raw in cur:
        stratum, n = menu_stratum(json.loads(raw), external_id)
        if stratum:
            strata[stratum].append(store_id)
            menus[store_id] = n
    picked = {
        "A": sorted(strata["A"], key=md5_order)[:args.with_menu],
        "B": sorted(strata["B"], key=md5_order)[:args.no_menu],
    }
    sample = picked["A"] + picked["B"]
    before = state_of(cur, sample)

    record = {
        "created_at": dt.datetime.now().isoformat(timespec="seconds"),
        "round": args.round,
        "purpose": "v3 P2 — 소멸 판정에 쓸 수 있는 관측이 들어오는지 확인 (승인·publish 없음)",
        "candidates": {k: len(v) for k, v in strata.items()},
        "strata": {k: v for k, v in picked.items()},
        "menu_items_at_selection": {str(k): menus[k] for k in sample},
        "before": {str(k): v for k, v in before.items()},
    }
    _commit_selection(conn, cur, sample, record, out, args,
                      note=f"후보 메뉴 있음 {len(strata['A']):,} · 메뉴 탭 없음 {len(strata['B']):,}")


def _commit_selection(conn, cur, sample, record, out, args, note):
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(record, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    if args.dry_run:
        conn.rollback()
    else:
        # 표본만 due 맨 앞으로 당긴다 — 표본 밖 가게의 스케줄은 그대로다. 리스도 풀어야 클레임에 잡힌다
        cur.execute("UPDATE store_sync_state SET next_due_at = %s, last_attempt_at = NULL "
                    "WHERE source = 'NAVER' AND state = 'ACTIVE' AND store_id = ANY(%s)", (EARLY, sample))
        print(f"due 맨 앞으로 당김: {cur.rowcount}곳")
        conn.commit()
    conn.close()
    print(note)
    print(f"표본: A {len(record['strata']['A'])} + B {len(record['strata']['B'])} = {len(sample)}")
    print(f"→ {out}")


def report(args):
    record = json.loads(before_path(args.round).read_text(encoding="utf-8"))
    sample = [int(k) for k in record["before"]]
    stratum_of = {sid: s for s, ids in record["strata"].items() for sid in ids}
    conn = local_db.connect()
    cur = conn.cursor()
    after = state_of(cur, sample)
    cur.execute("""
        SELECT store_id, count(*) AS n,
               bool_or(menu_complete) AS any_complete, bool_and(identity_verified) AS all_verified,
               max(parser_version) AS parser, max(jsonb_array_length(menu_names)) AS menus
        FROM menu_evidence_observation WHERE store_id = ANY(%s) AND observed_at >= %s
        GROUP BY store_id""", (sample, record["created_at"]))
    obs = {r[0]: {"n": r[1], "complete": r[2], "verified": r[3], "parser": r[4], "menus": r[5]}
           for r in cur.fetchall()}
    conn.close()

    rows = []
    for sid in sample:
        b, a, o = record["before"][str(sid)], after.get(sid, {}), obs.get(sid)
        rows.append({
            "store_id": sid, "stratum": stratum_of[sid],
            "visited": a.get("visit_count", 0) > b["visit_count"],
            "observed": o is not None,
            "complete": bool(o and o["complete"]),
            "verified": bool(o and o["verified"]),
            "parser": o and o["parser"],
            "new_snapshot": a.get("snapshots", 0) > b["snapshots"],
            "changed_count": a.get("change_count", 0) - b["change_count"],
            "hash_v3": a.get("content_hash_version") == 3,
            "signal_refreshed": (a.get("signal_observed_at") or "") > (b["signal_observed_at"] or ""),
            "signal_hash_set": a.get("signal_hash") is not None,
            "payload_retained": a.get("payload_retained"),
            "reviews": (b["visitor_reviews_total"], a.get("visitor_reviews_total")),
        })

    def count(stratum, key):
        rs = [r for r in rows if r["stratum"] == stratum]
        return sum(1 for r in rs if r[key]), len(rs)

    print(f"표본 {len(rows)} (선정 {record['created_at']})\n")
    print(f"{'':28}{'A 메뉴 있음':>14}{'B 메뉴 탭 없음':>16}")
    for label, key in [("방문 기록(visit+1)", "visited"), ("관측 원장에 기록", "observed"),
                       ("대상 확인(identity)", "verified"), ("메뉴 완전성 true", "complete"),
                       ("새 스냅샷 저장(내용 변경)", "new_snapshot"), ("해시 3세대로 전환", "hash_v3"),
                       ("신호 관측시각 전진", "signal_refreshed"), ("신호 해시 채움", "signal_hash_set")]:
        a, na = count("A", key)
        b, nb = count("B", key)
        print(f"  {label:26}{a:>7}/{na:<6}{b:>9}/{nb:<6}")
    parsers = {r["parser"] for r in rows if r["parser"]}
    print(f"\n관측 파서 세대: {parsers or '없음'}")
    unchanged = [r for r in rows if r["visited"] and not r["new_snapshot"]]
    retained_false = sum(1 for r in unchanged if r["payload_retained"] is False)
    print(f"무변경 방문 {len(unchanged)} 중 원본 미보존(payload_retained=false) 표시 {retained_false}")
    review_moved = [r for r in unchanged if r["reviews"][0] != r["reviews"][1]]
    print(f"무변경 방문인데 리뷰 수가 바뀐 가게 {len(review_moved)} — 이전 구현이라면 버려졌을 갱신")
    for r in review_moved[:8]:
        print(f"    store {r['store_id']}: 리뷰 {r['reviews'][0]} → {r['reviews'][1]}")
    anomalies = [r for r in rows if r["stratum"] == "B" and r["complete"]]
    if anomalies:
        print(f"\n!! 메뉴 탭 없는 가게가 완전성 true — {[r['store_id'] for r in anomalies]}")
    out = before_path(args.round).with_name(before_path(args.round).name.replace("_before", "_after"))
    out.write_text(json.dumps(rows, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    print(f"\n→ {out}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("select")
    s.add_argument("--with-menu", type=int, default=80)
    s.add_argument("--no-menu", type=int, default=20)
    s.add_argument("--dry-run", action="store_true")
    s.add_argument("--round", type=int, default=1)
    s.add_argument("--reuse", help="이전 회차 before.json — 같은 가게를 다시 본다")
    s.set_defaults(func=select)
    r = sub.add_parser("report")
    r.add_argument("--round", type=int, default=1)
    r.set_defaults(func=report)
    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
