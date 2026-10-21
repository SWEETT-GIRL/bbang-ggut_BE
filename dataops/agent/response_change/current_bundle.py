"""Prepare a novel menu-entity incident from captures, without an incident catalog.

The first automatic recipe supports an unfamiliar entity name inside a previously
understood, complete placeMenus/menus list with explicit fixed KRW displayText.
It does not invent meanings for arbitrary new fields or container counts. All
other shapes hold with a stable reason. No raw names, URLs, IDs or prose leave
this module. Independent expectations are a versioned host contract, not model output.
"""
from dataclasses import dataclass, fields
from datetime import datetime, timezone
import copy
import hashlib
import json
from pathlib import Path
import re

from .current_manifest import EDITABLE_PATHS, PARSER_PATH, PROFILE_PATH, verify_deployment
from .dispatch import FrozenBundle, Hold, MAX_BUNDLE_BYTES, MAX_PAYLOAD_BYTES
from .ledger import digest
from .runtime import LEGACY_TEST

RECIPE = "complete-fixed-won-menu-entity-v1"
CONTRACT = "naver-response-v1"
ENTITY = re.compile(r"[A-Za-z][A-Za-z0-9_]{0,63}\Z")
WON = re.compile(r"([0-9][0-9,]*)\s*원\Z")
MAX_MENUS = 64
ALLOWED_ISSUES = {"MENU_ENTITY_UNSUPPORTED", "PRICE_SHAPE_UNSUPPORTED", "IMAGE_SHAPE_UNSUPPORTED",
                  "MENU_INCOMPLETE", "MENU_OUTPUT_MISMATCH"}


def _resolve(raw, value):
    seen = set()
    while isinstance(value, dict) and "__ref" in value:
        ref = value["__ref"]
        if not isinstance(ref, str) or ref in seen or len(seen) >= 8 or ref not in raw:
            raise Hold("UNRESOLVED_REFERENCE")
        seen.add(ref)
        value = raw[ref]
    return value


def _observation_time(value):
    try:
        parsed = value if isinstance(value, datetime) else datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError()
        return parsed.astimezone(timezone.utc)
    except (ValueError, TypeError, AttributeError):
        raise Hold("OBSERVATION_PROVENANCE_REQUIRED") from None


def _capture_binding(sample, role):
    """Private provenance only; original payload text is never checkpointed."""
    return {**{key: sample[key] for key in ("capture_id", "source", "store_id", "external_id", "attempt_seq",
            "parser_digest", "contract_version", "payload_digest")},
            "observed_at": _observation_time(sample.get("observed_at")).isoformat(), "role": role}


