#!/usr/bin/env python3
"""가게 취향 라벨(v3) 계산 → 로컬 검수표 → 앱 DB store_recommendation_feature 적재 (BE #777).

왜 store_profile 과 따로인가
  store_profile.axis_* 는 축 넷(−1~+1)이라 두 갈래까지만 담는다. 맛이 넷(달콤·담백·짭짤·풍미)이고
  식감이 셋(부드·바삭·쫄깃)이 되면 짭짤과 담백이 같은 +1 이 되고 풍미·쫄깃은 어느 극도 아니라 0 이 된다.
  그 표현으로는 회원이 검사에서 고른 갈래를 가게와 맞댈 수 없다. 두 표가 같은 가게를 다른 각도로 들고 있다.

산식은 store_profile_batch 의 축 계산과 같다
  메뉴쪽 = 종류 라벨의 (1 + 좋아요) 가중 평균, 라벨 붙는 메뉴 MIN_MENUS 이상일 때만
  리뷰쪽 = store_signal.reviewMenus 라벨의 언급수 가중 평균 (라벨 2개 이상 또는 언급 5회 이상)
  최종   = 둘 다 있으면 0.6*메뉴 + 0.4*리뷰, 아니면 있는 쪽
  네이버에서 두 번 연속 사라진 메뉴는 뺀다 — store_profile 이 그렇게 만들어져 있어서, 라벨만 안 빼면
  같은 가게의 두 표가 서로 다른 메뉴를 근거로 삼는다.

프로필과 라벨은 동일한 frozen 근거를 검증한 뒤 하나의 트랜잭션으로 반영한다.
미해결 legacy는 NEEDS_REVIEW로 보존하며 선택 범위의 게시를 중단한다. 기존 serving 값은 유지한다.
NEEDS_REVIEW는 WITHOUT_LABELS가 아니고 --apply-invalidations로도 우회할 수 없다.
대상 DB 에 없는 store_id 는 FK 오류로 전체 중단한다. scope를 조용히 줄이지 않는다.
--dry-run 은 실제로 INSERT 까지 해 보고 ROLLBACK 한다 — CHECK·FK·권한·캐스팅이 진짜 표에서 통과하는지
보는 것이 목적이라 문법만 보는 드라이런은 뜻이 없다.

속·분위기는 사전에서 −1~+1 로 들고 있다가 내보낼 때 (x+1)/2 로 0~1 로 편다
  · 부호를 회원 선언과 같게 맞춘다 (꽉 참 1 · 트렌디 1). 점수 경로에서 뒤집는 규칙을 없앤다 —
    부호가 틀리면 예외 없이 그럴듯한 코스가 나와서 틀린 걸 알 방법이 없다.
  · 자를 라벨 일곱 개와 같게 만든다. 범위가 두 배면 이 둘이 점수 분산의 59% 를 먹는다(09-21 실측:
    통일 뒤 26.4%). 읽는 쪽이 정규화를 기억해야 하는 문제를 표에서 없앤다.
  선형 변환이라 가중평균을 먼저 하고 나중에 펴도 값이 같다 — 손실 없다.

적재 시 두 표를 함께 upsert하고 검수한 WITHOUT_LABELS ID만 명시적으로 무효화한다.
로컬 publish 감사 원장에 intent·이전 값·예상 결과를 먼저 기록한다. --dry-run도 이 감사 기록은 남는다.
commit 결과가 불명확하면 UNKNOWN으로 다음 publish를 차단한다.

사용
    python scripts/recommendation_feature_load.py --build                    # 계산 → 로컬 검수표
    python scripts/recommendation_feature_load.py --target lab --dry-run     # 검수표 → 랩 (끝에 ROLLBACK)
    python scripts/recommendation_feature_load.py --target lab
    python scripts/recommendation_feature_load.py --target prod              # 개발 리허설이 끝난 뒤에만
"""
import argparse
import datetime as dt
import hashlib
import json
import os
import sys
from dataclasses import dataclass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from store_profile_batch import (MIN_MENUS, MIN_REVIEW_LABELS, MIN_REVIEW_MENTIONS,  # noqa: E402
                                 BATCH_VERSION, review_kind_hits)
