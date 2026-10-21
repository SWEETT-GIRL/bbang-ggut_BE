"""가게 태그 규칙 — store_signal(로컬)로 가게 태그 8개에 0~1 값을 매긴다. LLM 없음. (dataops #11 새 사전, 2026-09-19)

태그는 "이 가게가 무엇으로 알려진 곳인가" 다. 회원 태그(BE user_taste_profile.tags)는 회원이 좋아한 가게들의 태그 평균이고,
추천 점수의 태그 항 tag_match = Σ 회원태그·가게태그 / Σ 회원태그 는 가게 값에 대해 증가 함수라 "X 가 낮은 가게를 좋아한다" 를
표현하지 못한다. 그래서 사전을 두 원칙으로 짰다(랩 측정 — 운영 신호 11,491건 · 좋아한 가게 4곳 이상 회원 198명).
  · 서로 반대인 취향은 태그 둘로 나누고, 같은 방향으로 겹치는 것만 하나로 합친다. 가성비 ↔ 공간사진(로그비 상관 −0.56),
    디저트 ↔ 빵맛(−0.74)은 나누고, 옛 카페형·테이크아웃·혼빵은 머물기 하나로 합쳤다(커피음료–머물기 +0.72).
  · 회원끼리 갈려야 태그다 — 회원들이 좋아한 가게 풀에서 무작위로 뽑은 것보다 회원 값이 더 퍼져야 한다. 평균이 높아도 갈림이 강하면
    태그(특별한메뉴 1.47배), 평균이 높고 갈림이 약하면 태그가 아니라 인기 쪽 재료라 여기서 뺐다(빵맛 1.23 · 종류다양 1.24).

태그 — 키는 BE TasteTag · jsonb 키 · SQL 파라미터가 함께 쓰는 식별자라 문장부호를 넣지 않는다(화면 문구는 BE 가 따로 둔다)
  웨이팅      줄 서는 명소 — 리뷰 대기시간 테마 · 방송 · 웨이팅/오픈런 키워드 (규칙, 옛 웨이팅감수)
  머물기      앉아서 머물기 좋은 곳 — 투표: 커피·음료·차 · 좌석 편함 · 오래 머물기 · 집중 · 대화 ("매장이 넓어요"는 대형 몫)
  공간사진    인테리어·사진·뷰가 좋은 곳 — 투표: 인테리어 · 사진 · 뷰 · 컨셉 (옛 데이트와 상관 0.94)
  가성비      투표: 가성비가 좋아요
  건강한맛    투표: 건강한 맛 · 재료가 신선해요
  특별한메뉴  투표: 특별한 메뉴가 있어요
  대형        넓은 대형 매장 — 단체 · 대기공간 · 매장이 넓어요 · 주차 (규칙)
  디저트      투표: 디저트가 맛있어요 — 빵맛을 인기 쪽으로 뺀 대신 디저트 위주 가게를 좋아하는 회원의 균형추
뺀 것: 노포·신상·아침·저녁(v4 — 취향이 아니라 가게 사실), 동네빵집(카페형의 뒤집기에 가깝고 갈림 약함),
      선물(회원끼리 안 갈리고 빼도 상위 4곳이 바뀐 쌍 6%), 데이트(→ 공간사진), 카페형·테이크아웃·혼빵(→ 머물기).

투표 태그 값 = 수축한 lift 의 백분위를 lift 1 이 0.5 에 오도록 두 토막으로 편 것
  lift = ((c + m·p) / (T + m)) / p     c 그 태그 표 · T 가게 전체 표 · p 태그의 사전 비율 · m = 20
  값 = 0.5·q/q1 (q < q1) · 0.5 + 0.5·(q−q1)/(1−q1) (q ≥ q1)     q 기준 분포에서의 백분위 · q1 lift 1 의 백분위
  · 0.5 이상은 모든 태그에서 "보통 빵집(lift 1)보다 두드러진다" 이고, 표가 아예 없는 가게는 lift 가 정확히 1 이라 정확히 0.5 를 받는다
    ("정보 없음" = 평균 = 0.5, 키가 비는 가게가 없다). 순서는 백분위 그대로다.
  · 한 토막 백분위로 두면 0표가 많은 태그(건강한맛 0표 24%)는 평균보다 적은 가게도 0.5 를 넘는다 — 269표 중 3표(lift 0.60)가
    0.60 이 되어 BE 배지 임계(0.5)에 걸렸다.
  · m = 20 은 "20표면 데이터와 사전을 반반 믿는다" 는 뜻이다. 표가 적은 가게는 lift 1(보통 빵집) 쪽으로 당겨지고, 0표도 T 가 클수록
    더 낮게 선다 — 500표 중 0표는 20표 중 0표보다 강한 "아니다" 다. 친절해요(91%)처럼 흔한 표가 분모를 채우는 문제도 비율이 아니라
    전체 대비 배수(lift)로 재서 사라진다.
  · p 는 20표 이상 빵집의 가게별 비율 평균(가게당 한 표)으로 추정한다. 표 합으로 재면 큰 가게 쪽으로 기울고, 작은 가게를 넣으면
    3표 중 1표 같은 값이 드문 태그의 사전을 부풀린다. 추정은 깨끗하게, 적용은 전부에.
  · 백분위의 기준 분포는 표가 있는 빵집(직전 검수표의 bakery_score ≥ 0.5 — 추천 후보와 같은 모집단)이다.
  · 20표 미만을 비우고 BE 가 0.5 로 채우던 안은 버렸다. 한 토막 백분위에서는 lift 1 의 자리가 태그에 따라 0.46~0.80 이라
    20표 경계에서 값이 튄다. 두 토막으로 펴면서 그 자리가 0.5 로 모였다.
규칙 태그(웨이팅·대형)는 옛 규칙 그대로의 0~1 이라 백분위가 아니다 — 두 척도가 섞여 있다.

점수는 근거(evidence)와 함께 나온다 — 눈검사에서 "왜 이 값인지"를 보기 위해.

사용
    python scripts/store_tags.py                                     # 테스트셋 30곳 (가게 이름만 운영에서 읽는다)
    python scripts/store_tags.py --store-ids 465426,465987           # 지정 가게
"""
import argparse
import bisect
import csv
import io
import json
import math
import os
import subprocess

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TAGS = ["웨이팅", "머물기", "공간사진", "가성비", "건강한맛", "특별한메뉴", "대형", "디저트"]
VOTE_TAGS = {   # 네이버 방문자 키워드 투표(votedKeywords) 코드 묶음
    "머물기": ("coffee_good", "drink_good", "tea_good", "comfy", "stay_long", "study_good", "talk_good"),
    "공간사진": ("interior_cool", "photo_good", "view_good", "concept_unique"),
    "가성비": ("price_cheap",),
    "건강한맛": ("taste_healthy", "fresh"),
    "특별한메뉴": ("special_menu",),
    "디저트": ("dessert_good",),
}
SHRINK_VOTES = 20        # m — 20표면 데이터와 사전을 반반 믿는다
PRIOR_MIN_VOTES = 20     # 사전 비율 p 를 추정할 때 쓰는 가게의 최소 표
REFERENCE_BAKERY = 0.5   # 백분위 기준 분포 — 빵집다움 하한(BE recommendation.score.min-bakery-score 와 같다)


