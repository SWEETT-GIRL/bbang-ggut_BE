#!/usr/bin/env python3
"""observe.sh 가 적은 server.log 를 1분 버킷으로 요약한다 — 왜 깨졌는지의 원인 증거(락 대기 · Hikari pending/active · 초안 지표).

    python3 scripts/lab/draft-buffer/summarize-server-log.py results/<날짜>-<시나리오>/server.log [시작 HH:MM:SS] [끝 HH:MM:SS]

시각은 observe.sh 가 적은 그대로(서버 시계 — dev 는 UTC). 시작·끝을 주면 그 사이만 본다(k6 출력의 시작·끝 시각을 UTC 로 바꿔 넣는다).
"""
import csv
import sys
from collections import OrderedDict


def number(value):
	try:
		return float(value)
	except (TypeError, ValueError):
		return 0.0


def main():
	if len(sys.argv) < 2:
		print(__doc__)
		sys.exit(1)
	path = sys.argv[1]
	start = sys.argv[2] if len(sys.argv) > 2 else "00:00:00"
	end = sys.argv[3] if len(sys.argv) > 3 else "23:59:59"
	rows = [r for r in csv.DictReader(open(path, encoding="utf-8")) if r.get("time") and r["time"][0].isdigit()]
	buckets = OrderedDict()
	for row in rows:
		time = row["time"]
		if not (start <= time <= end):
			continue
		bucket = buckets.setdefault(time[:5], {"lock": [], "pending": [], "active": [], "backlog": [], "lag": []})
		bucket["lock"].append(number(row.get("lock_waits")))
		bucket["pending"].append(number(row.get("hikari_pending")))
		bucket["active"].append(number(row.get("hikari_active")))
		bucket["backlog"].append(number(row.get("draft_backlog")))
		bucket["lag"].append(number(row.get("draft_lag_p95")))
	print(f"{'minute':7} {'n':>4} {'lock max/avg':>14} {'hikari pend max/avg':>20} {'active max':>10} {'backlog max':>11} {'lag p95 max':>11}")
	for minute, b in buckets.items():
		n = len(b["lock"])
		print(f"{minute:7} {n:>4} {max(b['lock']):>6.0f}/{sum(b['lock']) / n:>7.1f} {max(b['pending']):>9.0f}/{sum(b['pending']) / n:>10.1f} "
			f"{max(b['active']):>10.0f} {max(b['backlog']):>11.0f} {max(b['lag']):>11.2f}")
	total = sum(len(b["lock"]) for b in buckets.values())
	print(f"samples: {total}")


if __name__ == "__main__":
	main()
