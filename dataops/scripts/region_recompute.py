# -*- coding: utf-8 -*-
"""가게 지역(region_1st/2nd/3rd) 좌표 역산 — 카카오 coord2regioncode B타입(법정동) → 앱 어휘 (이슈 #5).

※ 2026-09-26 부터 scripts/region_resolver.py(v3 P4-4)가 대체한다. 이 스크립트의 "해소"는 (구, 동) 이름쌍만 보고,
  투영도 v1(N가를 접지 않음)이다. --propose 로 만든 store.region 제안은 서버 위치 게이트가 지금 투영(v2)의
  근거와 대조하므로 N가 가게에서는 보류된다. 새 지역 작업은 region_resolver.py 로 한다.

1) --fetch : 운영 ACTIVE 가게 좌표를 카카오로 역산해 로컬 scratch.region_kakao 에 쌓는다 (재실행 시 이미 받은 가게는 건너뜀).
2) --report: 현재 region 과 비교해 변경 건수·법정동→앱 키 대조표(해소/미해소)·변경 목록 TSV 를 낸다.
3) --propose: 변경(해소된 것만, 또는 --include-unresolved)을 change_proposal(target=store.region, source=KAKAO) 로 넣는다 → 승인은 approve API.

어휘 규칙 (BE 와 합의, 2026-09-08):
  region_1st = 시도 약칭 (서울특별시→서울, 전북특별자치도→전북, 전남광주통합특별시→광주 5개 구면 광주 아니면 전남)
  region_2nd = region_2depth_name 앞 토큰 (성남시 분당구 → 성남시). 세종은 2depth 가 비어 '세종시'
  region_3rd = 법정동을 BE Region3rd enum 키에 맞춰 해소: 구_법정동 → 구_(N가 뗌) → 구_(N가 뗌)+동 → 시도_구_… 변형.
               해소 안 되면 법정동 그대로 두고 미해소 목록에 올린다 (BE 어휘 안건)
키: KAKAO_REST_API_KEY 는 환경변수 또는 --env 파일. 값은 어디에도 찍지 않는다.
"""
import argparse
import csv
import json
import os
import re
import sys
import time
from collections import Counter, defaultdict

import psycopg2
import requests
from psycopg2.extras import Json

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from store_tags import prod_csv, read_env  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIDO_SHORT = {"서울특별시": "서울", "부산광역시": "부산", "대구광역시": "대구", "인천광역시": "인천", "광주광역시": "광주", "대전광역시": "대전",
              "울산광역시": "울산", "세종특별자치시": "세종", "경기도": "경기", "강원특별자치도": "강원", "강원도": "강원", "충청북도": "충북",
              "충청남도": "충남", "전북특별자치도": "전북", "전라북도": "전북", "전라남도": "전남", "경상북도": "경북", "경상남도": "경남",
              "제주특별자치도": "제주", "전남광주통합특별시": "전남광주", "전남광주": "전남광주"}
GWANGJU_GU = {"동구", "서구", "남구", "북구", "광산구"}
AMBIGUOUS_GU = {"중구", "동구", "서구", "남구", "북구", "강서구", "고성군"}   # BE: 이름 겹치는 구는 시도_구_동
TABLE = "scratch.region_kakao"


def load_enum_keys(path):
    text = open(path, encoding="utf-8").read()
    return set(re.findall(r"^\s+([가-힣0-9_]+),?\s*$", text, re.M))


def short_sido(name, gu):
    s = SIDO_SHORT.get(name, name)
    if s == "전남광주":
        return "광주" if gu in GWANGJU_GU else "전남"
    return s


def resolve_dong(keys, sido, gu, dong):
    """→ (new_3rd, key or None)"""
    if not dong:
        return "", None
    stem = re.sub(r"[0-9]가$", "", dong)
    cands = []
    for c in (dong, stem, stem + "동", re.sub(r"동$", "", stem) + "동"):
        if c and c not in cands:
            cands.append(c)
    for c in cands:
        for k in (f"{gu}_{c}", f"{sido}_{gu}_{c}"):
            if k in keys:
                return k.split("_")[-1], k
    return dong, None


