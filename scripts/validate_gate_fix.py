"""게이트 수정 효과 오프라인 검증.

실패(throttled) 가게들이 **실패 당시 저장해둔 후보**(NaverEnrichCandidate)에 대해
현재 select_match 게이트를 다시 적용해, 이번 수정으로 몇 건이 매칭으로 회복되는지 본다.
네이버를 다시 호출하지 않으므로(저장된 후보만 사용) 실행 중인 배치와 충돌 없고 빠르다.

  cd scripts && source .venv/bin/activate && python validate_gate_fix.py

후보가 저장 안 된 가게(검색 0건이라 후보 미저장)는 NO_CANDIDATES 로 분류 — 게이트 수정
대상이 아님.
"""

import csv
from collections import Counter

import requests

import admin_auth
from naver_match import classify_store, select_match

admin_auth.install()

SERVER = "http://localhost:8080"
CSV_PATH = "gate_fix_validation.csv"


def fetch_throttled(size: int = 1000) -> list[dict]:
	url = f"{SERVER}/api/admin/stores/naver-throttled?page=0&size={size}"
	return requests.get(url, timeout=30).json().get("data", {}).get("content", []) or []


def fetch_candidates(store_id: int) -> list[dict]:
	url = f"{SERVER}/api/admin/stores/{store_id}/naver-enrich-candidates"
	return requests.get(url, timeout=15).json().get("data") or []


def to_naver(c: dict) -> dict:
	"""저장된 후보 → score_candidate 가 먹는 naver dict (lat→y, lng→x)."""
	return {
		"name": c.get("name"),
		"y": str(c.get("lat") if c.get("lat") is not None else ""),
		"x": str(c.get("lng") if c.get("lng") is not None else ""),
		"address": c.get("address") or "",
		"category": "",  # 후보 저장 시 카테고리 미보관 — 폐업 판정은 이름으로만
		"id": c.get("naverPlaceId"),
	}


def main() -> None:
	stores = fetch_throttled()
	print(f"실패(throttled) 가게: {len(stores)}건 — 저장된 후보에 새 게이트 재적용\n")

	rows: list[dict] = []
	for s in stores:
		kakao = {
			"name": s["name"],
			"lat": s.get("lat"),
			"lng": s.get("lng"),
			"address": s.get("address") or "",
		}
		cands = [to_naver(c) for c in fetch_candidates(s["id"])]
		matched, scored, reason = select_match(kakao, cands)
		r = classify_store(cands, matched, reason, scored)
		r["store_id"] = s["id"]
		r["store_name"] = s["name"]
		r["store_address"] = kakao["address"]
		rows.append(r)

	# 분류: MATCH = 이번 수정으로 회복(후보 동일, 게이트만 바뀜) / MISMATCH = 여전히 거부
	#       NO_CAND = 저장된 후보 없음(검색 0건) → 게이트 무관
	recovered = [r for r in rows if r["bucket"] == "MATCH"]
	still_reject = [r for r in rows if r["bucket"] == "MISMATCH"]
	no_cands = [r for r in rows if r["bucket"] == "NO_CAND"]

	print(f"{'=' * 64}")
	print(f"총 {len(rows)}건")
	print(f"  ✅ 이제 매칭됨 (회복) : {len(recovered)}")
	print(f"  ❌ 여전히 게이트 거부 : {len(still_reject)}")
	print(f"  ◽ 후보 없음(검색0)   : {len(no_cands)}")

	# 회복 케이스의 게이트 통과 사유 분포
	if recovered:
		reasons = Counter(r["reason"] for r in recovered)
		print("\n  회복 사유별:")
		for reason, n in reasons.most_common():
			print(f"    {n:3d}× {reason}")
		print("\n  회복 샘플 (최대 25):")
		for r in recovered[:25]:
			dist = f"{r['distance_m']:.0f}m" if r["distance_m"] is not None else "-"
			print(f"    {r['store_id']:5d} {r['store_name'][:22]:22s} → '{r['top_cand_name']}'"
				f" ({r['top_score']}점/{r['name_kind']}/{dist})")

	# 여전히 거부 사유 분포
	if still_reject:
		reasons = Counter(r["reason"] for r in still_reject)
		print("\n  여전히 거부 사유별:")
		for reason, n in reasons.most_common():
			print(f"    {n:3d}× {reason}")

	with open(CSV_PATH, "w", encoding="utf-8-sig", newline="") as f:
		fields = ["store_id", "store_name", "store_address", "bucket", "reason",
			"num_candidates", "top_cand_name", "top_score", "name_kind",
			"distance_m", "road_match"]
		writer = csv.DictWriter(f, fieldnames=fields)
		writer.writeheader()
		for r in rows:
			writer.writerow({k: r.get(k) for k in fields})
	print(f"\n  → CSV: scripts/{CSV_PATH}")


if __name__ == "__main__":
	main()
