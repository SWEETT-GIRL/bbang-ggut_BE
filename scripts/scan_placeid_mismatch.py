#!/usr/bin/env python3
"""매칭된 가게의 naver_place_id 가 실제로 그 가게 것인지 검증 (오매칭 탐지, 1회성).

배경: 이전 크롤이 같은 브랜드 근접 지점을 옆 지점 placeId 에 잘못 매칭한 사례 다수
(예: 킴스델리마켓 백제고분로 가게가 롯데잠실점 placeId 를 차지). 그 결과 잘못된 가게는
남의 지점 메뉴/사진을 갖고, 진짜 주인은 409 로 막힌다.

검증: 각 매칭 가게의 placeId 실제 네이버 주소(도로명+번지)를 가져와 가게 주소와 대조.
불일치면 오매칭. 그 placeId 의 진짜 주인(주소가 일치하는 다른 우리 가게)도 같이 찾아준다.

읽기 전용 — DB/네이버 변경 없음. 결과는 콘솔 + CSV.
실행: scripts/.venv/bin/python scripts/scan_placeid_mismatch.py
"""

import csv
import re
import sys
import time
from pathlib import Path

import psycopg2
import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from naver_match import extract_road_and_number  # noqa: E402

OUT_CSV = Path(__file__).resolve().parent / "placeid_mismatch.csv"
UA = {"User-Agent": ("Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
					"AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1")}


def db_config() -> dict:
	props = (Path(__file__).resolve().parent.parent
			/ "src/main/resources/application-local.properties")
	cfg = {}
	for line in props.read_text(encoding="utf-8").splitlines():
		line = line.strip()
		if line.startswith("spring.datasource.") and "=" in line:
			k, _, v = line.partition("=")
			cfg[k.strip()] = v.strip()
	rest = cfg["spring.datasource.url"].split("//", 1)[1]
	hostport, _, dbname = rest.partition("/")
	host, _, port = hostport.partition(":")
	return {"host": host, "port": int(port or 5432), "dbname": dbname,
			"user": cfg["spring.datasource.username"],
			"password": cfg["spring.datasource.password"]}


def naver_address(pid: str) -> str:
	"""placeId 의 네이버 실제 주소(roadAddress 우선) 한 줄. 실패 시 ''."""
	for typ in ("restaurant", "cafe", "place"):
		try:
			r = requests.get(f"https://m.place.naver.com/{typ}/{pid}/home", headers=UA, timeout=12)
			r.encoding = "utf-8"
			addrs = [a for a in re.findall(r'"roadAddress":"([^"]{6,50})"', r.text) if a != "주소"]
			if not addrs:
				addrs = [a for a in re.findall(r'"address":"([^"]{6,50})"', r.text) if a != "주소"]
			if addrs:
				return addrs[0]
		except Exception:
			pass
	return ""


def main() -> None:
	conn = psycopg2.connect(**db_config(), connect_timeout=15)
	cur = conn.cursor()
	cur.execute("SELECT id, name, address, naver_place_id FROM store WHERE naver_place_id IS NOT NULL ORDER BY id")
	stores = cur.fetchall()
	# 주소 도로명+번지 인덱스 (진짜 주인 찾기용)
	addr_index = {}  # (road, num) -> [store...]
	for sid, name, addr, pid in stores:
		rn = extract_road_and_number(addr or "")
		addr_index.setdefault(rn, []).append((sid, name, addr))

	print(f"매칭된 가게 {len(stores)}개 검증 시작 (네이버 조회)...")
	mismatches = []
	checked = 0
	failed_lookup = 0
	for sid, name, addr, pid in stores:
		nv = naver_address(pid)
		checked += 1
		if not nv:
			failed_lookup += 1
			continue
		k_road, k_num = extract_road_and_number(addr or "")
		n_road, n_num = extract_road_and_number(nv)
		# 도로명이 둘 다 있고 다르면 오매칭. (번지까지 보면 더 엄격하지만 도로명 불일치만으로 충분)
		if k_road and n_road and k_road != n_road:
			owner = addr_index.get((n_road, n_num), [])
			owner = [o for o in owner if o[0] != sid]
			mismatches.append((sid, name, addr, pid, nv,
							"; ".join(f"[{o[0]}]{o[1]}" for o in owner) or "(우리DB에 없음)"))
		if checked % 50 == 0:
			print(f"  ...{checked}/{len(stores)} (오매칭 {len(mismatches)}, 조회실패 {failed_lookup})")
		time.sleep(0.25)

	with OUT_CSV.open("w", newline="", encoding="utf-8-sig") as f:
		w = csv.writer(f)
		w.writerow(["store_id", "store_name", "store_address", "naver_place_id",
					"naver_actual_address", "rightful_owner_in_db"])
		for m in mismatches:
			w.writerow(m)

	print(f"\n=== 결과 ===")
	print(f"검증 {checked}, 조회실패 {failed_lookup}, 오매칭(도로명 불일치) = {len(mismatches)}")
	print(f"CSV: {OUT_CSV}")
	print("\n오매칭 샘플(상위 20):")
	for sid, name, addr, pid, nv, owner in mismatches[:20]:
		print(f"  [{sid}] {name} | DB:{addr[:25]}\n       placeId={pid} → 네이버:{nv[:30]} | 진짜주인: {owner}")
	cur.close()
	conn.close()


if __name__ == "__main__":
	main()
