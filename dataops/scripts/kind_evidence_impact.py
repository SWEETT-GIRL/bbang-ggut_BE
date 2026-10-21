"""Compare frozen JSON artifacts offline; no env, DB, or API access.

Required input contract (schema_version=1), in BOTH artifacts::

    {"schema_version": 1, "artifact_id": "baseline-build-id",
     "scope": {"cohort_id": "fixed-cohort", "store_ids": [1],
               "axis_dimensions": ["taste", "texture", "filling", "vibe"],
               "label_dimensions": ["sweet", "crispy"]},
     "stores": [{"store_id": 1,
                 "profile": {"kinds": ["식빵"], "signature_kinds": ["식빵"],
                             "axes": {"taste": 0, "texture": 0, "filling": 0, "vibe": 0},
                             "type": "PCLN", "type_label": null, "bakery_score": 0.5},
                 "labels": {"sweet": 0.1, "crispy": 0.2},
                 "serving_outcome": "READY", "publish_selected": false}],
     "proposals": [{"proposal_id": 10, "store_id": 1, "kind": "식빵"}],
     "recommendations": {
         "request_ids": ["request-1"], "top_k": 1,
         "requests": [{"request_id": "request-1", "input_fingerprint": "fixed-request-input",
                       "ranked_store_ids": [1], "badges": {"1": ["식빵 취향"]},
                       "reasons": {"1": "식빵을 좋아하는 취향에 맞아요."}}]}}

proposals/recommendations are optional: absent means NOT_MEASURED, not zero.
badges/reasons are independently optional; if supplied, their keys must exactly
cover ranked_store_ids. Both artifacts must have the same store cohort/dimensions
and, when paired recommendations exist, the same request cohort, top_k, and
input_fingerprint per request. Rankings may be shorter than top_k.
Every declared vector dimension must be present; unavailable scalars are explicit
null. labels=null means no feature row. READY requires labels, WITHOUT_LABELS
requires labels=null. NEEDS_REVIEW cannot be selected for publishing. Selected
WITHOUT_LABELS means an explicitly reviewed invalidation, not authorization by
this tool. All profile values are proposed values, not inferred writes.

The report separates whole-cohort deltas from selected-for-publish deltas. Blocked
and unselected stores preserve baseline in the planned-publish view. Publishing is
never claimed. bakery_score >= 0.5 is one explicit gate, not total recommendation
impact. Recommendation text changes are exact comparisons, not quality ratings.

Usage: python scripts/kind_evidence_impact.py --baseline before.json --candidate after.json
Default output is JSON on stdout only. --output PATH additionally creates a NEW
report file exclusively (no overwrite). Invalid input: stderr JSON, exit code 2.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import sys


class ImpactInputError(ValueError):
    """Incomplete, ambiguous, or unmatched input artifacts."""


def _fail(message):
    raise ImpactInputError(message)


def _object(value, required, optional, path):
    if not isinstance(value, dict):
        _fail(f"{path}: object required")
    required = set(required)
    missing, extra = required - value.keys(), value.keys() - required - set(optional)
    if missing or extra:
        _fail(f"{path}: missing={sorted(missing)}, unknown={sorted(extra)}")


def _text(value, path):
    if not isinstance(value, str) or not value.strip():
        _fail(f"{path}: nonblank string required")
    return value


def _id(value, path):
    if type(value) is not int or value <= 0:
        _fail(f"{path}: positive integer required")
    return value


def _unique_list(value, validate, path, *, nonempty=False):
    if not isinstance(value, list) or (nonempty and not value):
        _fail(f"{path}: {'nonempty ' if nonempty else ''}array required")
    result = [validate(item, f"{path}[{index}]") for index, item in enumerate(value)]
    if len(result) != len(set(result)):
        _fail(f"{path}: duplicate values")
    return result


def _number(value, path):
    if value is not None and (type(value) not in (int, float) or not math.isfinite(value)):
        _fail(f"{path}: finite number or explicit null required")
    return value


def _vector(value, dimensions, path):
    _object(value, dimensions, (), path)
    for dimension in dimensions:
        _number(value[dimension], f"{path}.{dimension}")


def _proposal_id(value, path):
    return str(_id(value, path)) if type(value) is int else _text(value, path)


def _recommendations(value, store_ids, path):
    _object(value, ("request_ids", "top_k", "requests"), (), path)
    ids = _unique_list(value["request_ids"], _text, path + ".request_ids", nonempty=True)
    _id(value["top_k"], path + ".top_k")
    if not isinstance(value["requests"], list):
        _fail(path + ".requests: array required")
    requests = {}
    for row in value["requests"]:
        _object(row, ("request_id", "input_fingerprint", "ranked_store_ids"), ("badges", "reasons"), path)
        rid = _text(row["request_id"], path + ".request_id")
        if rid not in ids or rid in requests:
            _fail(f"{path}: out-of-scope or duplicate request_id {rid}")
        _text(row["input_fingerprint"], path + ".input_fingerprint")
        ranking = _unique_list(row["ranked_store_ids"], _id, path + ".ranked_store_ids")
        if not set(ranking).issubset(store_ids):
            _fail(f"{path}: ranked stores outside declared cohort")
        for field in ("badges", "reasons"):
            if field not in row:
                continue
            _object(row[field], (str(sid) for sid in ranking), (), path + "." + field)
            for sid, content in row[field].items():
                if field == "badges":
                    _unique_list(content, _text, path + ".badges." + sid)
                elif not isinstance(content, str):
                    _fail(f"{path}.reasons.{sid}: string required (empty means no sentence)")
        requests[rid] = row
    if set(requests) != set(ids):
        _fail(path + ": requests must cover exactly request_ids")
    return requests


def validate_artifact(value, name):
    _object(value, ("schema_version", "artifact_id", "scope", "stores"),
            ("proposals", "recommendations"), name)
    if type(value["schema_version"]) is not int or value["schema_version"] != 1:
        _fail(name + ": unsupported schema_version")
    _text(value["artifact_id"], name + ".artifact_id")
    scope = value["scope"]
    _object(scope, ("cohort_id", "store_ids", "axis_dimensions", "label_dimensions"), (), name + ".scope")
    _text(scope["cohort_id"], name + ".scope.cohort_id")
    ids = set(_unique_list(scope["store_ids"], _id, name + ".scope.store_ids", nonempty=True))
    for field in ("axis_dimensions", "label_dimensions"):
        _unique_list(scope[field], _text, name + ".scope." + field, nonempty=True)
    if not isinstance(value["stores"], list):
        _fail(name + ".stores: array required")
    stores = {}
    for row in value["stores"]:
        _object(row, ("store_id", "profile", "labels", "serving_outcome", "publish_selected"), (), name + ".stores")
        sid = _id(row["store_id"], name + ".store_id")
        if sid not in ids or sid in stores:
            _fail(f"{name}: out-of-scope or duplicate store_id {sid}")
        profile = row["profile"]
        _object(profile, ("kinds", "signature_kinds", "axes", "type", "type_label", "bakery_score"), (), name + ".profile")
        for field in ("kinds", "signature_kinds"):
            _unique_list(profile[field], _text, name + ".profile." + field)
        _vector(profile["axes"], scope["axis_dimensions"], name + ".profile.axes")
        for field in ("type", "type_label"):
            if profile[field] is not None:
                _text(profile[field], name + ".profile." + field)
        score = _number(profile["bakery_score"], name + ".profile.bakery_score")
        if score is not None and not 0 <= score <= 1:
            _fail(name + ": bakery_score must be between 0 and 1")
        if row["labels"] is not None:
            _vector(row["labels"], scope["label_dimensions"], name + ".labels")
        outcome = row["serving_outcome"]
        if outcome not in ("READY", "NEEDS_REVIEW", "WITHOUT_LABELS"):
            _fail(name + ": unsupported serving_outcome")
        if type(row["publish_selected"]) is not bool:
            _fail(name + ": publish_selected must be boolean")
        if outcome == "NEEDS_REVIEW" and row["publish_selected"]:
            _fail(f"{name}: blocked store {sid} cannot be selected for publishing")
        if outcome == "READY" and row["labels"] is None:
            _fail(f"{name}: READY store {sid} needs explicit labels")
        if outcome == "WITHOUT_LABELS" and row["labels"] is not None:
            _fail(f"{name}: WITHOUT_LABELS store {sid} must have labels=null")
        stores[sid] = row
    if set(stores) != ids:
        _fail(name + ": stores must cover exactly scope.store_ids")
    if "proposals" in value:
        if not isinstance(value["proposals"], list):
            _fail(name + ".proposals: array required")
        proposal_ids = set()
        for row in value["proposals"]:
            _object(row, ("proposal_id", "store_id", "kind"), (), name + ".proposals")
            pid = _proposal_id(row["proposal_id"], name + ".proposal_id")
            if pid in proposal_ids:
                _fail(name + ": duplicate proposal_id " + pid)
            proposal_ids.add(pid)
            if _id(row["store_id"], name + ".proposal.store_id") not in ids:
                _fail(name + ": proposal store outside declared cohort")
            _text(row["kind"], name + ".proposal.kind")
    requests = _recommendations(value["recommendations"], ids, name + ".recommendations") if "recommendations" in value else None
    return stores, requests


def _unmeasured(reason):
    return {"status": "NOT_MEASURED", "reason": reason}


def _delta(before, after):
    return {"before": before, "after": after}


def _store_delta(before, after):
    changed = {}
    for field in ("kinds", "signature_kinds", "type", "type_label", "bakery_score"):
        old, new = before["profile"][field], after["profile"][field]
        if old != new:
            changed[field] = _delta(old, new)
            if field in ("kinds", "signature_kinds"):
                changed[field].update(added=sorted(set(new) - set(old)), removed=sorted(set(old) - set(new)),
                                      reordered=set(old) == set(new))
            elif field == "bakery_score":
                changed[field]["numeric_delta"] = new - old if old is not None and new is not None else None
    for field in ("axes", "labels"):
        old = before["profile"][field] if field == "axes" else before[field]
        new = after["profile"][field] if field == "axes" else after[field]
        if old != new:
            changed[field] = _delta(old, new)
            if old is not None and new is not None:
                changed[field]["dimension_changes"] = {
                    key: {**_delta(old[key], new[key]),
                          "numeric_delta": new[key] - old[key] if old[key] is not None and new[key] is not None else None}
                    for key in old if old[key] != new[key]}
    if before["serving_outcome"] != after["serving_outcome"]:
        changed["serving_outcome"] = _delta(before["serving_outcome"], after["serving_outcome"])
    return changed


def _store_summary(ids, before, after, deltas):
    fields = ("kinds", "signature_kinds", "axes", "type", "type_label", "labels", "bakery_score", "serving_outcome")
    pairs = set()
    for sid in ids:
        for field in ("kinds", "signature_kinds"):
            pairs.update((sid, kind) for kind in set(before[sid]["profile"][field]) ^ set(after[sid]["profile"][field]))
    crossings = {"entered_store_ids": [], "exited_store_ids": [], "not_comparable_store_ids": []}
    for sid in ids:
        old, new = before[sid]["profile"]["bakery_score"], after[sid]["profile"]["bakery_score"]
        if old is None or new is None:
            crossings["not_comparable_store_ids"].append(sid)
        elif old < 0.5 <= new:
            crossings["entered_store_ids"].append(sid)
        elif new < 0.5 <= old:
            crossings["exited_store_ids"].append(sid)
    return {"store_count": len(ids), "changed_store_count": sum(bool(deltas[sid]) for sid in ids),
            "unique_changed_store_kind_pairs": len(pairs),
            "changed_store_kind_pairs": [{"store_id": sid, "kind": kind} for sid, kind in sorted(pairs)],
            "field_changed_store_counts": {field: sum(field in deltas[sid] for sid in ids) for field in fields},
            "bakery_score_gate": {"rule": "bakery_score >= 0.5", **crossings}}


def _proposal_summary(value):
    if "proposals" not in value:
        return _unmeasured("proposals not supplied")
    rows = value["proposals"]
    return {"status": "MEASURED", "proposal_count": len(rows),
            "unique_store_count": len({row["store_id"] for row in rows}),
            "unique_store_kind_pairs": len({(row["store_id"], row["kind"]) for row in rows})}


def _content_delta(field, before, after, old_top, new_top):
    if field not in before or field not in after:
        return _unmeasured(f"paired {field} not supplied")
    common = sorted(set(old_top) & set(new_top))
    if not common:
        return {**_unmeasured(f"no common top-k stores for paired {field} comparison"),
                "entered_store_ids": sorted(set(new_top) - set(old_top)),
                "exited_store_ids": sorted(set(old_top) - set(new_top))}
    changes = [{"store_id": sid, **_delta(before[field][str(sid)], after[field][str(sid)])}
               for sid in common if before[field][str(sid)] != after[field][str(sid)]]
    return {"status": "MEASURED", "compared_common_store_count": len(common), "changes": changes,
            "entered_store_ids": sorted(set(new_top) - set(old_top)),
            "exited_store_ids": sorted(set(old_top) - set(new_top))}


def _recommendation_report(baseline, candidate, before, after):
    if before is None or after is None:
        return _unmeasured("paired recommendation artifacts not supplied; profile deltas cannot infer ranking, badges, or sentences")
    old_config, new_config = baseline["recommendations"], candidate["recommendations"]
    if set(before) != set(after) or old_config["top_k"] != new_config["top_k"]:
        _fail("recommendation request cohorts and top_k must match")
    k, rows = old_config["top_k"], []
    for rid in sorted(before):
        old, new = before[rid], after[rid]
        if old["input_fingerprint"] != new["input_fingerprint"]:
            _fail(f"recommendation {rid}: input_fingerprint mismatch")
        old_top, new_top = old["ranked_store_ids"][:k], new["ranked_store_ids"][:k]
        old_ranks = {sid: i + 1 for i, sid in enumerate(old_top)}
        new_ranks = {sid: i + 1 for i, sid in enumerate(new_top)}
        entered, exited = sorted(set(new_top) - set(old_top)), sorted(set(old_top) - set(new_top))
        rows.append({"request_id": rid, "top_k": {"before": old_top, "after": new_top,
                     "entered_store_ids": entered, "exited_store_ids": exited,
                     "baseline_slot_replacement_rate": len(exited) / len(old_top) if old_top else None,
                     "rank_changes": [{"store_id": sid, "before": old_ranks[sid], "after": new_ranks[sid]}
                                      for sid in sorted(set(old_top) & set(new_top)) if old_ranks[sid] != new_ranks[sid]]},
                     "badges": _content_delta("badges", old, new, old_top, new_top),
                     "reasons": _content_delta("reasons", old, new, old_top, new_top)})
    content_summaries = {}
    for field in ("badges", "reasons"):
        measured = [row for row in rows if row[field]["status"] == "MEASURED"]
        if not measured:
            content_summaries[field] = _unmeasured(f"no paired {field} inputs")
        else:
            content_summaries[field] = {
                "status": "MEASURED" if len(measured) == len(rows) else "PARTIALLY_MEASURED",
                "measured_request_count": len(measured),
                "unmeasured_request_ids": [row["request_id"] for row in rows if row[field]["status"] != "MEASURED"],
                "compared_request_store_pair_count": sum(row[field]["compared_common_store_count"] for row in measured),
                "changed_request_store_pair_count": sum(len(row[field]["changes"]) for row in measured),
                "unique_changed_store_count": len({change["store_id"] for row in measured for change in row[field]["changes"]}),
                "changed_common_content_request_count": sum(bool(row[field]["changes"]) for row in measured)}
    baseline_slots = sum(len(row["top_k"]["before"]) for row in rows)
    return {"status": "MEASURED", "top_k": k, "request_count": len(rows),
            "basis": "SUPPLIED_FROZEN_OUTPUTS_NOT_A_CLAIM_OF_PUBLISHED_EFFECT",
            "changed_ranking_request_count": sum(row["top_k"]["before"] != row["top_k"]["after"] for row in rows),
            "replaced_request_count": sum(bool(row["top_k"]["entered_store_ids"] or row["top_k"]["exited_store_ids"]) for row in rows),
            "baseline_slot_replacement_rate": sum(len(row["top_k"]["exited_store_ids"]) for row in rows) / baseline_slots if baseline_slots else None,
            **content_summaries, "requests": rows}


def compare_artifacts(baseline, candidate):
    """Pure comparison of validated frozen artifacts; inputs are not mutated."""
    before, old_requests = validate_artifact(baseline, "baseline")
    after, new_requests = validate_artifact(candidate, "candidate")
    old_scope, new_scope = baseline["scope"], candidate["scope"]
    if (set(before) != set(after) or any(old_scope[key] != new_scope[key]
            for key in ("cohort_id", "axis_dimensions", "label_dimensions"))):
        _fail("baseline and candidate must declare the same cohort and dimensions")
    ids = sorted(before)
    selected = [sid for sid in ids if after[sid]["publish_selected"]]
    blocked = [sid for sid in ids if after[sid]["serving_outcome"] == "NEEDS_REVIEW"]
    deltas = {sid: _store_delta(before[sid], after[sid]) for sid in ids}
    return {"report_version": 1, "baseline_artifact_id": baseline["artifact_id"],
            "candidate_artifact_id": candidate["artifact_id"], "scope": old_scope,
            "publication": {"status": "NOT_EXECUTED", "selected_store_ids": selected, "blocked_store_ids": blocked,
                            "unselected_store_ids": [sid for sid in ids if sid not in selected and sid not in blocked],
                            "selected_invalidation_store_ids": [sid for sid in selected if after[sid]["serving_outcome"] == "WITHOUT_LABELS"]},
            "proposals": {"baseline": _proposal_summary(baseline), "candidate": _proposal_summary(candidate)},
            "proposed_all_stores": _store_summary(ids, before, after, deltas),
            "planned_publish_only": _store_summary(selected, before, after, deltas),
            "stores": [{"store_id": sid, "publish_selected": sid in selected,
                        "serving_outcome": after[sid]["serving_outcome"], "proposed_delta": deltas[sid],
                        "planned_publish_delta": deltas[sid] if sid in selected else {}} for sid in ids],
            "recommendations": _recommendation_report(baseline, candidate, old_requests, new_requests)}


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            _fail("duplicate JSON object key: " + key)
        result[key] = value
    return result


def load_artifact(path):
    raw = Path(path).read_bytes()
    result = json.loads(raw, object_pairs_hook=_unique_object,
                        parse_constant=lambda constant: _fail("non-finite JSON number: " + constant))
    return result, hashlib.sha256(raw).hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--output", type=Path, help="Additionally create a new JSON report; never overwrite")
    args = parser.parse_args(argv)
    try:
        if args.output and args.output.resolve() in (args.baseline.resolve(), args.candidate.resolve()):
            _fail("output must not be an input artifact")
        baseline, baseline_hash = load_artifact(args.baseline)
        candidate, candidate_hash = load_artifact(args.candidate)
        report = compare_artifacts(baseline, candidate)
        report["input_sha256"] = {"baseline": baseline_hash, "candidate": candidate_hash}
        rendered = json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n"
        if args.output:
            with args.output.open("x", encoding="utf-8") as output:
                output.write(rendered)
        sys.stdout.write(rendered)
        return 0
    except (ImpactInputError, OSError, ValueError, TypeError) as error:
        print(json.dumps({"status": "INVALID_INPUT", "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