from store_tags import local_csv, prod_csv, read_env  # noqa: E402
from kind_evidence_store import group_menus, restore_context  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TABLE = "store_recommendation_feature"
DRAFT = "store_feature_draft"
DICT_VERSION = "v3-20260921"

LABELS = ("taste_sweet", "taste_plain", "taste_salty", "taste_aroma", "tex_soft", "tex_crisp", "tex_chewy")
AXES2 = ("axis_filling", "axis_vibe")            # 사전 컬럼 이름 (−1~+1)
OUT_AXES = {"axis_filling": "filling_rich", "axis_vibe": "vibe_trendy"}   # 내보낼 이름 (0~1)
DIMS = LABELS + AXES2
COLS = list(LABELS) + list(OUT_AXES.values()) + [
    "label_source", "menu_label_count", "review_label_count", "evidence",
    "source_fingerprint", "dictionary_version", "observed_at", "computed_at"]

# 포트는 기계마다 다른 터널에 잡혀 있을 수 있어 환경변수로 연다 (local_db.py 와 같은 이유)
TARGETS = {
    "dev": {"port": int(os.environ.get("DEV_DB_PORT", "15433")), "user": "sweetgirl", "password_key": "PROD_DB_PASSWORD"},
    "prod": {"port": int(os.environ.get("PROD_DB_PORT", "15432")), "user": "dataops_writer", "password_key": "DATAOPS_WRITER_PASSWORD"},
    # BE docker-compose.lab-postgres.yml 의 로컬 랩(운영 덤프 복제본). 비밀번호는 그 compose 에 적힌 로컬 전용 값
    "lab": {"port": int(os.environ.get("LAB_DB_PORT", "5432")), "user": "sweetgirl", "password": "soboro-lab"},
}
# 대상 표가 BE #777 모양인지 — 아니면 아무것도 쓰기 전에 멈춘다
REQUIRED_COLUMNS = {"filling_rich": "numeric", "vibe_trendy": "numeric", "taste_salty": "numeric",
                    "source_fingerprint": "character", "evidence": "jsonb"}


def load_label_table():
    rows = local_csv("SELECT l.kind, " + ", ".join(f"l.{c}" for c in DIMS) + " FROM menu_kind_label l "
                     "JOIN menu_kind_axis a ON a.kind = l.kind AND a.is_bread IS TRUE;")
    table = {r["kind"]: {d: float(r[d]) for d in DIMS} for r in rows}
    return table, sorted(table, key=len, reverse=True)


def weighted(vectors):
    total = sum(w for _, w in vectors)
    if total <= 0:
        return None
    return {d: sum(v[d] * w for v, w in vectors) / total for d in DIMS}


def fingerprint(snapshot_id, menu_hits, review_hits, evidence_fingerprint=""):
    """이 벡터를 만든 재료의 지문 — 사전 판본 · 신호 스냅샷 · 쓰인 메뉴와 리뷰 라벨. 시각은 넣지 않는다.

    같은 값이면 다시 계산해도 같은 벡터가 나온다. 재적재가 진짜 바뀐 것인지 이 한 열로 가린다.
    """
    parts = [DICT_VERSION, str(snapshot_id or ""), evidence_fingerprint]
    parts += [f"m:{k}:{w}" for k, w in sorted(menu_hits)]
    parts += [f"r:{k}:{c}" for k, c in sorted(review_hits)]
    return hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()[:32]


def evidence_of(menu_hits, review_hits, limit=5):
    """이유 문장이 쓸 근거 — 가중치 큰 종류부터. 어느 쪽에서 왔는지 함께 남긴다."""
    agg = {}
    for kind, w in menu_hits:
        agg.setdefault(kind, {"kind": kind, "menuWeight": 0, "reviewMentions": 0})["menuWeight"] += w
    for kind, c in review_hits:
        agg.setdefault(kind, {"kind": kind, "menuWeight": 0, "reviewMentions": 0})["reviewMentions"] += c
    return sorted(agg.values(), key=lambda e: -(e["menuWeight"] + e["reviewMentions"]))[:limit]


