"""검수표(로컬 store_profile_draft) → 앱 DB store_profile 적재.

운영 store_profile 은 BE #731 모양이다(타입 4글자 코드 bread_type · 리뷰 컬럼 넷 · attrs 는 편의시설만).
검수표는 같은 모양에 검수용 컬럼(type_label·axis_menu_count·axis_review_count·tag_evidence·signal_snapshot_id·
profile_version·conversion_prior)이 더 있을 뿐이라, 그 컬럼만 버리고 bbti_type → bread_type 으로 이름을 바꿔 옮긴다.

이 모듈의 단독 게시 CLI는 중단했다. recommendation_feature_load.py가 프로필과 라벨의
frozen 근거를 확인하고 공통 publisher로 두 표를 원자적으로 게시한다.
kind_evidence_publish_attempt에 intent·이전 값·예상 결과를 남기며 FK 오류로 scope를 줄이지 않는다.

사용: python scripts/recommendation_feature_load.py --target lab --dry-run --store-ids 123
"""
import argparse
import csv
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from store_profile_batch import BATCH_VERSION  # noqa: E402  — 기본은 지금 배치가 만든 버전만 옮긴다
from store_tags import local_csv, read_env  # noqa: E402
from kind_evidence_store import read_frozen_evidence  # noqa: E402

csv.field_size_limit(10 ** 7)

TARGETS = {
    "dev": {"port": 15434, "user": "sweetgirl", "password_key": "PROD_DB_PASSWORD"},
    "prod": {"port": 15432, "user": "dataops_writer", "password_key": "DATAOPS_WRITER_PASSWORD"},
    # BE docker-compose.lab-postgres.yml 의 로컬 랩(운영 덤프 복제본 — 추천 측정용). 비밀번호는 그 compose 파일에 적힌 로컬 전용 값이다
    "lab": {"port": 5432, "user": "sweetgirl", "password": "soboro-lab"},
}
# 운영 컬럼 순서. 검수표 이름이 다른 것은 bbti_type→bread_type 하나뿐이다.
COLS = ["store_id", "axis_taste", "axis_texture", "axis_filling", "axis_vibe", "axis_source", "bread_type",
        "tags", "attrs", "kinds", "signature_menus", "bakery_score", "store_status", "batch_version", "computed_at",
        "review_count", "rating_count", "review_score", "description"]
# psql --csv 로 읽은 값은 전부 문자열이라 SQL 쪽에서 캐스팅한다. 배열은 '{a,b}' 리터럴 그대로 들어간다.
TEMPLATE = ("(%s, %s::numeric, %s::numeric, %s::numeric, %s::numeric, %s, %s, %s::jsonb, %s::jsonb, %s::text[], %s::text[], "
            "%s::numeric, %s, %s, %s::timestamp, %s::int, %s::int, %s::numeric, %s)")
# 대상 표에 열이 있을 때만 보내는 열과 그 캐스팅 — dataops 가 BE 마이그레이션보다 먼저 만든 열이다. 없으면 알리고 빼고 보낸다.
OPTIONAL_COLS = {"signature_kinds": "%s::text[]"}   # dataops #11 — 대표 메뉴를 빵 종류로 푼 것
TYPE_CODE = re.compile(r"^[SP][FC][RL][ON]$")
# 대상 표가 BE #731(V20260911142251) 모양인지 — 아니면 첫 배치를 보내기 전에 멈춘다.
# 운영은 dev 에서 다 만들어 보고 나중에 배포하므로, 배포 전에 돌리면 bread_type 이 SMALLINT(#720)이고 리뷰 컬럼이 없다.
REQUIRED_COLUMNS = {"bread_type": ("character varying", 4), "review_count": ("integer", None), "rating_count": ("integer", None),
                    "review_score": ("numeric", None), "description": ("text", None)}


def read_draft(batch_version, expected_evidence=None):
    assert re.fullmatch(r"[\w.\-]+", batch_version), batch_version
    scope = ""
    if expected_evidence is not None:
        if not expected_evidence:
            raise ValueError("empty publish scope")
        scope = " AND store_id IN (" + ",".join(str(int(sid)) for sid in expected_evidence) + ")"
    rows = local_csv(
        "SELECT store_id, axis_taste, axis_texture, axis_filling, axis_vibe, axis_source, bbti_type AS bread_type, "
        "tags::text AS tags, attrs::text AS attrs, kinds::text AS kinds, signature_menus::text AS signature_menus, "
        "bakery_score, store_status, batch_version, computed_at::text AS computed_at, "
        "review_count, rating_count, review_score, description, kind_evidence::text, kind_evidence_fingerprint, "
        "(SELECT s.external_id FROM store_sync_state s WHERE s.store_id=store_profile_draft.store_id "
        "AND s.source='NAVER') AS current_external_id, "
        + ", ".join(f"{c}::text AS {c}" for c in OPTIONAL_COLS) + " "
        f"FROM store_profile_draft WHERE batch_version = '{batch_version}'{scope} ORDER BY store_id;")
    if expected_evidence is not None and {str(r["store_id"]) for r in rows} != set(expected_evidence):
        raise ValueError("profile/feature scope differs; rebuild and review before publishing")
    out = []
    for r in rows:
        frozen = json.loads(r["kind_evidence"]) if r.get("kind_evidence") else None
        fp = r.get("kind_evidence_fingerprint")
        state = read_frozen_evidence(frozen, fp)
        state.require_publishable()
        if not r.get("current_external_id") or state.external_id != str(r["current_external_id"]):
            raise ValueError(f"store {r['store_id']}: NAVER mapping changed before publish; rebuild first")
        if expected_evidence is not None and expected_evidence[str(r["store_id"])] != fp:
            raise ValueError(f"store {r['store_id']}: profile was rebuilt after labels; no writes allowed")
        vals = [r[c] if r[c] != "" else None for c in COLS + list(OPTIONAL_COLS)]   # psql --csv 의 NULL 은 빈 문자열
        if vals[6] is not None and not TYPE_CODE.match(vals[6]):
            raise SystemExit(f"store {vals[0]}: bread_type '{vals[6]}' 이 4글자 코드가 아니다 — 검수표가 옛 모양이다")
        out.append(vals)
    return out


