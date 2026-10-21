# -*- coding: utf-8 -*-
"""축 건강검진 — 축이 자기 이름값을 하는지, 규칙이 데이터 구조를 따라가는지 두 눈금으로 잰다.

왜 필요한가: 축을 고칠 때마다 "나아졌다"를 말하려면 기준이 있어야 하는데,
분포 균형(최대 칸 몇 %)은 품질 지표가 아니다 — 랜덤 배정도 균형은 완벽하다.
그래서 축 바깥의 독립 출처와 맞대는 눈금 둘을 쓴다.

  눈금 A  축 ↔ 외부 기준 상관. 축을 만든 재료(종류 매핑표)와 무관한 신호로 검산한다.
          맛은 리뷰 투표의 bread_good/dessert_good 비율, 분위기는 tags(리뷰 어휘·규모).
          식감·속은 대응하는 외부 신호가 없어 검사하지 못한다 — 한계로 남긴다.
  눈금 B  k-means 군집이 규칙 타입으로 얼마나 흩어지는가. 낮은 군집이 규칙의 구멍이다.
          군집은 4축이 아니라 v3 라벨 9차원으로 만든다 — 4축으로 만들면 순환이다.
          랜덤 배정으로는 오르지 않으므로 균형과 달리 진짜 개선만 잡힌다.

사용: python scripts/axis_healthcheck.py            # 로컬만 읽는다 (터널 불필요)
      python scripts/axis_healthcheck.py --save before.json
      python scripts/axis_healthcheck.py --compare before.json
"""
import argparse
import json
import math
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from store_profile_batch import AXES  # noqa: E402
from store_tags import local_csv  # noqa: E402

VIBE_TREND = ("interior_cool", "photo_good", "concept_unique")
VIBE_CLASSIC = ("cozy", "atmosphere_calm")
# (축, 기준 이름, 기대 부호, 값 뽑는 법) — 기대 부호는 축의 + 극과 같이 움직여야 하는 방향
CRITERIA = [
    ("taste", "빵 투표 비중 bread/(bread+dessert)", +1, "vote_bread"),
    ("taste", "건강한 맛 투표 비중", +1, "vote_healthy"),
    ("vibe", "데이트 태그", +1, "tag_데이트"),
    ("vibe", "카페형 태그", +1, "tag_카페형"),
    ("vibe", "동네빵집 태그", -1, "tag_동네빵집"),
]


def corr(xs, ys):
    n = len(xs)
    if n < 2:
        return 0.0
    mx, my = sum(xs) / n, sum(ys) / n
    sx = math.sqrt(sum((x - mx) ** 2 for x in xs))
    sy = math.sqrt(sum((y - my) ** 2 for y in ys))
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / (sx * sy) if sx and sy else 0.0


def load():
    prof, tags = {}, {}
    for r in local_csv("SELECT store_id, axis_taste, axis_texture, axis_filling, axis_vibe, bbti_type, "
                       "tags::text AS t FROM store_profile_draft WHERE axis_taste IS NOT NULL;"):
        prof[r["store_id"]] = {a: float(r[f"axis_{a}"]) for a in AXES}
        prof[r["store_id"]]["type"] = r["bbti_type"] or None   # 4글자 코드(2026-09-13 부터). Counter 키로만 쓴다
        try:
            tags[r["store_id"]] = json.loads(r["t"])
        except Exception:
            tags[r["store_id"]] = {}
    votes = {}
    for r in local_csv("SELECT store_id, signals::text AS s FROM store_signal;"):
        if r["store_id"] not in prof:
            continue
        try:
            g = json.loads(r["s"])
        except Exception:
            continue
        v = {x["code"]: int(x["count"]) for x in (g.get("votedKeywords") or [])}
        if sum(v.values()):
            votes[r["store_id"]] = v
    return prof, tags, votes


def criterion_value(key, sid, tags, votes):
    v = votes.get(sid)
    if key == "vote_bread":
        if not v:
            return None
        b, d = v.get("bread_good", 0), v.get("dessert_good", 0)
        return b / (b + d) if (b + d) >= 5 else None
    if key == "vote_healthy":
        return v.get("taste_healthy", 0) / sum(v.values()) if v else None
    if key.startswith("tag_"):
        return tags.get(sid, {}).get(key[4:])
    return None


