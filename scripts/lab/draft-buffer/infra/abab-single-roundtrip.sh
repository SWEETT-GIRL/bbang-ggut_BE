#!/usr/bin/env bash
# 기준(api-all.jar) 과 왕복 1회화(api-all-1rt.jar) 를 번갈아 3번씩 — 회차 간 편차를 보고 평균으로 판정한다.
S=${DRAFT_LAB_WORK:?}
ST="20s:1280,60s:1280,20s:2500,60s:2500"
for i in 1 2 3; do
  STAGES=$ST JAR=api-all.jar LABEL=ab-base-$i bash "$(dirname "$0")/fanout-dist.sh" > $S/ab-base-$i.log 2>&1
  STAGES=$ST JAR=api-all-1rt.jar LABEL=ab-1rt-$i bash "$(dirname "$0")/fanout-dist.sh" > $S/ab-1rt-$i.log 2>&1
done
echo ABABDONE