def fold_hdong(h):
    """행정동 → 앱 어휘 접기 (2026-09-08 표본 실측: 앱 region_3rd·BE enum 이 행정동을 접은 모양).
    성수2가3동→성수동 · 청라1동→청라동 · 잠실본동→잠실동 · 종로1·2·3·4가동→종로동 · 교현.안림동→교현동 · 송현1.2동→송현동"""
    x = (h or "").strip()
    x = re.split(r"[.·]", x)[0]                        # 복합 행정동은 앞 이름만 (교현.안림동 → 교현, 송현1.2동 → 송현1)
    x = re.sub(r"제?[0-9]+가[0-9]*동$", "동", x)        # 성수2가3동 → 성수동
    x = re.sub(r"[0-9·,.]+가동$", "동", x)               # 종로1·2·3·4가동 → 종로동
    x = re.sub(r"제?[0-9]+동$", "동", x)                  # 청라1동 → 청라동, 동탄8동 → 동탄동
    x = re.sub(r"본동$", "동", x)                         # 잠실본동 → 잠실동
    x = re.sub(r"[0-9]+$", "", x)                         # 송현1 → 송현
    if x and not re.search(r"(동|읍|면|가|리)$", x):
        x += "동"                                          # 교현 → 교현동
    return x if x and x != "동" else (h or "").strip()   # 접다가 이름이 사라지면 원문 유지


def resolve_h(keys, sido, gu, hdong, bdong):
    """행정동 우선 해소 → (new_3rd, key or None). 후보: 행정동 원문 → 접은 행정동 → 법정동 → 법정동 N가 뗌"""
    folded = fold_hdong(hdong)
    stem_b = re.sub(r"[0-9]가$", "", bdong or "")
    cands = [c for c in dict.fromkeys([hdong, folded, bdong, stem_b, stem_b + "동" if stem_b else ""]) if c]
    for c in cands:
        for k in (f"{gu}_{c}", f"{sido}_{gu}_{c}"):
            if k in keys:
                return k.split("_")[-1], k
    return folded or (bdong or ""), None


def local_conn():
    # 포트를 박지 않는다 — 이 Mac 에서 15433 은 개발 DB 터널이라, 박아 두면 --propose 가 개발 DB 에 제안을 쓴다.
    # local_db 가 DATAOPS_DB_PORT 를 읽는다 (로컬 도커는 15434)
    import local_db
    return local_db.connect()


def ensure_table(conn):
    with conn.cursor() as cur:
        cur.execute("CREATE SCHEMA IF NOT EXISTS scratch")
        cur.execute(f"""CREATE TABLE IF NOT EXISTS {TABLE} (
            store_id BIGINT PRIMARY KEY, lon DOUBLE PRECISION, lat DOUBLE PRECISION,
            b_code TEXT, b_1depth TEXT, b_2depth TEXT, b_3depth TEXT, b_4depth TEXT,
            cur_1st TEXT, cur_2nd TEXT, cur_3rd TEXT,
            new_1st TEXT, new_2nd TEXT, new_3rd TEXT, resolved_key TEXT, resolved BOOLEAN,
            status TEXT NOT NULL, error TEXT, fetched_at TIMESTAMP NOT NULL DEFAULT now())""")
        for col in ("h_2depth TEXT", "h_3depth TEXT", "h_code TEXT", "new_3rd_h TEXT", "resolved_key_h TEXT", "resolved_h BOOLEAN", "h_fetched_at TIMESTAMP"):
            cur.execute(f"ALTER TABLE {TABLE} ADD COLUMN IF NOT EXISTS {col}")
    conn.commit()


