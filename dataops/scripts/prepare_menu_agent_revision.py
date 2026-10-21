#!/usr/bin/env python3
"""Freeze oracle v2 after the explicitly recorded post-hoc price review.

Read only the previously frozen case and challenge artifacts. Do not read parser
implementations, generate a human patch, call a model, or change prior evidence.
The resulting case is suitable for a separately authorized Sonnet evaluation.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path


CONTRACT_VERSION = "menu-repair-eval-v2"
ORACLE_VERSION = 2
PRIMITIVE_CONTRACT = (
    "For PlaceMenuItem, price is supported only as an object with textual displayText. "
    "Primitive string or numeric prices are unsupported and must yield null, even if a scalar looks numeric. "
    "This rule is specific to PlaceMenuItem: preserve the existing Menu scalar-price behavior unchanged."
)
CHALLENGE_PRICES = {
    "post-review-variable-string": "시가",
    "post-review-numeric-scalar": 5300,
    "post-review-range-string": "5000~7000",
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_private(path: Path, value: object) -> None:
    # The revision directory itself is also exclusive, so a retry cannot replace evidence.
    with path.open("x", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write("\n")
    path.chmod(0o600)


def validate_challenges(challenge: dict, fixture_hash: str) -> list[dict]:
    if (challenge.get("evaluation_stage") != "POST_HOC_REVIEW_CHALLENGE"
            or challenge.get("counts_as_original_benchmark") is not False
            or challenge.get("original_fixture_file_sha256") != fixture_hash):
        raise ValueError("Challenge must identify the unchanged original oracle and post-hoc stage")
    cases = challenge.get("cases", [])
    if len(cases) != 3 or {row.get("id") for row in cases} != set(CHALLENGE_PRICES):
        raise ValueError("Exactly the three frozen primitive-price challenges are required")
    for row in cases:
        if row.get("visibility") != "hidden" or row.get("kind") != "post_review_challenge":
            raise ValueError("Post-hoc challenge classification must remain explicit")
        entities = [value for value in row.get("raw", {}).values()
                    if isinstance(value, dict) and value.get("__typename") in {"Menu", "PlaceMenuItem"}]
        if len(entities) != 2 or [entity["__typename"] for entity in entities] != ["PlaceMenuItem", "Menu"]:
            raise ValueError("Each challenge must independently compare new and legacy menu entities")
        price = CHALLENGE_PRICES[row["id"]]
        expected = []
        for entity in entities:
            if type(entity.get("price")) is not type(price) or entity["price"] != price:
                raise ValueError("Frozen primitive price value or type differs from the reviewed case")
            expected.append({"name": entity["name"],
                             "price": None if entity["__typename"] == "PlaceMenuItem" else str(price),
                             "description": None, "imageUrls": [], "groupName": None})
        if row.get("expected") != {"menus": expected}:
            raise ValueError("Challenge must reject new primitive prices while preserving legacy values")
    return copy.deepcopy(cases)


def prepare_revision(source_dir: Path, challenge_dir: Path, output_dir: Path) -> dict:
    source_dir, challenge_dir, output_dir = (Path(path).resolve() for path in
                                             (source_dir, challenge_dir, output_dir))
    if output_dir.exists():
        raise FileExistsError("Revision output already exists; prior evidence cannot be overwritten")
    if output_dir.is_relative_to(source_dir) or output_dir.is_relative_to(challenge_dir):
        raise ValueError("Revision output must be separate from prior evidence directories")
    source_bytes = {name: (source_dir / name).read_bytes() for name in
                    ("manifest.json", "case.json", "fixtures.json", "provenance.private.json")}
    manifest = json.loads(source_bytes["manifest.json"])
    for name in ("case.json", "fixtures.json", "provenance.private.json"):
        if sha256(source_bytes[name]) != manifest["files"].get(name):
            raise ValueError("Original frozen input digest mismatch: " + name)
    case = json.loads(source_bytes["case.json"])
    fixtures = json.loads(source_bytes["fixtures.json"])
    provenance = json.loads(source_bytes["provenance.private.json"])
    if (manifest.get("fixture_count") != 7 or len(fixtures.get("cases", [])) != 7
            or case["case_id"] != manifest["case_id"] or fixtures["case_id"] != case["case_id"]
            or case["parser_base_commit"] != manifest["base_commit"]):
        raise ValueError("Expected the consistent original seven-case historical baseline")
    if case.get("oracle_version", 1) != 1 or fixtures.get("oracle_version", 1) != 1:
        raise ValueError("Only the original oracle can be revised to version 2")
    challenge_bytes = (challenge_dir / "challenge-manifest.json").read_bytes()
    challenge = json.loads(challenge_bytes)
    additional = validate_challenges(challenge, sha256(source_bytes["fixtures.json"]))
    old_ids = {row["id"] for row in fixtures["cases"]}
    if len(old_ids) != 7 or old_ids & {row["id"] for row in additional}:
        raise ValueError("Original and added fixture IDs must remain unique")

    original_id = case["case_id"]
    revised_id = original_id + "-v2"
    revision = {
        "previous_case_id": original_id,
        "oracle_version": ORACLE_VERSION,
        "contract_version": CONTRACT_VERSION,
        "evaluation_stage": "REVISED_ORACLE_AFTER_POST_HOC_REVIEW",
        "counts_as_original_benchmark": False,
        "reason": "Add the three explicitly frozen primitive-price counterexamples found after the original paid run.",
    }
    case.update(id=revised_id, case_id=revised_id, oracle_version=ORACLE_VERSION,
                contract_version=CONTRACT_VERSION, revision=revision)
    case["contracts"].append(PRIMITIVE_CONTRACT)
    fixtures.update(case_id=revised_id, oracle_version=ORACLE_VERSION, contract_version=CONTRACT_VERSION,
                    revision=revision)
    fixtures["cases"].extend(additional)
    fixtures["oracle_origin"] += (
        " Version 2 retains all seven original cases and adds three previously frozen post-hoc review challenges. "
        "These three cases are known counterexamples for this revision, not an untouched evaluation set."
    )
    provenance["revision"] = {
        **revision,
        "original_manifest_sha256": sha256(source_bytes["manifest.json"]),
        "original_files": {name: sha256(data) for name, data in source_bytes.items()},
        "challenge_manifest_sha256": sha256(challenge_bytes),
        "challenge_created_at": challenge.get("created_at"),
        "previous_candidate_digest": challenge.get("candidate_digest"),
        "previous_candidate_reference": challenge.get("candidate_path"),
        "parser_implementation_read": False,
        "human_patch_supplied": False,
    }
    # Verify our inputs have not changed while preparing this immutable revision.
    if any((source_dir / name).read_bytes() != value for name, value in source_bytes.items()):
        raise ValueError("Original evidence changed during revision preparation")
    if (challenge_dir / "challenge-manifest.json").read_bytes() != challenge_bytes:
        raise ValueError("Challenge evidence changed during revision preparation")
    output_dir.mkdir(parents=True, exist_ok=False, mode=0o700)
    output_dir.chmod(0o700)
    for name, value in (("case.json", case), ("fixtures.json", fixtures),
                        ("provenance.private.json", provenance)):
        write_private(output_dir / name, value)
    revised_manifest = {
        "schema_version": 1,
        "case_id": revised_id,
        "base_commit": case["parser_base_commit"],
        "contract_version": CONTRACT_VERSION,
        "oracle_version": ORACLE_VERSION,
        "files": {name: sha256((output_dir / name).read_bytes()) for name in
                  ("case.json", "fixtures.json", "provenance.private.json")},
        "fixture_count": len(fixtures["cases"]),
        "original_fixture_count": 7,
        "added_post_hoc_fixture_count": 3,
        "real_source_pair": manifest["real_source_pair"],
        "source_payloads_exported": manifest["source_payloads_exported"],
        "revision": revision,
    }
    write_private(output_dir / "manifest.json", revised_manifest)
    return {"status": "FROZEN", "output_dir": str(output_dir), "case_id": revised_id,
            "contract_version": CONTRACT_VERSION, "oracle_version": ORACLE_VERSION,
            "fixture_count": len(fixtures["cases"]),
            "manifest_sha256": sha256((output_dir / "manifest.json").read_bytes()),
            "new_model_calls": 0}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--challenge-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    os.umask(0o077)
    print(json.dumps(prepare_revision(args.source_dir, args.challenge_dir, args.output_dir), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
