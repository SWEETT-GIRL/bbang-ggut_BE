#!/usr/bin/env python3
"""Region3rd enum에 존재하는 (region_1st, region_2nd, region_3rd) 조합을 DB에서 추출해
프론트엔드용 region_tree.json 생성.

실행:
  python3 scripts/generate_region_tree.py
  python3 scripts/generate_region_tree.py --out path/to/output.json

출력 형태:
[
  {
    "name": "서울",
    "districts": [
      {
        "name": "마포구",
        "dongs": ["공덕동", "합정동", ...]
      }
    ]
  }
]
"""

import argparse
import json
from collections import defaultdict
from pathlib import Path

import psycopg2

ENUM_FILE = Path(__file__).resolve().parent.parent \
    / "src/main/java/sweetgirl/croissant/store/enums/Region3rd.java"
DEFAULT_OUT = Path(__file__).resolve().parent.parent \
    / "scripts/region_tree.json"


def _load_properties() -> dict[str, str]:
    path = Path(__file__).resolve().parent.parent / "src/main/resources/application-local.properties"
    cfg: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, _, v = line.partition("=")
            cfg[k.strip()] = v.strip()
    return cfg


def db_config() -> dict:
    cfg = _load_properties()
    rest = cfg["spring.datasource.url"].split("//", 1)[1]
    hostport, _, dbname = rest.partition("/")
    host, _, port = hostport.partition(":")
    return {
        "host": host, "port": int(port or 5432), "dbname": dbname,
        "user": cfg["spring.datasource.username"],
        "password": cfg["spring.datasource.password"],
    }


def load_valid_enum_keys() -> set[str]:
    """Region3rd enum에서 유효한 키 집합 반환. 예: {'마포구_공덕동', ...}"""
    valid = set()
    for line in ENUM_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip().rstrip(",")
        if "_" in line and not line.startswith("//") and not line.startswith("package") \
                and not line.startswith("public"):
            valid.add(line)
    return valid


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(DEFAULT_OUT), help="출력 JSON 경로")
    args = parser.parse_args()

    valid_keys = load_valid_enum_keys()
    print(f"Region3rd enum 키 수: {len(valid_keys)}")

    conn = psycopg2.connect(**db_config(), connect_timeout=15)
    cur = conn.cursor()
    cur.execute("""
        SELECT DISTINCT region_1st, region_2nd, region_3rd
        FROM store
        WHERE region_1st IS NOT NULL
          AND region_2nd IS NOT NULL
          AND region_3rd IS NOT NULL
        ORDER BY region_1st, region_2nd, region_3rd
    """)
    rows = cur.fetchall()
    cur.close()
    conn.close()
    print(f"DB 조회 결과: {len(rows)}건")

    # region1st → region2nd → [region3rd] 트리 구성 (enum 존재 여부 필터링)
    tree: dict[str, dict[str, list[str]]] = defaultdict(lambda: defaultdict(list))
    skipped = 0
    for region1st, region2nd, region3rd in rows:
        enum_key = f"{region2nd}_{region3rd}"
        if enum_key not in valid_keys:
            skipped += 1
            continue
        tree[region1st][region2nd].append(region3rd)

    print(f"enum 미매칭으로 제외: {skipped}건")

    result = [
        {
            "name": r1,
            "districts": [
                {"name": r2, "dongs": dongs}
                for r2, dongs in sorted(districts.items())
            ]
        }
        for r1, districts in sorted(tree.items())
    ]

    out_path = Path(args.out)
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"저장 완료: {out_path}")
    total_dongs = sum(len(d["dongs"]) for r in result for d in r["districts"])
    print(f"시/도: {len(result)}개 / 구·군: {sum(len(r['districts']) for r in result)}개 / 동: {total_dongs}개")


if __name__ == "__main__":
    main()