def connect(target, env_path):
    import psycopg2
    t = TARGETS[target]
    password = t.get("password") or read_env(env_path, t["password_key"])
    return psycopg2.connect(host="127.0.0.1", port=t["port"], user=t["user"], password=password,
                            dbname="soboro", connect_timeout=10, application_name="store_profile_load")


def preflight(cur, target):
    cur.execute("SELECT column_name, data_type, character_maximum_length FROM information_schema.columns WHERE table_name = 'store_profile'")
    cols = {name: (typ, length) for name, typ, length in cur.fetchall()}
    if not cols:
        raise SystemExit(f"{target}: store_profile 이 없거나 볼 권한이 없다")
    problems = []
    for name, (typ, length) in REQUIRED_COLUMNS.items():
        if name not in cols:
            problems.append(f"{name} 없음")
        elif cols[name][0] != typ or (length and cols[name][1] != length):
            problems.append(f"{name} 이 {cols[name][0]}{f'({cols[name][1]})' if cols[name][1] else ''} (기대 {typ}{f'({length})' if length else ''})")
    if problems:
        raise SystemExit(f"{target} store_profile 이 BE #731(V20260911142251) 모양이 아니다 — {' · '.join(problems)}. "
                         "BE 가 dev → 운영 배포를 마친 뒤 다시 돌릴 것. 아무것도 쓰지 않았다")
    print(f"사전 점검 OK — {target} store_profile 은 #731 모양 (bread_type VARCHAR(4) · 리뷰 컬럼 넷)")
    optional = [c for c in OPTIONAL_COLS if c in cols]
    for c in OPTIONAL_COLS:
        if c not in cols:
            print(f"   {c} 열이 대상에 없다 — 이 열은 빼고 보낸다(BE 마이그레이션 전)")
    return optional


def upsert_sql(optional):
    cols = COLS + optional
    template = TEMPLATE[:-1] + "".join(", " + OPTIONAL_COLS[c] for c in optional) + ")"
    sql = (f"INSERT INTO store_profile ({', '.join(cols)}) VALUES %s ON CONFLICT (store_id) DO UPDATE SET "
           + ", ".join(f"{c} = EXCLUDED.{c}" for c in cols if c != "store_id") + ", updated_at = now()")
    return sql, template


def verify(cur, rows_loaded):
    cur.execute("SELECT COUNT(*), COUNT(bread_type), COUNT(review_score), MIN(review_score), MAX(review_score), "
                "COUNT(description), COUNT(DISTINCT batch_version) FROM store_profile")
    n, typed, scored, smin, smax, descr, versions = cur.fetchone()
    print(f"   store_profile 행 {n:,} · 타입 {typed:,} · 별점 {scored:,} ({smin}~{smax}) · 소개글 {descr:,} · batch_version {versions}종")
    cur.execute("SELECT bread_type, COUNT(*) FROM store_profile WHERE bread_type IS NOT NULL GROUP BY 1 ORDER BY 2 DESC LIMIT 4")
    print("   타입 상위:", ", ".join(f"{t} {c:,}" for t, c in cur.fetchall()))
    cur.execute("SELECT COUNT(*) FROM store_profile p LEFT JOIN store s ON s.id = p.store_id WHERE s.id IS NULL")
    print(f"   store 없는 프로필(FK 위반이면 여기까지 못 온다): {cur.fetchone()[0]}")
    # 표본 대조 — 적재한 값이 검수표와 같은가
    sample = {r[0]: r for r in rows_loaded[:3]}
    cur.execute("SELECT store_id, bread_type, review_count, rating_count, review_score, axis_taste, kinds FROM store_profile "
                "WHERE store_id = ANY(%s) ORDER BY store_id", ([int(s) for s in sample],))
    for sid, bt, rc, rt, rs, at, kinds in cur.fetchall():
        d = sample[str(sid)]
        same = (bt == d[6] and str(rc) == str(d[15] if d[15] is not None else None) and (rs is None) == (d[17] is None))
        print(f"   표본 {sid}: bread_type {bt} · review_count {rc} · rating_count {rt} · review_score {rs} · taste {at} · kinds {kinds[:4] if kinds else kinds} → {'일치' if same else '불일치!'}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", required=True)
    ap.add_argument("--target", choices=TARGETS, required=True)
    ap.add_argument("--batch-version", default=BATCH_VERSION)
    ap.add_argument("--dry-run", action="store_true", help="INSERT 까지 하고 ROLLBACK")
    ap.parse_args()
    raise SystemExit("프로필 단독 적재는 중단했습니다. recommendation_feature_load.py --target으로 같은 근거의 프로필·라벨을 함께 반영하세요")


if __name__ == "__main__":
    main()