def refetch_h(args, key, base, keys):
    """행정동(H) 문서를 받아 H 기준으로 다시 해소한다 — 대상: 아직 H 가 없고 (B 미해소이거나 B 결과가 현재값과 다른) 가게.
    B 결과가 현재값과 같은 가게는 바꿀 것이 없으니 호출하지 않는다 (카카오 일 쿼터 절약)."""
    conn = local_conn()
    ensure_table(conn)
    with conn.cursor() as cur:
        cond = "" if args.all_h else "AND (NOT resolved OR COALESCE(cur_1st,'') <> new_1st OR COALESCE(cur_2nd,'') <> new_2nd OR COALESCE(cur_3rd,'') <> new_3rd)"
        cur.execute(f"SELECT store_id, lon, lat, new_1st, new_2nd, b_3depth FROM {TABLE} WHERE status = 'OK' AND h_fetched_at IS NULL {cond} ORDER BY store_id")
        todo = cur.fetchall()
    if args.limit:
        todo = todo[: args.limit]
    print(f"[refetch-h] 대상 {len(todo):,}", flush=True)
    sess = requests.Session()
    ok = fail = 0
    t0 = time.time()
    for i, (sid, lon, lat, sido, gu, bdong) in enumerate(todo, 1):
        h = None
        err = None
        for attempt in range(4):
            try:
                r = sess.get(f"{base}/v2/local/geo/coord2regioncode.json", params={"x": lon, "y": lat},
                             headers={"Authorization": f"KakaoAK {key}"}, timeout=15)
                if r.status_code == 200:
                    hs = [d for d in r.json().get("documents", []) if d.get("region_type") == "H"]
                    h = hs[0] if hs else None
                    break
                if r.status_code in (401, 403):
                    raise SystemExit(f"[refetch-h] 카카오 인증 실패 {r.status_code}")
                if r.status_code == 429 or r.status_code >= 500:
                    time.sleep(5 * (attempt + 1))
                    continue
                err = f"HTTP {r.status_code}"
                break
            except requests.RequestException as e:
                err = str(e)[:120]
                time.sleep(2)
        hdong = (h or {}).get("region_3depth_name") or ""
        new3, rkey = resolve_h(keys, sido, gu, hdong, bdong)
        with conn.cursor() as cur:
            cur.execute(f"UPDATE {TABLE} SET h_2depth=%s, h_3depth=%s, h_code=%s, new_3rd_h=%s, resolved_key_h=%s, resolved_h=%s, h_fetched_at=now(), error=COALESCE(%s, error) WHERE store_id=%s",
                        ((h or {}).get("region_2depth_name"), hdong or None, (h or {}).get("code"), new3, rkey, bool(rkey), err, sid))
        ok += bool(h)
        fail += not h
        if i % 200 == 0:
            conn.commit()
        if i % 2000 == 0:
            rate = i / (time.time() - t0)
            print(f"[refetch-h] {i}/{len(todo)} — H {ok} 없음 {fail} · {rate:.1f}/s · 남은 ≈{(len(todo) - i) / rate / 60:.0f}분", flush=True)
        time.sleep(args.sleep)
    conn.commit()
    conn.close()
    print(f"[refetch-h] 종료 — H {ok} / 없음 {fail} · {(time.time() - t0) / 60:.1f}분", flush=True)