def gauge_a(prof, tags, votes):
    out = []
    for axis, name, sign, key in CRITERIA:
        pairs = [(prof[s][axis], criterion_value(key, s, tags, votes)) for s in prof]
        pairs = [(x, y) for x, y in pairs if y is not None]
        c = corr([x for x, _ in pairs], [y for _, y in pairs]) if pairs else 0.0
        out.append({"axis": axis, "criterion": name, "expect": sign, "corr": round(c, 3), "n": len(pairs)})
    return out


def store_kind_vectors(env):
    """가게 벡터를 4축이 아니라 v3 라벨 9차원으로 만든다 (운영 메뉴 구성 기준).

    4축으로 군집을 만들고 그 4축이 만든 타입과 비교하면 순환이라 늘 높게 나온다(실측 0.749).
    군집은 규칙이 보지 못하는 표현에서 만들어야 눈금이 된다."""
    import csv as _csv
    from axis_v3_labels import LABELS
    tsv = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "matching", "menu_kind_label_v3_draft.tsv")
    meta = {r["kind"]: r for r in _csv.DictReader(open(tsv, encoding="utf-8-sig"), delimiter="	")}
    lab = {k: LABELS[k] for k in LABELS if meta.get(k, {}).get("빵") != "아님"}
    from store_tags import prod_csv
    acc = defaultdict(lambda: [0.0] * 10)
    for m in prod_csv("SELECT m.store_id, mc.name AS kind, COALESCE(m.like_count,0) AS likes FROM menu m "
                      "JOIN menu_category mc ON mc.id = m.menu_category_id JOIN store s ON s.id = m.store_id "
                      "AND s.status='ACTIVE' WHERE m.deleted_at IS NULL;", env):
        if m["kind"] not in lab:
            continue
        w = 1.0 + int(m["likes"])
        a = acc[m["store_id"]]
        for i in range(9):
            a[i] += lab[m["kind"]][i] * w
        a[9] += w
    return {s: [x / a[9] for x in a[:9]] for s, a in acc.items() if a[9] > 0}


KIND_AXES = ("taste", "texture", "filling")   # 분위기는 뺀다 — 아래 설명


def kind_code(p):
    """종류에서 나오는 세 축만으로 만든 코드 1~8.

    눈금 B 는 '메뉴 종류로 만든 군집을 규칙이 따라가나'를 재므로, 대조하는 쪽도 종류에서 나오는 축만 쓴다.
    분위기(신상빵↔고전빵)도 종류에서 나오지만 성질이 달라(맛·식감·속은 한 입의 감각, 분위기는 시기) 뺐다.

    부호만 본다 — |축| < NEUTRAL 을 접지 않는다. 그래서 이 눈금은 2026-09-10 의 '중간 접힘' 수정을
    감지하지 못한다. 처음부터 고친 뒤의 동작을 재고 있었기 때문이다. 그 수정은 저장된 bbti_type 을
    이 눈금이 이미 가정하던 것과 일치시킨 일관성 수정이지, 축 품질이 달라진 것이 아니다."""
    code = 1
    for bit, a in zip((4, 2, 1), KIND_AXES):
        if p[a] > 0:
            code += bit
    return code


def kind_label(code):
    b = code - 1
    return ("담백" if b & 4 else "달콤") + "·" + ("바삭" if b & 2 else "부드") + "·" + ("심플" if b & 1 else "풍부")


def gauge_b(prof, env, k=16):
    try:
        import numpy as np
        from sklearn.cluster import KMeans
    except ImportError:
        return None
    vec = store_kind_vectors(env) if env else None
    if vec:
        sids = [s for s in prof if prof[s]["type"] and s in vec]
        X = np.array([vec[s] for s in sids])
    else:
        sids = [s for s in prof if prof[s]["type"]]
        X = np.array([[prof[s][a] for a in AXES] for s in sids])
    Z = (X - X.mean(0)) / (X.std(0) + 1e-9)
    lab = KMeans(n_clusters=k, n_init=10, random_state=0).fit(Z).labels_
    out = []
    for ci in range(k):
        idx = [i for i in range(len(sids)) if lab[i] == ci]
        if not idx:
            continue
        c = Counter(kind_code(prof[sids[i]]) for i in idx)
        top_t, top_n = c.most_common(1)[0]
        out.append({"cluster": ci, "n": len(idx), "types": len(c),
                    "purity": round(top_n / len(idx), 3), "top_type": top_t,
                    "top_label": kind_label(top_t)})
    out.sort(key=lambda r: r["purity"])
    return out


