#!/usr/bin/env python3
"""Aggregate the synthetic Java lab output; never publish store-level routes or member data."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics
import subprocess


def percentile(values, fraction):
    values = sorted(values)
    return values[max(0, math.ceil(len(values)*fraction)-1)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(args.input.read_text())
    rows = data['rows']
    aggregates = []
    for city in ['ALL', '수원시', '성남시', '고양시']:
        for requested in [1, 4, 6]:
            for arm in ['A', 'CITY_TOP15', 'D', 'D_TIGHT']:
                subset = [r for r in rows if r['arm']==arm and r['requested']==requested
                          and (city=='ALL' or r['city']==city)]
                if not subset:
                    continue
                personalized = [r for r in subset if r['persona']!='NO_TASTE']
                aggregates.append(dict(city=city, requested=requested, arm=arm, cases=len(subset),
                    complete=sum(r['size']==requested for r in subset),
                    meanSize=statistics.mean(r['size'] for r in subset),
                    meanCommonScore=statistics.mean(r['commonMeanScore'] for r in subset),
                    meanPersonalizedCosine=statistics.mean(r['commonMeanTaste'] for r in personalized),
                    personalizedCases=len(personalized),
                    meanDistanceM=statistics.mean(r['totalDistanceM'] for r in subset),
                    p95DistanceM=percentile([r['totalDistanceM'] for r in subset], .95),
                    maxDistanceM=max(r['totalDistanceM'] for r in subset),
                    maxHopM=max(r['maxHopM'] for r in subset),
                    maxDiameterM=max(r['diameterM'] for r in subset),
                    outsideOldAreaSelections=sum(r['outsideOldArea'] for r in subset),
                    totalSelections=sum(r['size'] for r in subset),
                    distinctSelectedStores=len({sid for r in subset for sid in r['storeIds']}),
                    algorithmP50Ms=percentile([r['algorithmMs'] for r in subset],.5),
                    algorithmP95Ms=percentile([r['algorithmMs'] for r in subset],.95),
                    fallbackCases=sum(r['fallback'] for r in subset)))
    key = lambda r: (r['city'],r['persona'],r['exclude20pct'],r['requested'])
    baseline={key(r):r for r in rows if r['arm']=='A'}
    paired=[]
    for arm in ['CITY_TOP15','D','D_TIGHT']:
        for count in [4,6]:
            both=[(r,baseline[key(r)]) for r in rows if r['arm']==arm and r['requested']==count
                  and r['size']==count and baseline[key(r)]['size']==count]
            deltas=[r['commonMeanScore']-a['commonMeanScore'] for r,a in both]
            paired.append(dict(arm=arm, requested=count, completePairs=len(both),
                improved=sum(d>1e-9 for d in deltas), worsened=sum(d < -1e-9 for d in deltas),
                same=sum(abs(d)<=1e-9 for d in deltas), minScoreDelta=min(deltas),
                meanScoreDelta=statistics.mean(deltas)))
    root=Path(__file__).resolve().parents[2]
    sources=['src/test/java/sweetgirl/croissant/recommendation/PolygonCourseComparisonLabTest.java',
             'src/main/java/sweetgirl/croissant/recommendation/repository/RecommendationCandidateJdbcRepository.java',
             'src/main/java/sweetgirl/croissant/recommendation/service/CourseRecommendationCommandService.java',
             'src/main/java/sweetgirl/croissant/recommendation/config/RecommendationProperties.java',
             'scripts/lab/course_polygon_input.py', 'scripts/lab/summarize_polygon_courses.py']
    result={'gitBase':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
            'inputSha256':hashlib.sha256(args.input.read_bytes()).hexdigest(),
            'sourceHashes':{s:hashlib.sha256((root/s).read_bytes()).hexdigest() for s in sources},
            'boundarySourceId':data['input']['sourceId'],
            'storeLatestUpdate':data['input']['storeLatestUpdate'],
            'profileLatestUpdate':data['input']['profileLatestUpdate'],
            'scopeStats':data['scopeStats'], 'settings':data['settings'],
            'personas':data['personas'], 'aggregates':aggregates,'pairedCompleteRoutes':paired,
            'limitations':data['limitations']+[
                'D_TIGHT budget = 500m * (requested stores - 1): 1,500m for 4, 2,500m for 6; experimental, not adopted policy.',
                'Scores are the common original-persona score even when selection uses neutral fallback.',
                'All algorithms see the same snapshot and deterministic exclusions. These are not production A/B results.']}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    for r in aggregates:
        if r['city']=='ALL' and r['requested'] in [4,6]:
            print(f"{r['requested']} {r['arm']}: {r['complete']}/{r['cases']} complete, cosine {r['meanPersonalizedCosine']:.3f}, distance {r['meanDistanceM']:.0f}m (max {r['maxDistanceM']}), algorithm p95 {r['algorithmP95Ms']:.2f}ms")
    print(json.dumps(paired,ensure_ascii=False,indent=2))


if __name__=='__main__':
    main()