def fetch(args, key, base, keys):
    env = args.env
    stores = prod_csv("SELECT id, region_1st, region_2nd, region_3rd, ST_X(location::geometry) AS lon, ST_Y(location::geometry) AS lat "
                      "FROM store WHERE status = 'ACTIVE' AND location IS NOT NULL ORDER BY id;", env)
    conn = local_conn()
    ensure_table(conn)
    with conn.cursor() as cur:
        cur.execute(f"SELECT store_id FROM {TABLE} WHERE status = 'OK'")
        done = {r[0] for r in cur.fetchall()}
    todo = [s for s in stores if int(s["id"]) not in done]
    if args.limit:
        todo = todo[: args.limit]
    print(f"[fetch] ACTIVE 좌표 있음 {len(stores):,} · 이미 받음 {len(done):,} · 이번 {len(todo):,}", flush=True)
    sess = requests.Session()
    ok = fail = 0
    t0 = time.time()
    for i, s in enumerate(todo, 1):
        sid, lon, lat = int(s["id"]), float(s["lon"]), float(s["lat"])
        row = {"status": "FAIL", "error": None, "b": None}
        for attempt in range(4):
            try:
                r = sess.get(f"{base}/v2/local/geo/coord2regioncode.json", params={"x": lon, "y": lat},
                             headers={"Authorization": f"KakaoAK {key}"}, timeout=15)
                if r.status_code == 200:
                    docs = [d for d in r.json().get("documents", []) if d.get("region_type") == "B"]
                    if docs:
                        row = {"status": "OK", "error": None, "b": docs[0]}
                    else:
                        row = {"status": "NO_B", "error": "B 문서 없음", "b": None}
                    break
                if r.status_code in (401, 403):
                    raise SystemExit(f"[fetch] 카카오 인증 실패 {r.status_code} — 키 확인")
                if r.status_code == 429 or r.status_code >= 500:
                    time.sleep(5 * (attempt + 1))
                    continue
                row["error"] = f"HTTP {r.status_code} {r.text[:80]}"
                break
            except requests.RequestException as e:
                row["error"] = str(e)[:120]
                time.sleep(2)
        b = row["b"] or {}
        gu_full = b.get("region_2depth_name") or ""
        dong_raw = b.get("region_3depth_name") or ""
        # 세종은 시군구가 없어 2depth 가 비고, 앱 관행은 region_2nd = region_3rd = 읍면동 (조치원읍/나성동 …)
        gu = gu_full.split()[0] if gu_full else dong_raw
        sido = short_sido(b.get("region_1depth_name") or "", gu)
        new_3rd, rkey = resolve_dong(keys, sido, gu, dong_raw)
        with conn.cursor() as cur:
            cur.execute(f"""INSERT INTO {TABLE} (store_id, lon, lat, b_code, b_1depth, b_2depth, b_3depth, b_4depth, cur_1st, cur_2nd, cur_3rd,
                new_1st, new_2nd, new_3rd, resolved_key, resolved, status, error, fetched_at)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,now())
                ON CONFLICT (store_id) DO UPDATE SET lon=EXCLUDED.lon, lat=EXCLUDED.lat, b_code=EXCLUDED.b_code, b_1depth=EXCLUDED.b_1depth,
                b_2depth=EXCLUDED.b_2depth, b_3depth=EXCLUDED.b_3depth, b_4depth=EXCLUDED.b_4depth, cur_1st=EXCLUDED.cur_1st, cur_2nd=EXCLUDED.cur_2nd,
                cur_3rd=EXCLUDED.cur_3rd, new_1st=EXCLUDED.new_1st, new_2nd=EXCLUDED.new_2nd, new_3rd=EXCLUDED.new_3rd, resolved_key=EXCLUDED.resolved_key,
                resolved=EXCLUDED.resolved, status=EXCLUDED.status, error=EXCLUDED.error, fetched_at=now()""",
                (sid, lon, lat, b.get("code"), b.get("region_1depth_name"), gu_full, b.get("region_3depth_name"), b.get("region_4depth_name"),
                 s["region_1st"], s["region_2nd"], s["region_3rd"],
                 sido if b else None, gu if b else None, new_3rd if b else None, rkey, bool(rkey) if b else None, row["status"], row["error"]))
        if row["status"] == "OK":
            ok += 1
        else:
            fail += 1
        if i % 200 == 0:
            conn.commit()
        if i % 2000 == 0:
            rate = i / (time.time() - t0)
            print(f"[fetch] {i}/{len(todo)} — OK {ok} 실패 {fail} · {rate:.1f}/s · 남은 ≈{(len(todo) - i) / rate / 60:.0f}분", flush=True)
        time.sleep(args.sleep)
    conn.commit()
    conn.close()
    print(f"[fetch] 종료 — OK {ok} / 실패 {fail} · {(time.time() - t0) / 60:.1f}분", flush=True)


