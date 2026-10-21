#!/usr/bin/env python3
"""주간 최신화 — 수집·검토 보고서·명시적 반영을 분리한다.

가게마다 네이버 수집 주기가 7일(T1)이라 한 주에 약 5만 곳이 돌아온다. 곳당 약 2초라 한 번에 몰면 28시간이 걸리고
연속 요청이 길어져 차단 위험도 커진다. 그래서 수집은 매일 새벽 시간 상자로 나누고(수집 주기가 된 가게만),
nightly/collect-only는 요일에 관계없이 수집만 한다. 반영은 검수 후 별도 apply 명령으로 실행한다.

별도 apply 명령의 기존 승인 범위 (사용자 결정 2026-09-27): 그 주에 새로 생긴 제안 중
  - 채움: 운영에 값이 없던 영업시간(7요일만, 24시간 제외)·인스타·전화 — approve_fill_pending.py
  - 메뉴 추가: 정크 규칙(음료·옵션·식사·문의·굿즈) 반려 뒤 승인 — approve_menu_add_pending.py
  - 사진: 이번에 승인된 가게의 메뉴·가게 사진 — image_backfill_worker.py
갱신(운영 값을 바꾸는 것)·메뉴 삭제·이름·주소·부분 요일 영업시간은 승인하지 않고 보고서에 모은다.
"그 주에 새로 생긴" 으로 한정하는 이유: 로컬에 오래 쌓인 대기 제안(09-27 기준 메뉴 추가 약 1.7만·메뉴 삭제 6.8만)을
첫 실행이 한꺼번에 승인하지 않게.

운영 쓰기는 전부 서버(ServingWriter, 조건부 쓰기)와 본 앱 이미지 API 경유 — 이 스크립트는 운영 DB 에 직접 쓰지 않는다.
수집은 원본 보존·전체 관측 보류를 지원하고 운영 쓰기가 비활성인 서버에서만 실행한다.
기존 Windows run_nightly.ps1의 일요일 --apply 옵션은 거부된다. Mac 스케줄러 전환은 별도 단계다.

사용:
  DATAOPS_DB_PORT=15434 python scripts/weekly_refresh.py collect-only --max-stores 100 --crawl-hours 0.5
  DATAOPS_DB_PORT=15434 python scripts/weekly_refresh.py apply --dry-run         # 반영 시험 조회 — 운영 쓰기 없음
  DATAOPS_DB_PORT=15434 python scripts/weekly_refresh.py preflight               # 터널·DB·서버 점검만
  DATAOPS_DB_PORT=15434 python scripts/weekly_refresh.py init                    # 처음 한 번 — 반영 기준 시각을 지금으로

반영 기준 시각(state.json 의 last_apply_at)이 없으면 반영하지 않는다. 기본값을 "7일 전"으로 두면 첫 실행이 그사이
쌓인 대기 제안을 한꺼번에 승인한다 — 처음에는 init 으로 기준을 명시한다.
"""
import argparse
import datetime as dt
import json
import os
import re
import socket
import subprocess
import sys
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
import local_db  # noqa: E402
from dataops_server import server_headers

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"
LOG_DIR = ROOT / "dataops-logs" / "weekly"
STATE = LOG_DIR / "state.json"
PY = sys.executable
DEFAULT_CRAWL_HOURS = 6.0  # 03:00 시작이면 09:00 전에 끝난다


