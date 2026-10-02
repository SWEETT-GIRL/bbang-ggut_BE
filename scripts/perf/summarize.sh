#!/usr/bin/env bash
# k6 CSV 를 초 단위 · 결과별로 접는다.
#
#   scripts/perf/summarize.sh scripts/perf/result-R1.csv [server-metrics.log]
#
# "몇 초에 몇 명이 어떤 식으로 실패했는가" — 이 표가 측정의 결론이다.
set -euo pipefail

CSV="${1:?사용: summarize.sh <k6 result.csv> [server-metrics.log]}"
METRICS="${2:-}"

echo "=== 초별 · 결과별 건수 (coupon_outcome) ==="
# k6 CSV: metric_name,timestamp,metric_value,...,extra_tags 의 extra_tags 에 outcome 이 들어간다.
awk -F, 'NR==1{for(i=1;i<=NF;i++){if($i=="metric_name")M=i; if($i=="timestamp")T=i; if($i=="extra_tags")E=i} next}
	$M=="coupon_outcome"{
		split($E,kv,"&"); o="?";
		for(k in kv){ if(kv[k] ~ /^outcome=/){ sub(/^outcome=/,"",kv[k]); o=kv[k] } }
		if(!base) base=$T; sec=$T-base; c[sec"|"o]++; secs[sec]=1; outs[o]=1
	}
	END{
		printf "%-5s", "초";
		n=0; for(o in outs){ order[n++]=o }
		for(i=0;i<n;i++) printf "%16s", order[i];
		printf "\n";
		for(s=0;s<=100;s++) if(secs[s]){
			printf "%-5s", s"s";
			for(i=0;i<n;i++) printf "%16d", c[s"|"order[i]]+0;
			printf "\n";
		}
	}' "$CSV"

echo ""
echo "=== 합계 ==="
awk -F, 'NR==1{for(i=1;i<=NF;i++){if($i=="metric_name")M=i; if($i=="extra_tags")E=i} next}
	$M=="coupon_outcome"{
		split($E,kv,"&"); o="?";
		for(k in kv){ if(kv[k] ~ /^outcome=/){ sub(/^outcome=/,"",kv[k]); o=kv[k] } }
		t[o]++; total++
	}
	END{ for(o in t) printf "  %-24s %8d  (%.1f%%)\n", o, t[o], t[o]*100/total; printf "  %-24s %8d\n","합계",total }' "$CSV"

if [ -n "$METRICS" ] && [ -f "$METRICS" ]; then
	echo ""
	echo "=== 서버 지표 최대값 (초별 최대) ==="
	awk '{ for(i=2;i<=NF-1;i++){} name=$2; val=$NF+0; if(val>max[name]) max[name]=val }
		END{ for(n in max) printf "  %-44s %10.0f\n", n, max[n] }' "$METRICS" | sort
fi