@dataclass
class FeatureBuild:
    rows: list
    outcomes: dict


def label_inputs(signal, menus, table, evidence):
    """Pure per-store calculation; profile and labels share evidence, not copied filters."""
    selected = evidence.filter_menus(menus)
    menu_hits = [(m["kind"], 1 + int(m.get("likes") or 0)) for m in selected if m["kind"] in table]
    review_hits = review_kind_hits(signal, table, evidence)
    menu_vec = weighted([(table[k], weight) for k, weight in menu_hits]) if len(menu_hits) >= MIN_MENUS else None
    review_vec = weighted([(table[k], count) for k, count in review_hits])
    if menu_vec is None and review_vec is not None and (len(review_hits) < MIN_REVIEW_LABELS
                                                       and sum(c for _, c in review_hits) < MIN_REVIEW_MENTIONS):
        review_vec = None
    if menu_vec and review_vec:
        vector = {d: 0.6 * menu_vec[d] + 0.4 * review_vec[d] for d in DIMS}
        source = "BOTH"
    elif menu_vec or review_vec:
        vector, source = menu_vec or review_vec, "MENU" if menu_vec else "REVIEW"
    else:
        vector, source = None, "NONE"
    return vector, source, menu_hits, review_hits


def scope_clause(store_ids):
    if store_ids is None:
        return ""
    ids = sorted({int(sid) for sid in store_ids})
    if not ids or any(sid <= 0 for sid in ids):
        raise ValueError("store scope must contain positive IDs")
    return " AND store_id IN (" + ",".join(map(str, ids)) + ")"


def build(env_path, store_ids=None):
    table, _ = load_label_table()
    profiles = local_csv("SELECT store_id, kind_evidence::text, kind_evidence_fingerprint "
                         f"FROM store_profile_draft WHERE batch_version='{BATCH_VERSION}'{scope_clause(store_ids)} ORDER BY store_id;")
    ids = [r["store_id"] for r in profiles]
    if not ids:
        raise ValueError("no evidence-backed profile draft; run store_profile_batch first")
    if store_ids is not None and set(map(int, ids)) != set(map(int, store_ids)):
        raise ValueError("requested profile scope is incomplete; rebuild profiles first")
    print(f"라벨 사전 {len(table)}종 (빵 종류만) · 대상 가게 {len(ids):,}", flush=True)
    id_list = ",".join(str(int(sid)) for sid in ids)
    menus = prod_csv(f"SELECT m.id, m.store_id, m.name, mc.name AS kind, COALESCE(m.like_count,0) AS likes FROM menu m "
                     f"LEFT JOIN menu_category mc ON mc.id = m.menu_category_id "
                     f"WHERE m.deleted_at IS NULL AND m.store_id IN ({id_list});", env_path)
    by_store = group_menus(menus)
    sig_rows = local_csv(f"SELECT store_id, snapshot_id, signals::text AS signals FROM store_signal WHERE store_id IN ({id_list});")
    signals = {r["store_id"]: json.loads(r["signals"]) for r in sig_rows}
    snapshot_of = {r["store_id"]: r["snapshot_id"] for r in sig_rows}
    identities = {r["store_id"]: r["external_id"] for r in local_csv(
        f"SELECT store_id, external_id FROM store_sync_state WHERE source='NAVER' AND store_id IN ({id_list});")}

    rows, source, outcomes = [], {"BOTH": 0, "MENU": 0, "REVIEW": 0, "NONE": 0}, {}
    now = dt.datetime.now().replace(microsecond=0)
    for profile in profiles:
        sid = profile["store_id"]
        if sid not in signals:
            raise ValueError(f"store {sid}: signal missing; failure is not evidence disappearance")
        sig = signals[sid]
        state = restore_context(profile["kind_evidence"], profile["kind_evidence_fingerprint"],
                                sig, by_store[sid], snapshot_of.get(sid), external_id=identities.get(sid))
        # Compute counterfactual shadow values even for held stores, so exclusion
        # impact remains inspectable. Outcome, not vector presence, authorizes publishing.
        vec, src, menu_hits, review_hits = label_inputs(sig, by_store[sid], table, state)
        build_status = ("NEEDS_REVIEW" if state.publication_status == "NEEDS_REVIEW"
                        else "WITH_LABELS" if vec else "WITHOUT_LABELS")
        outcomes[str(sid)] = {"status": build_status,
                              "profile_fingerprint": profile["kind_evidence_fingerprint"],
                              "blockers": state.publication_blockers}
        if vec is None:
            source["NONE"] += 1
            continue
        source[src] += 1
        rows.append([sid]
                    + [round(vec[d], 4) for d in LABELS]
                    + [round((vec[d] + 1) / 2, 4) for d in AXES2]
                    + [src, len(menu_hits), len(review_hits),
                       json.dumps(evidence_of(menu_hits, review_hits), ensure_ascii=False),
                       fingerprint(snapshot_of.get(sid), menu_hits, review_hits, state.fingerprint),
                       DICT_VERSION, state.latest_observed_at.replace(tzinfo=None) if state.latest_observed_at else None, now])
    print(f"라벨 벡터가 선 가게 {len(rows):,} · 출처 {source}", flush=True)
    held = sum(outcome["status"] == "NEEDS_REVIEW" for outcome in outcomes.values())
    print(f"NEEDS_REVIEW {held:,}곳의 계산값은 영향 비교용 shadow이며 게시 대상이 아닙니다", flush=True)
    return FeatureBuild(rows, outcomes)