def reduce_menu_capture(sample, profile):
    payload = sample.get("payload")
    if not isinstance(payload, str) or len(payload.encode()) > MAX_PAYLOAD_BYTES:
        raise Hold("CAPTURE_MISSING_OR_TOO_LARGE")
    if hashlib.sha256(payload.encode()).hexdigest() != sample.get("payload_digest"):
        raise Hold("CAPTURE_DIGEST_MISMATCH")
    try:
        raw = json.loads(payload)
        external = sample["external_id"]
        if not isinstance(external, str) or not external:
            raise Hold("TARGET_IDENTITY_MISMATCH")
        if not isinstance(raw, dict) or raw.get("errors") or not external:
            raise Hold("INCOMPLETE_OR_UNSCOPED_INPUT")
        roots = [(key, value) for key, value in raw.get("ROOT_QUERY", {}).items()
                 if key.startswith("placeDetail(") and key.endswith(")")]
        if len(roots) != 1:
            raise Hold("AMBIGUOUS_ROOT")
        key, root = roots[0]
        arguments = json.loads(key[len("placeDetail("):-1])
        if str(arguments.get("input", arguments).get("id")) != external:
            raise Hold("TARGET_IDENTITY_MISMATCH")
        root = _resolve(raw, root)
        if not isinstance(root, dict) or str(root.get("id", external)) != external:
            raise Hold("TARGET_IDENTITY_MISMATCH")
        bases = [value for key, value in raw.items() if key.startswith("PlaceDetailBase")]
        if (len(bases) != 1 or not isinstance(bases[0], dict)
                or str(bases[0].get("id", external)) != external
                or not isinstance(bases[0].get("name"), str) or not bases[0]["name"].strip()):
            raise Hold("TARGET_IDENTITY_MISMATCH")
        base = _resolve(raw, root.get("base", {}))
        if not isinstance(base, dict) or str(base.get("id", external)) != external:
            raise Hold("TARGET_IDENTITY_MISMATCH")
        mappings = {entry["field"]: entry for entry in profile["containers"]}
        selected = [(key, value) for key, value in root.items() if key.split("(", 1)[0] in mappings]
        if len(selected) != 1 or "(" in selected[0][0]:
            raise Hold("MENU_CONTAINER_MEANING_REVIEW_REQUIRED")
        field, value = selected[0]
        container = _resolve(raw, value)
        mapping = mappings[field]
        if not isinstance(container, dict):
            raise Hold("COMPLETE_LIST_REQUIRED")
        items = _resolve(raw, container.get(mapping["itemsField"]))
        totals = [container[name] for name in mapping["totalFields"] if type(container.get(name)) is int]
        page = _resolve(raw, container.get("pageInfo", {}))
        if (not isinstance(items, list) or not 1 <= len(items) <= MAX_MENUS
                or not totals or any(total != len(items) for total in totals)
                or container.get("hasNextPage") not in (None, False)
                or not isinstance(page, dict) or page.get("hasNextPage") not in (None, False)
                or "errors" in container):
            raise Hold("COMPLETE_LIST_REQUIRED")
        known = {item["typename"]: item for item in profile["entities"]}
        unknown = set()
        reduced = {"PlaceDetailBase:sample-store": {"id": "sample-store", "name": "표본 가게"}}
        refs, expected, names, ids = [], [], {}, set()
        recommendations = set()
        resolved_items = []
        for index, item in enumerate(items):
            menu = _resolve(raw, item)
            if not isinstance(menu, dict):
                raise Hold("MENU_ENTITY_MEANING_REVIEW_REQUIRED")
            # The current parser scans top-level entities. Moving an inline menu
            # into that map would silently repair a different failure while
            # constructing the fixture, so this recipe refuses that topology.
            if not any(menu is value for value in raw.values()):
                raise Hold("MENU_CACHE_SCOPE_REVIEW_REQUIRED")
            resolved_items.append(menu)
            kind, identity, name = menu.get("__typename"), menu.get("id"), menu.get("name")
            if (not isinstance(kind, str) or not ENTITY.fullmatch(kind)
                    or not isinstance(identity, (str, int)) or isinstance(identity, bool)
                    or not str(identity) or str(identity) in ids
                    or not isinstance(name, str) or not name.strip() or len(name) > 300):
                raise Hold("MENU_IDENTITY_OR_NAME_REVIEW_REQUIRED")
            ids.add(str(identity))
            label = names.setdefault(name, "메뉴-" + str(len(names) + 1))
            price = menu.get("price")
            codec = known.get(kind, {}).get("priceShape", "DISPLAY_TEXT_WON")
            if kind not in known:
                unknown.add(kind)
            if codec == "SCALAR":
                if isinstance(price, bool) or not isinstance(price, (str, int)) or not re.fullmatch(r"[0-9]{1,9}", str(price)):
                    raise Hold("MENU_PRICE_MEANING_REVIEW_REQUIRED")
                parsed_price, safe_price = str(price), price
            else:
                display = price.get("displayText") if isinstance(price, dict) else None
                match = WON.fullmatch(display.strip()) if isinstance(display, str) and len(display) < 40 else None
                if not match or price.get("priceType") not in (None, "fixed"):
                    raise Hold("MENU_PRICE_MEANING_REVIEW_REQUIRED")
                digits = match[1].replace(",", "")
                if len(digits) > 9 or not digits.isdigit():
                    raise Hold("MENU_PRICE_MEANING_REVIEW_REQUIRED")
                parsed_price, safe_price = digits, {"displayText": display}
                if "priceType" in price:
                    safe_price["priceType"] = price["priceType"]
            images = menu.get("images", [])
            if images is None:
                images = []
            if not isinstance(images, list) or len(images) > 8:
                raise Hold("MENU_IMAGE_MEANING_REVIEW_REQUIRED")
            safe_images, expected_images = [], []
            for position, image in enumerate(images):
                url = f"https://images.invalid/menu-{index + 1}-{position + 1}.jpg"
                if isinstance(image, str):
                    shape = "STRING"
                    safe_images.append(url)
                elif isinstance(image, dict) and isinstance(image.get("url"), str):
                    shape = "URL_OBJECT"
                    safe_images.append({"url": url})
                else:
                    raise Hold("MENU_IMAGE_MEANING_REVIEW_REQUIRED")
                if kind in known and shape not in known[kind]["imageShapes"]:
                    raise Hold("MENU_IMAGE_MEANING_REVIEW_REQUIRED")
                expected_images.append(url)
            description = menu.get("description")
            if description is not None and not isinstance(description, str):
                raise Hold("MENU_DESCRIPTION_MEANING_REVIEW_REQUIRED")
            description = None if description is None else "" if description == "" else "설명-" + str(index + 1)
            recommend = menu.get("recommend", False)
            if not isinstance(recommend, bool):
                raise Hold("MENU_RECOMMENDATION_MEANING_REVIEW_REQUIRED")
            if recommend:
                recommendations.add(label)
            alias = "menu-" + str(index + 1)
            ref = kind + ":" + alias
            reduced[ref] = {"__typename": kind, "id": alias, "name": label, "price": safe_price,
                            "images": safe_images, "description": description, "recommend": recommend}
            refs.append({"__ref": ref})
            expected.append({"name": label, "price": parsed_price, "imageUrls": expected_images,
                             "description": description, "groupName": None})
        relevant_types = set(known) | unknown
        cached = [value for value in raw.values() if isinstance(value, dict)
                  and value.get("__typename") in relevant_types]
        if len(cached) != len(resolved_items) or any(left is not right for left, right in zip(cached, resolved_items)):
            # Do not erase out-of-scope/duplicate/cache-order evidence and claim
            # a green reduced fixture proves the original observation correct.
            raise Hold("MENU_CACHE_SCOPE_REVIEW_REQUIRED")
        safe_container = {mapping["itemsField"]: refs}
        safe_container.update({name: container[name] for name in mapping["totalFields"] if name in container
                               and type(container[name]) is int})
        reduced["ROOT_QUERY"] = {'placeDetail({"input":{"id":"sample-store"}})': {
            "id": "sample-store", field: safe_container}}
        oracle = {"name": "표본 가게", "menus": expected,
                  "diagnostics": {"assessment": "READY", "menuAllowed": True,
                                  "modelEligible": False, "targetVerified": True, "issueCodes": []},
                  "evidence": {"menuComplete": True, "identityVerified": True,
                               "menuNames": sorted(names.values()), "representativeNames": sorted(recommendations)}}
        return reduced, oracle, sorted(unknown)
    except Hold:
        raise
    except (ValueError, TypeError, KeyError, AttributeError):
        raise Hold("MENU_STRUCTURE_REVIEW_REQUIRED") from None