def read_env(path, key):
    for line in open(path, encoding="utf-8"):
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    raise SystemExit(f"{path}: {key} 없음")


def psql_csv(args, sql, env=None):
    env_flags = [x for k, v in (env or {}).items() for x in ("-e", f"{k}={v}")]
    r = subprocess.run(["docker", "exec", "-i", *env_flags, "bbanggut-dataops-db", "psql", *args, "--csv", "-v", "ON_ERROR_STOP=1", "-f", "-"],
                       input=sql, capture_output=True, text=True, encoding="utf-8")
    if r.returncode:
        raise SystemExit("psql 실패: " + r.stderr.strip()[:500])
    return list(csv.DictReader(io.StringIO(r.stdout)))


def local_csv(sql):
    return psql_csv(["-U", "dataops", "-d", "dataops"], sql)


def prod_csv(sql, env_path):
    return psql_csv(["-h", "host.docker.internal", "-p", "15432", "-U", read_env(env_path, "PROD_DB_USERNAME"), "-d", "soboro"], sql,
                    env={"PGPASSWORD": read_env(env_path, "PROD_DB_PASSWORD"), "PGSSLMODE": "require", "PGOPTIONS": "-c default_transaction_read_only=on"})


def clip(x):
    return max(0.0, min(1.0, x))


def log_scale(count, saturate):
    """count 가 saturate 이상이면 1.0 — 절대 건수 기반 (표 비율은 리뷰 많은 가게에서 희석되므로)"""
    return clip(math.log1p(max(count, 0)) / math.log1p(saturate))


def shrunk_lift(count, total, prior):
    return ((count + SHRINK_VOTES * prior) / (total + SHRINK_VOTES)) / prior


def vote_scale():
    """투표 태그의 사전 비율 p 와 백분위 기준 분포(표가 있는 빵집의 수축 lift, 정렬)를 로컬 store_signal · store_profile_draft 로 만든다.
    검수표가 비어 있으면(첫 배치) 빵집다움을 모르므로 가게 전부를 기준으로 쓴다."""
    per_tag = ", ".join(
        f"COALESCE(sum(kv.c) FILTER (WHERE kv.code IN ({', '.join(repr(c) for c in codes)})), 0) AS \"{tag}\""
        for tag, codes in VOTE_TAGS.items())
    rows = local_csv(
        "WITH kv AS (SELECT s.store_id, e->>'code' AS code, (e->>'count')::float8 AS c FROM store_signal s, "
        "jsonb_array_elements(CASE WHEN jsonb_typeof(s.signals->'votedKeywords') = 'array' THEN s.signals->'votedKeywords' ELSE '[]' END) e) "
        f"SELECT s.store_id, d.bakery_score, COALESCE(sum(kv.c), 0) AS total, {per_tag} "
        "FROM store_signal s LEFT JOIN kv ON kv.store_id = s.store_id LEFT JOIN store_profile_draft d ON d.store_id = s.store_id "
        "GROUP BY s.store_id, d.bakery_score;")
    known = any(r["bakery_score"] for r in rows)
    ref = [r for r in rows if not known or (r["bakery_score"] and float(r["bakery_score"]) >= REFERENCE_BAKERY)]
    prior, dist, q1 = {}, {}, {}
    for tag in VOTE_TAGS:
        shares = [float(r[tag]) / float(r["total"]) for r in ref if float(r["total"]) >= PRIOR_MIN_VOTES]
        prior[tag] = max(sum(shares) / len(shares), 1e-6)
        dist[tag] = sorted(shrunk_lift(float(r[tag]), float(r["total"]), prior[tag]) for r in ref if float(r["total"]) > 0)
        q1[tag] = min(max(bisect.bisect_left(dist[tag], 1.0) / len(dist[tag]), 1e-6), 1 - 1e-6)
    return {"prior": prior, "dist": dist, "q1": q1, "reference": "빵집" if known else "전체(검수표 없음)"}