DRAFT_DDL = f"""
CREATE TABLE IF NOT EXISTS {DRAFT} (
  store_id bigint PRIMARY KEY,
  {', '.join(f'{c} numeric(5,4)' for c in list(LABELS) + list(OUT_AXES.values()))},
  label_source varchar(10), menu_label_count integer, review_label_count integer,
  evidence jsonb, source_fingerprint char(32), dictionary_version varchar(20),
  observed_at timestamp, computed_at timestamp,
  build_status varchar(32), profile_evidence_fingerprint varchar(64));
ALTER TABLE {DRAFT} ADD COLUMN IF NOT EXISTS build_status varchar(32);
ALTER TABLE {DRAFT} ADD COLUMN IF NOT EXISTS profile_evidence_fingerprint varchar(64);
"""


def write_draft(result):
    """로컬 검수표 — 적재 전에 눈으로 보고, 대상 DB 없이도 다시 쓸 수 있게 남긴다"""
    import local_db
    from psycopg2.extras import execute_values
    conn = local_db.connect()
    try:
        with conn.cursor() as cur:
            ids = [int(sid) for sid in result.outcomes]
            cur.execute("SELECT store_id, kind_evidence_fingerprint FROM store_profile_draft WHERE store_id=ANY(%s) FOR SHARE", (ids,))
            actual = {str(sid): fp for sid, fp in cur.fetchall()}
            expected = {sid: outcome["profile_fingerprint"] for sid, outcome in result.outcomes.items()}
            if actual != expected:
                raise ValueError("profile changed during label build; draft not replaced")
            cur.execute(DRAFT_DDL)
            cur.execute(f"DELETE FROM {DRAFT} WHERE store_id=ANY(%s)", (ids,))
            rows_by_id = {str(row[0]): row for row in result.rows}
            rows = []
            for sid, outcome in result.outcomes.items():
                row = rows_by_id.get(sid, [sid] + [None] * len(COLS))
                rows.append(row + [outcome["status"], outcome["profile_fingerprint"]])
            if rows:
                execute_values(cur, f"INSERT INTO {DRAFT} (store_id, {', '.join(COLS)}, build_status, profile_evidence_fingerprint) VALUES %s", rows, page_size=2000)
        conn.commit()
        print(f"검수표 {DRAFT} {len(rows):,}행 (WITHOUT_LABELS/NEEDS_REVIEW 포함 · {local_db.describe()})")
    finally:
        conn.close()