def reviewed_alias_counterpart(sample, profile):
    """Private whole-raw control for the existing reviewed alias recipe only.

    Eligibility is rechecked against the original capture. Only cache keys,
    matching __ref values and the unknown entity's __typename may differ. All
    names, prices, IDs, other fields, ordering and completeness evidence survive.
    This is never a candidate-produced expected value or a payload to submit.
    """
    _, _, unknown = reduce_menu_capture(sample, profile)
    if len(unknown) != 1:
        raise Hold("SINGLE_UNFAMILIAR_ENTITY_REQUIRED")
    supported = next((row for row in profile['entities'] if row['typename'] == 'PlaceMenuItem'), None)
    if (supported is None or supported['priceShape'] != 'DISPLAY_TEXT_WON'
            or set(supported['imageShapes']) != {'STRING', 'URL_OBJECT'}):
        raise Hold('REVIEWED_ALIAS_BASE_CONTRACT_CHANGED')
    raw = json.loads(sample['payload'])
    kind = unknown[0]
    replacements = {}
    for key, value in raw.items():
        if isinstance(value, dict) and value.get('__typename') == kind:
            if not key.startswith(kind + ':'):
                raise Hold('MENU_CACHE_ALIAS_REVIEW_REQUIRED')
            new = 'PlaceMenuItem:' + key[len(kind) + 1:]
            if new in raw or new in replacements.values():
                raise Hold('MENU_CACHE_ALIAS_COLLISION')
            replacements[key] = new
    if not replacements:
        raise Hold('MENU_CACHE_ALIAS_REVIEW_REQUIRED')
    counterpart = {replacements.get(key, key): copy.deepcopy(value) for key, value in raw.items()}
    for new in replacements.values():
        counterpart[new]['__typename'] = 'PlaceMenuItem'

    def references(value):
        if isinstance(value, dict):
            if '__ref' in value and isinstance(value['__ref'], str):
                value['__ref'] = replacements.get(value['__ref'], value['__ref'])
            for nested in value.values():
                references(nested)
        elif isinstance(value, list):
            for nested in value:
                references(nested)
    references(counterpart)
    assert_alias_only_delta(raw, counterpart, kind)
    # Public provenance uses hashes, never original cache IDs or raw text.
    evidence = {'recipe': RECIPE, 'unknown_typename': kind, 'supported_typename': 'PlaceMenuItem',
                'original_payload_digest': sample['payload_digest'],
                'counterpart_digest': digest(counterpart), 'entity_count': len(replacements)}
    return counterpart, evidence


