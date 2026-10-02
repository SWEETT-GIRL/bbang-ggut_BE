#!/usr/bin/env bash
# 측정 한 회차를 같은 잣대로 요약한다.  ./analyze.sh <회차이름>
#
# 회차마다 awk 를 새로 쓰면 잣대가 조금씩 달라져 비교가 흔들린다. 그래서 고정한다.
# 읽을 때 주의 — p99 는 "응답을 받은 것들"의 분포다. 아예 못 보낸 요청(dropped)은 거기 없으므로
# 둘을 같이 봐야 한다.
set -uo pipefail

LABEL="${1:?사용: analyze.sh <회차이름>}"
CSV="result-${LABEL}.csv"
MET="server-metrics-${LABEL}.log"
[ -f "$CSV" ] || { echo "$CSV 가 없습니다." >&2; exit 1; }

pct() { # stdin: 값들 → p50 p90 p95 p99 max min n
	sort -n | awk '{a[NR]=$1} END{
		if (NR==0) { print "  (표본 없음)"; exit }
		printf "  n=%-6d  p50 %7.0fms  p90 %7.0fms  p95 %7.0fms  p99 %7.0fms  최대 %7.0fms  최소 %5.0fms\n",
			NR, a[int(NR*0.5)+0], a[int(NR*0.9)+0], a[int(NR*0.95)+0], a[int(NR*0.99)+0], a[NR], a[1]}'
}

echo "════════ ${LABEL} ════════"

echo "── 시도 / 도달 ──"
awk -F, '$1=="dropped_iterations" {s+=$3} END{printf "  못 보냄(dropped): %d\n", s+0}' "$CSV"
awk -F, '$1=="http_reqs" && $12=="coupon_spike" {c++} END{printf "  쿠폰 도달      : %d\n", c+0}' "$CSV"

echo "── 상태코드 ──"
awk -F, '$1=="http_reqs" {print "  "$12" "$14}' "$CSV" | sort | uniq -c | sort -rn | head -8

echo "── 처리량 ──"
awk -F, '$1=="http_reqs" && $12=="coupon_spike" && $14!="0" {
		c++; t=$2; if(mn==""||t<mn)mn=t; if(t>mx)mx=t}
	END{d=mx-mn+1; printf "  평균 %.0f건/s  (%d건 / %ds)\n", c/d, c, d}' "$CSV"

T0=$(awk -F, '$1=="http_reqs" && $12=="coupon_spike" {print $2; exit}' "$CSV")
echo "── 5초 구간별 처리량 ──"
awk -F, -v t0="$T0" '$1=="http_reqs" && $12=="coupon_spike" && $14!="0" {
		b=int(($2-t0)/5); c[b]++; if(b>mx)mx=b}
	END{for(i=0;i<=mx;i++) printf "  %2d~%2ds : %5.0f건/s\n", i*5, i*5+4, c[i]/5}' "$CSV"

echo "── 발급 성공(201) 지연 ──"
awk -F, '$1=="http_req_duration" && $14=="201" {print $3}' "$CSV" | pct
echo "── 거절(409) 지연 ──"
awk -F, '$1=="http_req_duration" && $14=="409" {print $3}' "$CSV" | pct
echo "── 배경 검색(200) 지연 ──"
awk -F, '$1=="http_req_duration" && $12=="background_read" && $14=="200" {print $3}' "$CSV" | pct
awk -F, '$1=="http_req_duration" && $12=="background_read" && $14=="200" && $3<1000 {c++}
	END{printf "  1초 이내 응답: %d건\n", c+0}' "$CSV"

if [ -f "$MET" ]; then
	echo "── 서버측 처리시간 / 요청 (앱이 스스로 잰 값) ──"
	grep 'coupons/{couponId}/issue' "$MET" 2>/dev/null | awk '{
		split($2,a,"{"); m=a[1]; st=$2; sub(/.*status="/,"",st); sub(/".*/,"",st); k=m"|"st
		if (!(k in first)) first[k]=$3; last[k]=$3
	} END{
		for (k in last) { split(k,p,"|")
			if (p[1]=="http_server_requests_seconds_count") C[p[2]]=last[k]-first[k]
			if (p[1]=="http_server_requests_seconds_sum")   S[p[2]]=last[k]-first[k] }
		for (s in C) if (C[s]>0) printf "  status %s: %6d건, 요청당 %8.2fms\n", s, C[s], S[s]/C[s]*1000
		if (length(C)==0) print "  (컨트롤러가 처리한 건이 없음 — 필터가 다 잡았다는 뜻일 수 있다)"
	}'
	echo "── 톰캣 / 커넥션 풀 최대값 ──"
	grep -E '^[0-9]+ (tomcat_threads_busy|hikaricp_connections_(active|idle|pending))' "$MET" 2>/dev/null | awk '{
		split($2,a,"{"); m=a[1]; if ($3+0>mx[m]) mx[m]=$3+0
	} END{for (m in mx) printf "  %-38s %6.0f\n", m, mx[m]}' | sort
	echo "── 필터 거절 카운터 (신규) ──"
	grep -E '^[0-9]+ coupon_issue_rejected_total' "$MET" 2>/dev/null | awk '{
		if (first=="") first=$3; last=$3} END{
		if (first=="") print "  (스크랩에 없음 — run-remote.sh 의 grep 패턴에 추가 필요)"
		else printf "  필터가 거절: %.0f건\n", last-first}'
fi