def read_draft(store_ids=None):
    rows = local_csv(f"SELECT store_id, {', '.join(COLS)}, build_status, profile_evidence_fingerprint FROM {DRAFT} WHERE TRUE{scope_clause(store_ids)} ORDER BY store_id;")
    if not rows:
        raise SystemExit(f"{DRAFT} 이 비었다 — 먼저 --build 로 만들 것")
    if store_ids is not None and {int(r["store_id"]) for r in rows} != set(map(int, store_ids)):
        raise ValueError("requested feature scope is incomplete; rebuild labels first")
    out, outcomes = [], {}
    for r in rows:
        status = r["build_status"]
        if status not in ("WITH_LABELS", "WITHOUT_LABELS", "NEEDS_REVIEW") or not r["profile_evidence_fingerprint"]:
            raise ValueError(f"store {r['store_id']}: legacy/unverified label draft; rebuild first")
        outcomes[r["store_id"]] = {"status": status, "profile_fingerprint": r["profile_evidence_fingerprint"]}
        if status == "WITH_LABELS":
            out.append([r["store_id"]] + [r[c] if r[c] != "" else None for c in COLS])
    return FeatureBuild(out, outcomes)


def require_publishable_outcomes(result):
    """No silent subset publishing or force flag for unverified legacy policy changes."""
    blocked = sorted(sid for sid, outcome in result.outcomes.items() if outcome["status"] == "NEEDS_REVIEW")
    if blocked:
        raise ValueError("NEEDS_REVIEW stores " + ",".join(map(str, blocked))
                         + ": selected publish blocked; existing profile/feature rows stay unchanged")


def connect(target, env_path):
    import psycopg2
    t = TARGETS[target]
    password = t.get("password") or read_env(env_path, t["password_key"])
    return psycopg2.connect(host="127.0.0.1", port=t["port"], user=t["user"], password=password,
                            dbname="soboro", connect_timeout=10, application_name="recommendation_feature_load")


def preflight(cur, target):
    cur.execute("SELECT column_name, data_type FROM information_schema.columns WHERE table_name = %s", (TABLE,))
    cols = {name: typ for name, typ in cur.fetchall()}
    if not cols:
        raise SystemExit(f"{target}: {TABLE} 이 없거나 볼 권한이 없다 — BE PR #777 이 배포된 뒤에 돌릴 것. 아무것도 쓰지 않았다")
    problems = [f"{n} {'없음' if n not in cols else f'이 {cols[n]}'}" for n, typ in REQUIRED_COLUMNS.items()
                if n not in cols or cols[n] != typ]
    if problems:
        raise SystemExit(f"{target} {TABLE} 이 BE #777 모양이 아니다 — {' · '.join(problems)}. 아무것도 쓰지 않았다")

    # 권한은 표 모양과 다른 층이다. 새 표에는 SELECT 만 상속되고 INSERT/UPDATE 는 GRANT 로 따로 줘야 하는데
    # (BE #777 리뷰에서 잡혔다), 랩·개발에는 dataops_writer 롤이 없어 GRANT 가 건너뛴다 — 즉 그쪽에서
    # 통과해도 운영 권한은 검증되지 않는다. role_table_grants 대신 has_table_privilege 를 쓴다:
    # 롤을 통해 상속받은 권한까지 답해서 "정말 쓸 수 있나" 에 맞다.
    cur.execute("SELECT %s AS me, has_table_privilege(%s, 'SELECT') AS s, has_table_privilege(%s, 'INSERT') AS i, "
                "has_table_privilege(%s, 'UPDATE') AS u", (f"{target}:{TABLE}", TABLE, TABLE, TABLE))
    _, can_select, can_insert, can_update = cur.fetchone()
    missing = [n for n, ok in (("SELECT", can_select), ("INSERT", can_insert), ("UPDATE", can_update)) if not ok]
    if missing:
        cur.execute("SELECT current_user")
        raise SystemExit(f"{target}: {cur.fetchone()[0]} 가 {TABLE} 에 {' · '.join(missing)} 권한이 없다 — "
                         f"GRANT 가 돌지 않았다(대상 롤이 그 DB 에 없으면 건너뛴다). 아무것도 쓰지 않았다")
    print(f"사전 점검 OK — {target} {TABLE} 은 #777 모양 (filling_rich · vibe_trendy 0~1) · SELECT·INSERT·UPDATE 가능")