def report(prof, tags, votes, env=None):
    a = gauge_a(prof, tags, votes)
    b = gauge_b(prof, env)
    types = Counter(prof[s]["type"] for s in prof if prof[s]["type"])
    n = sum(types.values())
    return {"stores": len(prof),
            "gauge_a": a, "gauge_b": b,
            "type_top_share": round(max(types.values()) / n, 3) if n else 0,
            "mean_purity": round(sum(r["purity"] for r in b) / len(b), 3) if b else None}


def show(rep, base=None):
    def delta(now, was, digits=3):
        if was is None:
            return ""
        d = now - was
        return f"  ({d:+.{digits}f})" if abs(d) >= 10 ** -digits else "  (=)"

    print(f"축 있는 가게 {rep['stores']:,}곳\n")
    print("■ 눈금 A — 축이 자기 이름값을 하나 (축 바깥의 독립 신호와 상관)")
    print(f"   {'축':<9}{'기준':<34}{'상관':>8}{'판정':>8}")
    bmap = {(r["axis"], r["criterion"]): r["corr"] for r in (base or {}).get("gauge_a", [])}
    for r in rep["gauge_a"]:
        signed = r["corr"] * r["expect"]
        verdict = "맞음" if signed >= 0.15 else ("무관" if abs(signed) < 0.05 else ("약함" if signed > 0 else "반대"))
        d = delta(r["corr"], bmap.get((r["axis"], r["criterion"])))
        print(f"   {r['axis']:<9}{r['criterion']:<34}{r['corr']:>+8.3f}{verdict:>8}{d}")
    print("   * 식감·속은 대응하는 외부 신호가 없어 검사하지 못한다")
    if rep["gauge_b"]:
        print(f"\n■ 눈금 B — 군집이 규칙 타입으로 흩어지는 정도 (높을수록 규칙이 구조를 잘 따라감)")
        # 군집 번호는 실행마다 바뀐다(같은 데이터라도 초기값에 따라). 군집별 증감은 서로 다른 군집을
        # 비교하는 것이라 뜻이 없어 표시하지 않는다. 비교는 평균 순도로만 한다.
        pb = {}
        for r in rep["gauge_b"][:4]:
            print(f"   군집{r['cluster']:>2} ({r['n']:>5,}곳) 타입 {r['types']:>2}종으로 흩어짐 · "
                  f"최다 {r.get('top_label', r['top_type']):<14} {r['purity']:>5.0%}"
                  f"{delta(r['purity'], pb.get(r['cluster']), 3)}")
        print("   ...")
        for r in rep["gauge_b"][-2:]:
            print(f"   군집{r['cluster']:>2} ({r['n']:>5,}곳) 최다 {r.get('top_label', r['top_type']):<14} "
                  f"{r['purity']:>5.0%}{delta(r['purity'], pb.get(r['cluster']), 3)}")
        print(f"\n   평균 순도 {rep['mean_purity']:.3f}{delta(rep['mean_purity'], (base or {}).get('mean_purity'))}"
              f"   (참고: 최대 타입 비중 {rep['type_top_share']:.1%}"
              f"{delta(rep['type_top_share'], (base or {}).get('type_top_share'))} — 균형은 품질 아님)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--save")
    ap.add_argument("--compare")
    ap.add_argument("--env", default="../bbang-ggut_dataops_BE-localdata/.env",
                    help="운영 .env — 눈금 B 의 군집을 v3 라벨 벡터로 만든다. 없으면 4축으로 (순환이라 참고용)")
    args = ap.parse_args()
    rep = report(*load(), env=args.env)
    base = json.load(open(args.compare, encoding="utf-8")) if args.compare else None
    show(rep, base)
    if args.save:
        json.dump(rep, open(args.save, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(f"\n기준선 저장 {args.save}")


if __name__ == "__main__":
    main()