def assert_alias_only_delta(original, counterpart, unknown):
    """Reject even one unrelated normalization in a whole-capture control."""
    replacements = {key: 'PlaceMenuItem:' + key[len(unknown) + 1:] for key, value in original.items()
                    if isinstance(value, dict) and value.get('__typename') == unknown
                    and key.startswith(unknown + ':')}
    if not replacements or list(counterpart) != [replacements.get(key, key) for key in original]:
        raise Hold('WHOLE_CAPTURE_ALIAS_DELTA_CHANGED')

    def equal(before, after, *, entity=False):
        if type(before) is not type(after):
            return False
        if isinstance(before, dict):
            if list(before) != list(after):
                return False
            for key, value in before.items():
                expected = ('PlaceMenuItem' if entity and key == '__typename' and value == unknown
                            else replacements.get(value, value) if key == '__ref' and isinstance(value, str)
                            else value)
                if not equal(expected, after[key]):
                    return False
            return True
        if isinstance(before, list):
            return len(before) == len(after) and all(equal(a, b) for a, b in zip(before, after))
        return before == after

    if any(not equal(value, counterpart[replacements.get(key, key)], entity=key in replacements)
           for key, value in original.items()):
        raise Hold('WHOLE_CAPTURE_ALIAS_DELTA_CHANGED')


@dataclass(frozen=True)
class CurrentBundle(FrozenBundle):
    files: dict
    manifest_path: str
    manifest_digest: str
    reprocess_targets: tuple
    capture_bindings: tuple