# 값은 psql --csv 로 읽어 전부 문자열이라 SQL 쪽에서 캐스팅한다
TEMPLATE = ("(%s, " + ", ".join(["%s::numeric"] * 9) + ", %s, %s::int, %s::int, %s::jsonb, %s, %s, "
            "%s::timestamp, %s::timestamp)")


def publish_profile_and_features(connection, args, profiles, result, invalidated):
    require_publishable_outcomes(result)
    import local_db
    import store_profile_load
    from kind_evidence_publish import publish

    profile_columns = store_profile_load.COLS + list(store_profile_load.OPTIONAL_COLS)
    _, profile_template = store_profile_load.upsert_sql(list(store_profile_load.OPTIONAL_COLS))
    manifest = {"batch_version": BATCH_VERSION, "dictionary_version": DICT_VERSION,
                "evidence_fingerprints": {sid: row["profile_fingerprint"] for sid, row in result.outcomes.items()}}
    outcome = publish(target=args.target, connection=connection, connect_local=local_db.connect,
                      profile_rows=profiles, feature_rows=result.rows, invalidated_ids=invalidated,
                      profile_columns=profile_columns, profile_template=profile_template,
                      feature_columns=["store_id"] + COLS, feature_template=TEMPLATE,
                      manifest=manifest, dry_run=args.dry_run)
    print(f"공통 publish {outcome['attempt_id']}: {outcome['status']}")
    return outcome


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--env", default=os.path.join(REPO_ROOT, ".env"))
    ap.add_argument("--build", action="store_true", help="계산해서 로컬 검수표를 새로 만든다")
    ap.add_argument("--target", choices=sorted(TARGETS), help="검수표를 이 DB 의 store_recommendation_feature 로 upsert")
    ap.add_argument("--dry-run", action="store_true", help="실제로 INSERT 까지 해 보고 ROLLBACK — CHECK·FK 를 진짜 표에서 확인한다")
    ap.add_argument("--apply-invalidations", action="store_true", help="검수한 WITHOUT_LABELS ID에 한해 기존 라벨 무효화를 승인한다")
    ap.add_argument("--store-ids", type=int, nargs="+", help="계산/게시할 명시적 가게 ID 범위")
    args = ap.parse_args()
    if not args.build and not args.target:
        ap.error("--build 또는 --target 중 하나는 있어야 한다")

    if args.build:
        write_draft(build(args.env, args.store_ids))
    if not args.target:
        return 0

    result = read_draft(args.store_ids)
    require_publishable_outcomes(result)
    invalidated = [int(sid) for sid, outcome in result.outcomes.items() if outcome["status"] == "WITHOUT_LABELS"]
    if invalidated and not args.apply_invalidations:
        raise SystemExit(f"WITHOUT_LABELS {len(invalidated)}곳: 검수 후 --apply-invalidations가 필요합니다. 아무것도 쓰지 않았습니다")
    import store_profile_load
    expected = {sid: outcome["profile_fingerprint"] for sid, outcome in result.outcomes.items()}
    profiles = store_profile_load.read_draft(BATCH_VERSION, expected_evidence=expected)
    print(f"공통 검수표 {len(profiles):,}곳 · 라벨 {len(result.rows):,} · 무효화 {len(invalidated):,} → {args.target}", flush=True)
    conn = connect(args.target, args.env)
    cur = conn.cursor()
    try:
        preflight(cur, args.target)
        optional = store_profile_load.preflight(cur, args.target)
        if optional != list(store_profile_load.OPTIONAL_COLS):
            raise ValueError("signature_kinds column is required for evidence-backed publishing")
        # Shared publisher owns commit/rollback and the explicit invalidation scope.
        publish_profile_and_features(conn, args, profiles, result, invalidated)
    finally:
        cur.close()
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