def vote_value(scale, tag, count, total):
    """(0~1, lift) — 기준 분포의 백분위 q 를 lift 1 의 백분위 q1 이 0.5 에 오도록 두 토막으로 편다(머리말)"""
    lift = shrunk_lift(count, total, scale["prior"][tag])
    dist, q1 = scale["dist"][tag], scale["q1"][tag]
    q = bisect.bisect_left(dist, lift) / len(dist)
    return (0.5 * q / q1 if q < q1 else 0.5 + 0.5 * (q - q1) / (1 - q1)), lift


def score(sig, scale):
    s = json.loads(sig["signals"])
    votes = {v["code"]: v["count"] for v in s.get("votedKeywords") or []}
    themes = {t["code"]: t["count"] for t in s.get("reviewThemes") or []}
    conv = set(s.get("conveniences") or [])
    kws = " ".join(s.get("keywordList") or [])
    ev, out = {}, {}

    wait_count = themes.get("waitingtime", 0)
    out["웨이팅"] = clip(0.7 * log_scale(wait_count, 40) + 0.3 * bool(s.get("broadcastInfos")) + 0.2 * any(w in kws for w in ("웨이팅", "오픈런")))
    ev["웨이팅"] = f"대기시간 테마 {wait_count}건 방송 {len(s.get('broadcastInfos') or [])}"

    out["대형"] = clip(0.35 * ("단체 이용 가능" in conv) + 0.25 * ("대기공간" in conv) + 0.6 * log_scale(votes.get("spacious", 0), 60) + 0.1 * ("주차" in conv))
    ev["대형"] = f"단체 {'Y' if '단체 이용 가능' in conv else 'N'} 대기 {'Y' if '대기공간' in conv else 'N'} 넓어요 {votes.get('spacious', 0)}표"

    total = sum(votes.values())
    for tag, codes in VOTE_TAGS.items():
        count = sum(votes.get(c, 0) for c in codes)
        out[tag], lift = vote_value(scale, tag, count, total)
        ev[tag] = f"{count}/{total}표 lift {lift:.2f}"
    return out, ev


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", default=os.path.join(REPO_ROOT, ".env"))
    parser.add_argument("--testset", default=os.path.join(REPO_ROOT, "matching", "course_testset_20260904.tsv"))
    parser.add_argument("--store-ids")
    parser.add_argument("--threshold", type=float, default=0.5)
    args = parser.parse_args()
    ids = args.store_ids.split(",") if args.store_ids else [r["store_id"] for r in csv.DictReader(open(args.testset, encoding="utf-8"), delimiter="\t")]

    scale = vote_scale()
    sigs = {r["store_id"]: r for r in local_csv(f"SELECT store_id, category, signals::text AS signals FROM store_signal WHERE store_id IN ({','.join(ids)});")}
    names = {r["store_id"]: r["name"] for r in prod_csv(f"SELECT id AS store_id, name FROM store WHERE id IN ({','.join(ids)});", args.env)}

    print("사전 비율 p(20표↑ 빵집, 가게당 평균): " + " · ".join(f"{t} {p:.3f}" for t, p in scale["prior"].items()))
    print(f"백분위 기준: 표 있는 {scale['reference']} {len(scale['dist']['가성비']):,}곳 · lift 1 의 백분위(→ 0.5): "
          + " · ".join(f"{t} {q:.2f}" for t, q in scale["q1"].items()) + f" | 태그 임계 {args.threshold}\n")
    for sid in ids:
        if sid not in sigs:
            print(f"{sid}: store_signal 없음 — rebuild 필요")
            continue
        scores, ev = score(sigs[sid], scale)
        on = [f"{t} {scores[t]:.2f}" for t in TAGS if scores[t] >= args.threshold]
        print(f"■ {names.get(sid, sid)} ({sid}) {sigs[sid]['category']}")
        print("   태그: " + (", ".join(on) if on else "(없음)"))
        print("   근거: " + " | ".join(f"{t}={scores[t]:.2f} [{ev[t]}]" for t in TAGS))


if __name__ == "__main__":
    main()