class CurrentParserBundleProvider:
    def __init__(self, manifest_path, expected_manifest_digest, *, normal_loader=None, require_committed=True):
        self.path = Path(manifest_path).resolve()
        self.expected_digest = expected_manifest_digest
        self.require_committed = require_committed
        self.manifest = verify_deployment(self.path, expected_manifest_digest=expected_manifest_digest,
                                          require_committed=require_committed)
        self.current_parser_digest = self.manifest["parser_digest"]
        self.contract_version = CONTRACT
        self.normal_loader = normal_loader

    def freeze(self, bundle):
        # JSONB changes nested object key order. The parser intentionally visits
        # entity maps in input order, so persist the complete reduced bundle as
        # bounded JSON text and bind those exact bytes as well as its contents.
        payload = json.dumps(bundle.__dict__, ensure_ascii=False)
        if len(payload.encode()) > MAX_BUNDLE_BYTES:
            raise Hold("CURRENT_BUNDLE_TOO_LARGE")
        return {"version": 1, "payload": payload, "payload_digest": hashlib.sha256(payload.encode()).hexdigest(),
                "bundle_digest": bundle.digest()}

    def restore(self, incident, frozen, capture_loader):
        manifest = verify_deployment(self.path, expected_manifest_digest=self.expected_digest,
                                     require_committed=self.require_committed)
        try:
            if (not isinstance(frozen, dict) or set(frozen) != {"version", "payload", "payload_digest", "bundle_digest"}
                    or frozen["version"] != 1 or not isinstance(frozen["payload"], str)
                    or len(frozen["payload"].encode()) > MAX_BUNDLE_BYTES
                    or hashlib.sha256(frozen["payload"].encode()).hexdigest() != frozen["payload_digest"]):
                raise Hold("FROZEN_BUNDLE_INVALID")
            values = json.loads(frozen["payload"])
            if not isinstance(values, dict) or set(values) != {field.name for field in fields(CurrentBundle)}:
                raise Hold("FROZEN_BUNDLE_INVALID")
            bundle = CurrentBundle(**values)
            if (bundle.digest() != frozen["bundle_digest"] or bundle.manifest_digest != self.expected_digest
                    or bundle.manifest_path != str(self.path) or bundle.deployment_parser_digest != manifest["parser_digest"]
                    or incident["source"] != "NAVER" or incident["parser_digest"] != bundle.deployment_parser_digest
                    or incident["contract_version"] != bundle.contract_version or bundle.contract_version != CONTRACT
                    or bundle.case["case_id"] != "incident-" + incident["fingerprint"][:20]):
                raise Hold("FROZEN_BUNDLE_BINDING_MISMATCH")
            bindings = bundle.capture_bindings
            if (not isinstance(bindings, list) or not 1 <= len(bindings) <= 6
                    or len({row["capture_id"] for row in bindings}) != len(bindings)):
                raise Hold("FROZEN_CAPTURE_BINDING_INVALID")
            captured = capture_loader([row["capture_id"] for row in bindings])
            by_id = {row["capture_id"]: row for row in captured}
            if len(by_id) != len(bindings) or len(captured) != len(bindings):
                raise Hold("FROZEN_CAPTURE_NOT_FOUND")
            for binding in bindings:
                sample = by_id.get(binding["capture_id"])
                if sample is None:
                    raise Hold("FROZEN_CAPTURE_NOT_FOUND")
                if (binding["role"] not in {"TARGET", "PRIOR_NORMAL"}
                        or _capture_binding(sample, binding["role"]) != binding
                        or binding["role"] == "PRIOR_NORMAL" and sample.get("status") != "READY"):
                    raise Hold("FROZEN_CAPTURE_BINDING_CHANGED")
                payload = sample.get("payload")
                if (not isinstance(payload, str) or len(payload.encode()) > MAX_PAYLOAD_BYTES
                        or hashlib.sha256(payload.encode()).hexdigest() != binding["payload_digest"]):
                    raise Hold("CAPTURE_DIGEST_MISMATCH")
            return bundle
        except Hold:
            raise
        except (ValueError, TypeError, KeyError, AttributeError):
            raise Hold("FROZEN_BUNDLE_INVALID") from None

    def prepare(self, incident, samples):
        manifest = verify_deployment(self.path, expected_manifest_digest=self.expected_digest,
                                     require_committed=self.require_committed)
        if (incident["source"] != "NAVER" or incident["parser_digest"] != manifest["parser_digest"]
                or incident["contract_version"] != CONTRACT):
            raise Hold("DEPLOYED_PARSER_OR_CONTRACT_MISMATCH")
        try:
            issues = json.loads(incident["diagnostics"]) if isinstance(incident["diagnostics"], str) else incident["diagnostics"]
            if isinstance(issues, dict):
                issues = issues.get("issues", [])
            codes = {item["code"] for item in issues}
        except (ValueError, KeyError, TypeError):
            raise Hold("INVALID_DIAGNOSTIC_CONTRACT") from None
        if "MENU_ENTITY_UNSUPPORTED" not in codes or not codes <= ALLOWED_ISSUES:
            raise Hold("UNSUPPORTED_REPAIR_FAMILY_REVIEW_REQUIRED")
        snapshot = self.path.parent / "snapshot"
        files = {path: (snapshot / path).read_text() for path in (*EDITABLE_PATHS, LEGACY_TEST)}
        profile = json.loads(files[PROFILE_PATH])
        selected = sorted(samples, key=lambda sample: str(sample.get("capture_id", "")))
        if not selected:
            raise Hold("CAPTURE_SAMPLE_REQUIRED")
        fixtures, targets, public, bindings = [], [], [], []
        for sample in selected[:4]:
            if (sample.get("source") != "NAVER" or sample.get("parser_digest") != manifest["parser_digest"]
                    or sample.get("contract_version") != CONTRACT or not sample.get("capture_id")):
                raise Hold("CAPTURE_BINDING_MISMATCH")
            reduced, expected, unknown = reduce_menu_capture(sample, profile)
            if len(unknown) != 1:
                raise Hold("SINGLE_UNFAMILIAR_ENTITY_REQUIRED")
            case_id = "new-" + digest(sample["capture_id"])[:16]
            fixtures.append({"id": case_id, "role": "NEW_STRUCTURE", "visibility": "public",
                "external_id": "sample-store", "raw": reduced, "expected": expected,
                "oracle": {"kind": "REVIEWED_CONTRACT", "reference": RECIPE}})
            if (type(sample.get("store_id")) is not int or sample["store_id"] <= 0
                    or type(sample.get("attempt_seq")) is not int or sample["attempt_seq"] < 0):
                raise Hold("OBSERVATION_PROVENANCE_REQUIRED")
            targets.append({**{key: sample[key] for key in
                ("capture_id", "store_id", "external_id", "attempt_seq", "payload_digest")},
                "observed_at": _observation_time(sample.get("observed_at")).isoformat()})
            bindings.append(_capture_binding(sample, "TARGET"))
            public.append({"id": case_id, "raw": reduced, "unfamiliar_entity": unknown[0]})
        # A deterministic control checks the same known meaning under a supported
        # typename. It is explicitly synthetic, never claimed to be an old observation.
        control = copy.deepcopy(fixtures[0])
        control.update(id="normal-control", role="NORMAL_REGRESSION", visibility="hidden")
        control["oracle"]["reference"] = RECIPE + ":controlled-supported-entity"
        raw = control["raw"]
        replacements = {key: "PlaceMenuItem:" + key.split(":", 1)[1] for key, value in raw.items()
                        if isinstance(value, dict) and value.get("__typename") not in (None, "Menu", "PlaceMenuItem")}
        # Parser output preserves entity-map order. Renaming must not move mixed
        # old/new entries and manufacture a false normal-regression failure.
        raw = {replacements.get(key, key): value for key, value in raw.items()}
        control["raw"] = raw
        for new in replacements.values():
            raw[new]["__typename"] = "PlaceMenuItem"
        def rewrite(value):
            if isinstance(value, dict):
                if "__ref" in value:
                    value["__ref"] = replacements.get(value["__ref"], value["__ref"])
                for nested in value.values():
                    rewrite(nested)
            elif isinstance(value, list):
                for nested in value:
                    rewrite(nested)
        rewrite(raw)
        fixtures.insert(0, control)
        incomplete = copy.deepcopy(control)
        incomplete.update(id="contract-missing-total", role="CONTRACT_COUNTEREXAMPLE")
        incomplete["oracle"]["reference"] = RECIPE + ":complete-list-required"
        for root in incomplete["raw"]["ROOT_QUERY"].values():
            for mapping in profile["containers"]:
                if mapping["field"] in root:
                    for total in mapping["totalFields"]:
                        root[mapping["field"]].pop(total, None)
        incomplete["expected"]["diagnostics"] = {"assessment": "PARTIAL", "menuAllowed": False,
            "modelEligible": False, "targetVerified": True, "issueCodes": ["MENU_INCOMPLETE"]}
        incomplete["expected"]["evidence"]["menuComplete"] = False
        fixtures.append(incomplete)
        normal_origin = "controlled supported-type counterpart plus immutable existing regression suites"
        if self.normal_loader:
            for normal in self.normal_loader(selected[0])[:2]:
                if (normal.get("source") != "NAVER" or normal.get("status") != "READY"
                        or normal.get("store_id") != selected[0].get("store_id")
                        or normal.get("external_id") != selected[0].get("external_id")
                        or not normal.get("capture_id") or normal.get("parser_digest") != manifest["parser_digest"]
                        or normal.get("contract_version") != CONTRACT
                        or _observation_time(normal.get("observed_at")) >= _observation_time(selected[0].get("observed_at"))):
                    raise Hold("PRIOR_CAPTURE_BINDING_MISMATCH")
                reduced, expected, unknown = reduce_menu_capture(normal, profile)
                if not unknown:
                    bindings.append(_capture_binding(normal, "PRIOR_NORMAL"))
                    fixtures.append({"id": "prior-" + digest(normal["capture_id"])[:16],
                        "role": "NORMAL_REGRESSION", "visibility": "hidden", "external_id": "sample-store",
                        "raw": reduced, "expected": expected,
                        "oracle": {"kind": "REVIEWED_CONTRACT", "reference": RECIPE + ":prior-capture"}})
                    normal_origin = "prior complete capture, controlled counterpart and immutable regression suites"
        case = {"case_id": "incident-" + incident["fingerprint"][:20], "recipe": RECIPE,
                "scope": "menu entity aliases with known complete-list and fixed-WON meaning only",
                "samples": public, "normal_evidence": normal_origin,
                "contract": "Preserve complete scoped menu names, fixed numeric KRW prices, image shapes and recommendations; unknown meaning must hold.",
                "deployment_manifest_digest": self.expected_digest,
                "automatic_deployment_authorized": False}
        bundle = CurrentBundle(case, files[PARSER_PATH], files[LEGACY_TEST], fixtures,
            manifest["parser_digest"], CONTRACT, tuple(item["payload_digest"] for item in targets),
            files, str(self.path), self.expected_digest, tuple(targets), tuple(bindings))
        if len(json.dumps(bundle.__dict__, ensure_ascii=False).encode()) > MAX_BUNDLE_BYTES:
            raise Hold("CURRENT_BUNDLE_TOO_LARGE")
        return bundle
