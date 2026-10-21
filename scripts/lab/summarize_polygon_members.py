#!/usr/bin/env python3
"""실제 프로필 실험을 집계만 공개한다. 회원 ID·개별 취향·가게 경로는 출력하지 않는다."""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import statistics
import subprocess


def average(items):
    return statistics.mean(items) if items else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--previous', type=Path, help='동일 결과를 낸 최적화 전 실행 원본')
    args = parser.parse_args()
    data = json.loads(args.input.read_text())
    rows = data['rows']
    key = lambda row: (row['city'], row['persona'], row['context'], row['requested'])
    by_arm = {arm: {key(row): row for row in rows if row['arm']==arm}
              for arm in ['CONTROL','D_TIGHT','D_HYBRID']}
    aggregates=[]
    for context in ['FRESH','ADDED_STORE','RETRY_FROZEN']:
        for count in [4,6]:
            for arm in by_arm:
                group=[row for row in rows if row['context']==context and row['requested']==count and row['arm']==arm]
                times=sorted(row['algorithmMs'] for row in group if row['algorithmMs'] is not None)
                aggregates.append(dict(context=context, requested=count, arm=arm, cases=len(group),
                    complete=sum(row['size']==count for row in group),
                    meanScore=average([row['commonMeanScore'] for row in group]),
                    meanCosineWithAxis=average([row['commonMeanTaste'] for row in group if row['hasAxis']]),
                    meanDistanceWithApproachM=average([row['distanceWithApproachM'] for row in group]),
                    maxDistanceWithApproachM=max(row['distanceWithApproachM'] for row in group),
                    totalBudgetViolations=sum(row['distanceWithApproachM']>500*(count-1) for row in group),
                    approachHopViolations=sum(row['approachM']>1500 for row in group),
                    uniqueSelectedStores=len({sid for row in group for sid in row['storeIds']}),
                    outsideOldArea=sum(row['outsideOldArea'] for row in group),
                    fallbackCases=sum(row['fallback'] for row in group),
                    algorithmP95Ms=times[math.ceil(len(times)*.95)-1] if times else None))
    pairs=[]
    for context in ['FRESH','ADDED_STORE','RETRY_FROZEN']:
        for count in [4,6]:
            for control_arm in ['CONTROL','D_TIGHT']:
                values=[(row,by_arm[control_arm][key(row)]) for row in rows
                        if row['arm']=='D_HYBRID' and row['context']==context and row['requested']==count]
                complete=[(new,old) for new,old in values if new['size']==count and old['size']==count]
                deltas=[new['commonMeanScore']-old['commonMeanScore'] for new,old in complete]
                pairs.append(dict(context=context,requested=count,baseline=control_arm,cases=len(values),
                    completePairs=len(complete),improved=sum(d>1e-9 for d in deltas),
                    worsened=sum(d < -1e-9 for d in deltas),same=sum(abs(d)<=1e-9 for d in deltas),
                    meanScoreDelta=average(deltas),
                    controlFits=sum(new['controlFits'] for new,old in values)))
    root=Path(__file__).resolve().parents[2]
    sources=['core/src/test/java/sweetgirl/croissant/recommendation/PolygonCourseComparisonLabTest.java',
             'core/src/test/java/sweetgirl/croissant/recommendation/PolygonMemberCourseLabTest.java',
             'scripts/lab/summarize_polygon_members.py']
    result=dict(gitBase=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
                inputSha256=hashlib.sha256(args.input.read_bytes()).hexdigest(),
                sourceHashes={s:hashlib.sha256((root/s).read_bytes()).hexdigest() for s in sources},
                boundarySourceId=data['input']['sourceId'],storeLatestUpdate=data['input']['storeLatestUpdate'],
                cohortCounts=dict(Counter(member['stratum'] for member in data['members'])),
                settings=data['settings'],aggregates=aggregates,pairedResults=pairs,
                casesWithRecordedStoreExclusion=sum(row['recordedInCity']>0 for row in rows if row['arm']=='CONTROL'),
                limitations=data['limitations'])
    if args.previous:
        previous=json.loads(args.previous.read_text())
        without_time=lambda report: [{k:v for k,v in row.items() if k!='algorithmMs'} for row in report['rows']]
        if without_time(previous)!=without_time(data):
            raise ValueError('Optimization changed a non-timing result; inspect before reporting a speed-only change')
        timing=[]
        for count in [4,6]:
            for report,label in [(previous,'before'),(data,'after')]:
                values=sorted(row['algorithmMs'] for row in report['rows'] if row['arm']=='D_HYBRID'
                              and row['context']=='FRESH' and row['requested']==count)
                timing.append(dict(run=label,requested=count,p95Ms=values[math.ceil(len(values)*.95)-1]))
        result['optimization']={'beforeSha256':hashlib.sha256(args.previous.read_bytes()).hexdigest(),
                                'allNonTimingRowsEqual':True,'rowsCompared':len(rows),'freshHybridTiming':timing}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'cohortCounts':result['cohortCounts'],'aggregates':aggregates,'pairedResults':pairs},ensure_ascii=False,indent=2))


if __name__=='__main__':
    main()