def log(message):
    line = f"{dt.datetime.now():%Y-%m-%d %H:%M:%S} {message}"
    print(line, flush=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    with open(LOG_DIR / "weekly_refresh.log", "a", encoding="utf-8") as f:
        f.write(line + "\n")


def load_state():
    try:
        return json.loads(STATE.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def save_state(state):
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")


def db_now():
    conn = local_db.connect()
    try:
        cur = conn.cursor()
        cur.execute("SELECT now()::timestamp")
        return cur.fetchone()[0]
    finally:
        conn.close()


def preflight(server, prod_port, collect_only=False):
    """→ 문제 목록(비었으면 통과). 터널은 사람이 연다 — 닫혀 있으면 건너뛰고 알린다"""
    problems = []
    try:
        db_now()
    except Exception as e:  # noqa: BLE001
        problems.append(f"로컬 dataops DB 접속 실패({local_db.describe()}): {str(e)[:120]}")
    if not collect_only:
        try:
            socket.create_connection(("127.0.0.1", prod_port), timeout=5).close()
        except OSError:
            problems.append(f"운영 DB 터널(127.0.0.1:{prod_port})이 닫혀 있다 — 터널을 연 뒤 다시 돌린다")
    if collect_only:
        from sync_worker import safe_server
        try:
            safe_server(server)
        except (requests.RequestException, ValueError, KeyError, RuntimeError):
            problems.append("수집 전용 서버 필요: 원본 보존·전체 관측 보류 지원, 운영 쓰기 비활성")
    try:
        status = requests.get(f"{server}/actuator/health", headers=server_headers(), timeout=5).json().get("status")
        if status != "UP":
            problems.append(f"dataops 서버 상태 {status}")
    except requests.RequestException as e:
        problems.append(f"dataops 서버 응답 없음({server}): {str(e)[:80]}")
    return problems


def run_script(name, *args):
    """하위 스크립트 실행 — 출력을 그대로 로그에 흘리고 (종료 코드, 마지막 몇 줄)을 돌려준다"""
    cmd = [PY, "-u", str(SCRIPTS / name), *[str(a) for a in args]]
    log(f"실행: {name} {' '.join(str(a) for a in args)}")
    # 윈도우는 하위 파이썬의 표준 출력이 cp949 라 한글 로그가 깨진다 — UTF-8 모드로 띄운다
    env = {**os.environ, "PYTHONUTF8": "1", "PYTHONIOENCODING": "utf-8"}
    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                            encoding="utf-8", errors="replace", env=env)
    tail = []
    with open(LOG_DIR / "weekly_refresh.log", "a", encoding="utf-8") as f:
        for line in proc.stdout:
            f.write(line)
            print(line, end="", flush=True)
            tail = (tail + [line.rstrip()])[-8:]
    code = proc.wait()
    log(f"종료: {name} → {code}")
    return code, tail


def crawl(server, hours, max_stores=None):
    extra = ["--max", max_stores] if max_stores is not None else []
    return run_script("sync_worker.py", "--server", server, "--max-hours", hours, *extra)


def approved_store_ids(since):
    conn = local_db.connect()
    try:
        cur = conn.cursor()
        cur.execute("""SELECT DISTINCT store_id FROM change_proposal
                       WHERE status = 'APPROVED' AND reviewed_at >= %s
                         AND (target LIKE 'menu.add.%%' OR target IN ('store.business_hours', 'store.instagram_handle'))""",
                    (since,))
        return sorted(r[0] for r in cur.fetchall())
    finally:
        conn.close()


def pending_store_ids(since):
    conn = local_db.connect()
    try:
        cur = conn.cursor()
        cur.execute("SELECT DISTINCT store_id FROM change_proposal WHERE status = 'PENDING' AND created_at >= %s "
                    "AND target LIKE 'menu.add.%%'", (since,))
        return sorted(r[0] for r in cur.fetchall())
    finally:
        conn.close()


def apply(server, since, dry_run):
    """그 주에 새로 생긴 제안의 자동 승인 → 사진 → (보고서는 호출자가). → 결과 dict"""
    started = db_now()
    result = {"since": str(since), "started": str(started), "dry_run": dry_run, "steps": {}}
    extra = ["--dry-run"] if dry_run else []
    code, tail = run_script("approve_fill_pending.py", "--server", server, "--created-after", since,
                            "--full-week-only", *extra)
    result["steps"]["채움 승인"] = {"code": code, "tail": tail}
    code, tail = run_script("approve_menu_add_pending.py", "--server", server, "--created-after", since, *extra)
    result["steps"]["메뉴 추가 승인"] = {"code": code, "tail": tail}

    store_ids = pending_store_ids(since) if dry_run else approved_store_ids(started)
    ids_file = LOG_DIR / f"image_targets_{started:%Y%m%d}.json"
    ids_file.write_text(json.dumps({"store_ids": store_ids}), encoding="utf-8")
    if store_ids:
        code, tail = run_script("image_backfill_worker.py", "--all", "--store-ids-file", ids_file, *extra)
    else:
        code, tail = 0, ["사진 보낼 가게 없음"]
    result["steps"]["사진"] = {"code": code, "tail": tail, "stores": len(store_ids)}
    return result


def week_summary(since, apply_started):
    """보고서 수치 — 로컬 DB 읽기만"""
    conn = local_db.connect()
    try:
        cur = conn.cursor()
        cur.execute("SELECT count(*), count(DISTINCT store_id) FROM menu_evidence_observation WHERE observed_at >= %s",
                    (since,))
        visits, visited = cur.fetchone()
        cur.execute("SELECT count(*) FROM place_snapshot WHERE created_at >= %s", (since,))
        snapshots = cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM store_sync_state WHERE source = 'NAVER' AND state = 'UNLINKED'")
        unlinked = cur.fetchone()[0]
        approved = []
        if apply_started:
            cur.execute("""SELECT CASE WHEN target LIKE 'menu.add.%%' THEN 'menu.add' ELSE target END, status,
                                  coalesce(apply_outcome_reason, ''), count(*)
                           FROM change_proposal WHERE reviewed_at >= %s GROUP BY 1, 2, 3 ORDER BY 1, 2""",
                        (apply_started,))
            approved = cur.fetchall()
        cur.execute("""SELECT CASE WHEN target LIKE 'menu.add.%%' THEN '메뉴 추가(규칙 밖·남은 것)'
                                   WHEN target LIKE 'menu.%%.removed' THEN '메뉴 삭제'
                                   WHEN target LIKE 'menu.%%' THEN '메뉴 변경(가격·설명·분류)'
                                   WHEN target = 'store.business_hours' AND old_value::text IN ('null', '""', '[]')
                                        THEN '영업시간 채움(부분 요일·24시간)'
                                   WHEN target = 'store.business_hours' THEN '영업시간 갱신'
                                   ELSE target END AS kind, count(*), count(DISTINCT store_id)
                       FROM change_proposal WHERE status = 'PENDING' AND created_at >= %s
                       GROUP BY 1 ORDER BY 2 DESC""", (since,))
        review = cur.fetchall()
        return {"visits": visits, "visited": visited, "snapshots": snapshots, "unlinked": unlinked,
                "approved": approved, "review": review}
    finally:
        conn.close()


def write_report(kind, since, problems, crawl_result, apply_result):
    now = dt.datetime.now()
    apply_started = apply_result and not apply_result.get("dry_run") and apply_result.get("started")
    try:
        summary = week_summary(since, apply_started)
    except Exception as e:  # noqa: BLE001
        summary = None
        problems = problems + [f"보고서 집계 실패: {str(e)[:120]}"]
    lines = [f"# 주간 최신화 보고서 — {now:%Y-%m-%d %H:%M} ({kind})", "", f"- 기간: {since} ~ {now:%Y-%m-%d %H:%M}"]
    if problems:
        lines += ["", "## 문제", *[f"- {p}" for p in problems]]
    if crawl_result or summary:
        lines += ["", "## 수집"]
    if crawl_result:
        lines += [f"- 워커 종료 코드 {crawl_result[0]}", *[f"  - {t}" for t in crawl_result[1][-3:]]]
    if summary:
        lines += [f"- 이번 기간 관측 {summary['visits']:,}회 · {summary['visited']:,}곳, 내용 변경(스냅샷) {summary['snapshots']:,}",
                  f"- 수집 불가(UNLINKED) 누적 {summary['unlinked']:,}곳"]
    if apply_result:
        lines += ["", "## 자동 반영" + (" (시험 조회 — 운영 쓰기 없음)" if apply_result.get("dry_run") else "")]
        for step, value in apply_result["steps"].items():
            lines.append(f"- {step}: 종료 코드 {value['code']}" + (f" · 대상 가게 {value['stores']:,}" if "stores" in value else ""))
            lines += [f"  - {t}" for t in value["tail"][-3:] if t]
        if summary and summary["approved"]:
            lines += ["", "| 대상 | 상태 | 사유 | 건수 |", "|---|---|---|---|"]
            lines += [f"| {t} | {s} | {r} | {n:,} |" for t, s, r, n in summary["approved"]]
    if summary and summary["review"]:
        lines += ["", "## 사람 검토 대기 (이번 기간에 새로 생긴 PENDING)", "", "| 종류 | 제안 | 가게 |", "|---|---|---|"]
        lines += [f"| {k} | {n:,} | {s:,} |" for k, n, s in summary["review"]]
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    path = LOG_DIR / f"report_{now:%Y%m%d_%H%M}_{kind}.md"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    log(f"보고서: {path}")
    return path


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["nightly", "collect-only", "review-only", "apply", "preflight", "init"])
    ap.add_argument("--server", default="http://localhost:8090")
    ap.add_argument("--prod-port", type=int, default=int(os.environ.get("PROD_DB_PORT", "15432")))
    ap.add_argument("--crawl-hours", type=float, default=DEFAULT_CRAWL_HOURS)
    ap.add_argument("--max-stores", type=int, default=None)
    ap.add_argument("--apply", action="store_true", help="폐기된 옵션: 사용하면 오류; 별도 apply 명령 필요")
    ap.add_argument("--since", default=None, help="검토/반영 기준 시각 (기본: 지난 반영 시각; 없으면 명시 필요)")
    ap.add_argument("--dry-run", action="store_true", help="apply 를 시험 조회로 — 운영 쓰기 없음")
    args = ap.parse_args()
    if args.apply:
        ap.error("수집과 반영은 분리합니다. 검수 뒤 별도의 apply 실행을 사용하세요.")
    if args.crawl_hours <= 0 or (args.max_stores is not None and args.max_stores < 1):
        ap.error("crawl-hours/max-stores must be positive")

    state = load_state()
    if args.command == "init":
        state["last_apply_at"] = args.since or str(db_now())
        save_state(state)
        log(f"반영 기준 시각 = {state['last_apply_at']} — 이 뒤에 새로 생긴 제안만 자동 반영한다")
        return 0
    if args.command == "review-only":
        since = args.since or state.get("last_apply_at")
        if not since:
            ap.error("review-only는 --since 또는 저장된 반영 기준 시각이 필요합니다.")
        write_report("review-only", since, [], None, None)
        return 0
    collect_only = args.command in ("nightly", "collect-only")
    problems = preflight(args.server, args.prod_port, collect_only=collect_only)
    if args.command == "preflight":
        print("\n".join(problems) if problems else "점검 통과")
        return 1 if problems else 0
    since = args.since or state.get("last_apply_at")
    if problems:
        log("사전 점검 실패 — 이번 실행을 건너뛴다: " + " / ".join(problems))
        write_report(args.command, since or "(기준 없음)", problems, None, None)
        return 2

    crawl_result = None
    do_apply = args.command == "apply"
    if do_apply and not since:
        problems.append("반영 기준 시각이 없다 — `weekly_refresh.py init` 으로 정한 뒤부터 자동 반영한다(이번은 수집만)")
        do_apply = False
        since = str(db_now() - dt.timedelta(days=1))   # 보고서 기간 표시용
    if collect_only:
        crawl_result = crawl(args.server, args.crawl_hours, args.max_stores)
        if crawl_result[0] not in (0, 3):   # 3 = 차단 쿨다운 소진; 수집과 반영은 독립이다
            problems.append(f"수집 워커 비정상 종료 {crawl_result[0]}")
    apply_result = None
    if do_apply:
        apply_result = apply(args.server, since, args.dry_run)
        if not args.dry_run and all(v["code"] in (0,) for v in apply_result["steps"].values()):
            state["last_apply_at"] = apply_result["started"]
            save_state(state)
        elif not args.dry_run:
            problems.append("자동 반영 중 실패한 단계가 있다 — 다음 실행이 같은 기간을 이어서 본다")
    write_report(args.command if not args.dry_run else "apply-dry-run", since, problems, crawl_result, apply_result)
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