def refresh_cur(env):
    """로컬에 쌓인 cur_1st/2nd/3rd 를 운영 현재값으로 다시 읽어 온다 — 이미 승인·반영된 가게가 다시 '변경'으로 잡히는 것을 막는다."""
    rows = prod_csv("SELECT id, region_1st, region_2nd, region_3rd FROM store WHERE status = 'ACTIVE';", env)
    conn = local_conn()
    with conn.cursor() as cur:
        cur.execute("CREATE TEMP TABLE _cur (store_id BIGINT PRIMARY KEY, r1 TEXT, r2 TEXT, r3 TEXT) ON COMMIT DROP")
        from psycopg2.extras import execute_values
        execute_values(cur, "INSERT INTO _cur (store_id, r1, r2, r3) VALUES %s ON CONFLICT DO NOTHING",
                       [(int(r["id"]), r["region_1st"], r["region_2nd"], r["region_3rd"]) for r in rows])
        cur.execute(f"UPDATE {TABLE} t SET cur_1st = c.r1, cur_2nd = c.r2, cur_3rd = c.r3 FROM _cur c WHERE c.store_id = t.store_id")
        updated = cur.rowcount
    conn.commit()
    conn.close()
    print(f"[refresh-cur] 운영 현재값 갱신 {updated:,}/{len(rows):,}")


def load_search_region_dongs(env):
    """운영 search_region 의 DONG 행(법정동 시드) → {(구 이름, 동 이름)} — 법정동 기준의 '해소' 판정 기준 (2026-09-08 결정: 앱 지역 어휘는 법정동)"""
    rows = prod_csv("SELECT g.name AS gu, d.name AS dong FROM search_region d JOIN search_region g ON g.id = d.parent_region_id "
                    "WHERE d.type = 'DONG' AND d.is_active;", env)
    return {(r["gu"], r["dong"]) for r in rows}


def apply_b_mode(conn, env):
    """법정동 기준: new_3rd = 카카오 법정동 그대로(N가 접지 않음), resolved = search_region DONG(구, 동)에 있음. 로컬 테이블에 반영."""
    dongs = load_search_region_dongs(env)
    with conn.cursor() as cur:
        cur.execute(f"SELECT store_id, new_2nd, b_3depth FROM {TABLE} WHERE status = 'OK'")
        rows = cur.fetchall()
        for sid, gu, bdong in rows:
            ok = (gu, bdong) in dongs
            cur.execute(f"UPDATE {TABLE} SET new_3rd = %s, resolved = %s, resolved_key = %s WHERE store_id = %s",
                        (bdong, ok, f"{gu}/{bdong}" if ok else None, sid))
    conn.commit()
    return len(dongs)


