#!/usr/bin/env python3
"""델타 diff — 재수집분(delta/{ds}_pull.jsonl)을 기준선(localdata_raw/{ds}.jsonl)과 비교.

산출:
  delta/{ds}_new.jsonl  기준선에 없는 관리번호 (신규 인허가) → localdata_new_delta.py 입력
  delta/{ds}_upd.jsonl  기준선과 영업 상태가 달라진 관리번호 → localdata_status_delta.py 입력
그리고 --merge-baseline 시 기준선에 델타를 병합(신규 append + 상태 변경 레코드 교체)해
다음 달 diff 의 기준을 갱신한다.

--no-baseline: 기준선이 없을 때(다른 PC 에 있던 원본이 이 기계에 없음, 09-20) 방금 받은 전량({ds}.jsonl)만으로 같은 두 파일을 만든다.
  신규 = 인허가일이 --since 이후 (관리번호가 이미 아는 것인지는 localdata_new_delta 가 거른다)
  상태변경 = 지금 폐업인 레코드 전부 (우리가 아는 가게인지·운영이 ACTIVE 인지는 localdata_status_delta 가 거른다)
  전환 시점을 모르므로 "이번 달에 바뀐 것"이 아니라 "지금 폐업인 것"이다 — 다음 달부터는 이 원본이 기준선이 되어 보통 diff 로 돌아간다.

사용: python scripts/localdata_delta_diff.py [--merge-baseline]
      python scripts/localdata_delta_diff.py --no-baseline --since 20260801
"""
import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from localdata_match import DATASET_LABEL

ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "matching" / "localdata_raw"
DELTA_DIR = RAW_DIR / "delta"


def ymd(value):
    """인허가일은 '2026-06-25' 로 온다 — 숫자만 남겨 YYYYMMDD 로 비교한다"""
    return re.sub(r"\D", "", value or "")[:8]


def status_of(r):
    return (r.get("DTL_SALS_STTS_NM") or r.get("SALS_STTS_NM") or "").strip()


def run_without_baseline(since: str, datasets=None) -> int:
    DELTA_DIR.mkdir(parents=True, exist_ok=True)
    for ds in (datasets or DATASET_LABEL):
        (DELTA_DIR / f"{ds}_new.jsonl").write_text("", encoding="utf-8")   # 이번에 안 보는 업종의 옛 파일이 섞이지 않게
        (DELTA_DIR / f"{ds}_upd.jsonl").write_text("", encoding="utf-8")
        base_path = RAW_DIR / f"{ds}.jsonl"
        if not base_path.exists():
            print(f"[{ds}] 원본 없음 — 건너뜀")
            continue
        new_rows, closed_rows, seen = [], [], set()
        for line in open(base_path, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            m = r.get("MNG_NO") or ""
            if not m or m in seen:
                continue
            seen.add(m)
            if "폐업" in status_of(r):
                closed_rows.append(line)
            elif ymd(r.get("LCPMT_YMD")) >= ymd(since):
                new_rows.append(line)
        (DELTA_DIR / f"{ds}_new.jsonl").write_text("\n".join(new_rows) + ("\n" if new_rows else ""), encoding="utf-8")
        (DELTA_DIR / f"{ds}_upd.jsonl").write_text("\n".join(closed_rows) + ("\n" if closed_rows else ""), encoding="utf-8")
        print(f"[{ds}] 원본 {len(seen):,} → 신규 후보(인허가 {since} 이후 영업중) {len(new_rows):,} / 폐업 {len(closed_rows):,}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--merge-baseline", action="store_true")
    parser.add_argument("--no-baseline", action="store_true", help="기준선 없이 방금 받은 전량으로 신규·폐업을 뽑는다")
    parser.add_argument("--since", default="", help="--no-baseline 의 신규 인허가일 하한 (YYYYMMDD)")
    parser.add_argument("--datasets", nargs="+", default=list(DATASET_LABEL),
                        help="--no-baseline 에서 볼 업종 (기본 전부). 예: --datasets bakeries")
    args = parser.parse_args()

    if args.no_baseline:
        return run_without_baseline(args.since, args.datasets)

    for ds in DATASET_LABEL:
        pull_path = DELTA_DIR / f"{ds}_pull.jsonl"
        base_path = RAW_DIR / f"{ds}.jsonl"
        if not pull_path.exists() or not base_path.exists():
            print(f"[{ds}] pull/기준선 없음 — 건너뜀")
            continue
        baseline = {}
        base_max_ymd = ""
        for line in open(base_path, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            m = r.get("MNG_NO") or ""
            if m:
                baseline[m] = status_of(r)
            d = (r.get("LCPMT_YMD") or "").strip()
            if d > base_max_ymd:
                base_max_ymd = d
        new_rows, upd_rows, pull_lines = [], [], []
        seen = set()
        candidates_new = []
        for line in open(pull_path, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            m = r.get("MNG_NO") or ""
            if not m or m in seen:
                continue
            seen.add(m)
            pull_lines.append(line)
            if m not in baseline:
                candidates_new.append((line, (r.get("LCPMT_YMD") or "").strip()))
            elif status_of(r) != baseline[m]:
                upd_rows.append(line)
        # 기준선이 부분 수집(키워드 등)인데 pull 이 전량이면 "기준선에 없음"은 신규가
        # 아니라 수집 범위 밖이다 — 인허가일이 기준선 스냅샷 이후인 것만 신규로 인정.
        partial = len(baseline) < 0.5 * len(seen)
        if partial:
            new_rows = [l for l, d in candidates_new if d > base_max_ymd]
            print(f"[{ds}] 부분 기준선 감지 ({len(baseline):,} vs pull {len(seen):,}) — "
                  f"인허가일 > {base_max_ymd} 만 신규 인정, 범위 밖 {len(candidates_new) - len(new_rows):,} 제외")
        else:
            new_rows = [l for l, _ in candidates_new]
        (DELTA_DIR / f"{ds}_new.jsonl").write_text("\n".join(new_rows) + ("\n" if new_rows else ""), encoding="utf-8")
        (DELTA_DIR / f"{ds}_upd.jsonl").write_text("\n".join(upd_rows) + ("\n" if upd_rows else ""), encoding="utf-8")
        print(f"[{ds}] pull {len(seen):,} → 신규 {len(new_rows):,} / 상태변경 {len(upd_rows):,}")

        if args.merge_baseline:
            # pull 이 해당 유니버스(전량 또는 키워드)의 새 진실 — 통째 치환이 부분/전량
            # 어느 기준선에서든 올바르고, 다음 달 diff 의 기준을 깨끗하게 만든다.
            base_path.write_text("\n".join(pull_lines) + "\n", encoding="utf-8")
            print(f"[{ds}] 기준선 치환 완료 (총 {len(pull_lines):,}행)")
    return 0


if __name__ == "__main__":
    main()