def report(args, keys):
    """--mode B(기본): 법정동 그대로, 해소 = search_region DONG 에 존재. --mode H: 행정동 접기(비교용)."""
    conn = local_conn()
    if args.mode == "B":
        n = apply_b_mode(conn, args.env)
        print(f"[report] search_region DONG (구,동) {n:,}쌍 기준으로 법정동 해소 판정")
    with conn.cursor() as cur:
        if args.mode == "H":
            cur.execute(f"""SELECT store_id, b_1depth, b_2depth, COALESCE(h_3depth, b_3depth), cur_1st, cur_2nd, cur_3rd, new_1st, new_2nd,
                COALESCE(new_3rd_h, new_3rd), COALESCE(resolved_key_h, resolved_key), COALESCE(resolved_h, resolved), status FROM {TABLE}""")
        else:
            cur.execute(f"SELECT store_id, b_1depth, b_2depth, b_3depth, cur_1st, cur_2nd, cur_3rd, new_1st, new_2nd, new_3rd, resolved_key, resolved, status FROM {TABLE}")
        rows = cur.fetchall()
    conn.close()
    cols = ["store_id", "b_1depth", "b_2depth", "b_3depth", "cur_1st", "cur_2nd", "cur_3rd", "new_1st", "new_2nd", "new_3rd", "resolved_key", "resolved", "status"]
    rs = [dict(zip(cols, r)) for r in rows]
    print(f"[report] 기준: {'행정동(H) 접기, H 없으면 B' if args.mode == 'H' else '법정동(B) 그대로, 해소=search_region DONG 존재'}")
    okr = [r for r in rs if r["status"] == "OK"]
    changed = [r for r in okr if (r["cur_1st"] or "") != r["new_1st"] or (r["cur_2nd"] or "") != r["new_2nd"] or (r["cur_3rd"] or "") != r["new_3rd"]]
    print(f"[report] 받음 {len(rs):,} · OK {len(okr):,} · 실패 {len(rs) - len(okr):,}")
    print(f"[report] 변경 {len(changed):,} — 1st {sum(1 for r in changed if (r['cur_1st'] or '') != r['new_1st']):,} · 2nd {sum(1 for r in changed if (r['cur_2nd'] or '') != r['new_2nd']):,} · 3rd {sum(1 for r in changed if (r['cur_3rd'] or '') != r['new_3rd']):,}")
    print(f"[report] 3rd 해소 {sum(1 for r in okr if r['resolved']):,} · 미해소 {sum(1 for r in okr if not r['resolved']):,} (미해소는 법정동 그대로)")
    unresolved = Counter((r["new_1st"], r["new_2nd"], r["b_3depth"]) for r in okr if not r["resolved"])
    print("[report] 미해소 상위 20:", " | ".join(f"{s} {g} {d}({n})" for (s, g, d), n in unresolved.most_common(20)))
    os.makedirs(args.out_dir, exist_ok=True)
    m = defaultdict(int)
    for r in okr:
        m[(r["new_1st"], r["new_2nd"], r["b_3depth"], r["new_3rd"], r["resolved_key"] or "", "Y" if r["resolved"] else "N")] += 1
    suffix = "_h" if args.mode == "H" else ""
    with open(os.path.join(args.out_dir, f"region_kakao_map_20260908{suffix}.tsv"), "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t"); w.writerow(["sido", "gu", "kakao_3depth", "app_3rd", "region3rd_key", "resolved", "stores"])
        for k, n in sorted(m.items(), key=lambda x: (x[0][5], -x[1])):
            w.writerow([*k, n])
    with open(os.path.join(args.out_dir, f"region_changes_20260908{suffix}.tsv"), "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols, delimiter="\t"); w.writeheader(); w.writerows(changed)
    print(f"[report] 대조표·변경 목록: {args.out_dir}/region_kakao_map_20260908{suffix}.tsv, region_changes_20260908{suffix}.tsv")


def propose(args):
    conn = local_conn()
    with conn.cursor() as cur:
        if args.mode == "H":
            sel = "COALESCE(new_3rd_h, new_3rd) AS n3, COALESCE(h_code, b_code) AS code, COALESCE(resolved_key_h, resolved_key) AS rk, COALESCE(resolved_h, resolved) AS res"
        else:
            sel = "new_3rd AS n3, b_code AS code, resolved_key AS rk, resolved AS res"
        cond = "" if args.include_unresolved else "AND res"
        # source_lon/lat = 역산에 실제로 넘긴 좌표. 서버의 위치 보류 게이트가 이 값을 지금 운영 좌표와 대조한다 —
        # 제안 이후 가게가 움직였으면 이 지역 값은 옛 자리의 것이므로 승인되지 않는다
        cur.execute(f"""SELECT store_id, cur_1st, cur_2nd, cur_3rd, new_1st, new_2nd, n3, code, rk, res, lon, lat FROM (SELECT store_id, cur_1st, cur_2nd, cur_3rd, new_1st, new_2nd, lon, lat, {sel}, status FROM {TABLE}) t
            WHERE status = 'OK' {cond} AND (COALESCE(cur_1st,'') <> new_1st OR COALESCE(cur_2nd,'') <> new_2nd OR COALESCE(cur_3rd,'') <> n3)""")
        rows = cur.fetchall()
        cur.execute("SELECT store_id FROM change_proposal WHERE target = 'store.region' AND status IN ('PENDING','APPLYING')")
        pending = {r[0] for r in cur.fetchall()}
        n = 0
        for sid, c1, c2, c3, n1, n2, n3, code, rkey, resolved, lon, lat in rows:
            if sid in pending:
                continue
            old = {"region_1st": c1, "region_2nd": c2, "region_3rd": c3}
            new = {"region_1st": n1, "region_2nd": n2, "region_3rd": n3, "bjd_code": code, "region3rd_key": rkey,
                   "resolved": resolved, "source_lon": lon, "source_lat": lat}
            cur.execute("""INSERT INTO change_proposal (store_id, target, old_value, new_value, source, snapshot_id, status, created_at, updated_at,
                                           producer, target_family, policy_version)
                           VALUES (%s, 'store.region', %s, %s, 'KAKAO', NULL, 'PENDING', now(), now(),
                                   'REGION_RECOMPUTE', 'STORE_REGION', 'region_recompute-2026-09-26')""",
                        (sid, json.dumps(old, ensure_ascii=False), json.dumps(new, ensure_ascii=False)))
            n += 1
    conn.commit()
    conn.close()
    print(f"[propose] store.region PENDING 생성 {n:,}건 (이미 PENDING {len(pending):,} 건너뜀)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", default="C:/Users/USER/sw17/bbang-ggut_dataops_BE-localdata/.env", help="운영 reader 자격 .env")
    ap.add_argument("--key-env", default=os.path.join(REPO, ".env"), help="KAKAO_REST_API_KEY 가 있는 .env (환경변수가 우선)")
    ap.add_argument("--enum-file", default=os.path.join(REPO, "..", "core/src/main/java/sweetgirl/croissant/store/enums/Region3rd.java"))
    ap.add_argument("--out-dir", default="C:/Users/USER/sw17/dataops-logs")
    ap.add_argument("--fetch", action="store_true", help="B(법정동) 문서 수집 (전수, 재개 가능)")
    ap.add_argument("--refetch-h", action="store_true", help="H(행정동) 문서 수집 — B 미해소·변경 가게만 (--all-h 로 전수)")
    ap.add_argument("--all-h", action="store_true")
    ap.add_argument("--refresh-cur", action="store_true", help="cur_* 를 운영 현재값으로 다시 읽기 (승인 반영분 재제안 방지)")
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--propose", action="store_true")
    ap.add_argument("--mode", choices=["H", "B"], default="B", help="report/propose 기준 — B(기본, 2026-09-08 결정): 법정동 그대로·search_region DONG 으로 해소 판정, H: 행정동 접기(비교용)")
    ap.add_argument("--include-unresolved", action="store_true")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--sleep", type=float, default=0.1, help="호출 간격 (초) — 10/s")
    args = ap.parse_args()
    keys = load_enum_keys(args.enum_file)
    if args.fetch or args.refetch_h:
        key = os.environ.get("KAKAO_REST_API_KEY") or read_env(args.key_env, "KAKAO_REST_API_KEY")
        base = os.environ.get("KAKAO_BASE_URL") or "https://dapi.kakao.com"
        if args.fetch:
            fetch(args, key, base, keys)
        if args.refetch_h:
            refetch_h(args, key, base, keys)
    if args.refresh_cur:
        refresh_cur(args.env)
    if args.report:
        report(args, keys)
    if args.propose:
        propose(args)
    if not (args.fetch or args.refetch_h or args.refresh_cur or args.report or args.propose):
        ap.error("--refresh-cur / --fetch / --refetch-h / --report / --propose 중 하나")


if __name__ == "__main__":
    main()
