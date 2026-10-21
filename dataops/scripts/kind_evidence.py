"""Pure menu/kind evidence replay. No database, network, or filesystem writes.

Collection time of review aggregates is never evidence that a menu is on sale.
Only verified, same-place menu/representative observations can clear a tombstone.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re

import menu_classifier
from menu_classifier import CATEGORY_END_WORDS, GENERIC_KINDS, classify, doubt

KST = timezone(timedelta(hours=9))
FORMAT_VERSION = 2
POLICY_VERSION = 1
KNOWN_KINDS = frozenset(kind for kind, _ in CATEGORY_END_WORDS)


def _digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


# Hash loaded vocabulary AND Python rule source: rule-only edits must invalidate
# a frozen result even if a manual version string was not bumped.
CLASSIFIER_VERSION = "menu-classifier-doubt-v1"


def _classifier_fingerprint(source_bytes):
    source_fingerprint = hashlib.sha256(source_bytes).hexdigest()
    return _digest([CLASSIFIER_VERSION, CATEGORY_END_WORDS, sorted(GENERIC_KINDS), source_fingerprint])


CLASSIFIER_FINGERPRINT = _classifier_fingerprint(Path(menu_classifier.__file__).read_bytes())


def normalize_name(name):
    return re.sub(r"[^0-9a-z가-힣]", "", (name or "").lower())


def canonical_kind(name, allowed_kinds=None):
    """Resolve aliases consistently; only known/explicitly allowed exact kinds bypass doubt."""
    if not isinstance(name, str) or not name.strip():
        return None
    allowed = None if allowed_kinds is None else frozenset(allowed_kinds)
    exact = name.strip()
    if exact in GENERIC_KINDS:
        return None
    if exact in KNOWN_KINDS or (allowed is not None and exact in allowed):
        return exact if allowed is None or exact in allowed else None
    kind, _, word = classify(exact)
    if kind is None or kind in GENERIC_KINDS or doubt(exact, kind, word):
        return None
    return kind if allowed is None or kind in allowed else None


def _time(value):
    if value in (None, ""):
        return None
    parsed = value if isinstance(value, datetime) else datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    return parsed.replace(tzinfo=KST) if parsed.tzinfo is None else parsed.astimezone(KST)


def _iso(value):
    return value.isoformat() if value is not None else None


@dataclass(frozen=True)
class EvidencePolicy:
    # Conservative proposed defaults; serialized with each frozen evidence result.
    min_interval: timedelta = timedelta(hours=6)
    max_interval: timedelta = timedelta(days=30)
    signature_missing_count: int = 1
    score_missing_count: int = 2

    def __post_init__(self):
        if self.min_interval <= timedelta(0) or self.max_interval < self.min_interval:
            raise ValueError("invalid observation interval policy")
        if self.signature_missing_count != 1 or self.score_missing_count != 2:
            raise ValueError("this evidence version requires signature=1, score=2")

    def as_dict(self):
        return {"version": POLICY_VERSION, "min_interval_seconds": self.min_interval.total_seconds(),
                "max_interval_seconds": self.max_interval.total_seconds(),
                "signature_missing_count": self.signature_missing_count,
                "score_missing_count": self.score_missing_count}

    @classmethod
    def from_dict(cls, value):
        if value.get("version") != POLICY_VERSION:
            raise ValueError("unsupported evidence policy version")
        return cls(timedelta(seconds=value["min_interval_seconds"]),
                   timedelta(seconds=value["max_interval_seconds"]),
                   value["signature_missing_count"], value["score_missing_count"])


@dataclass
class _Track:
    missing_count: int = 0
    last_missing_at: datetime | None = None
    last_positive_at: datetime | None = None
    legacy_hold: bool = False
    hold_since: datetime | None = None
    confirmed_missing: bool = False

    @property
    def state(self):
        if self.confirmed_missing:
            return "CONFIRMED_MISSING"
        if self.legacy_hold:
            return "UNKNOWN"
        if self.missing_count:
            return "MISSING_ONCE"
        return "PRESENT" if self.last_positive_at else "UNKNOWN"

    def positive(self, at):
        if self.legacy_hold and (self.hold_since is None or at <= self.hold_since):
            return
        if self.last_missing_at is not None and at <= self.last_missing_at:
            return
        self.last_positive_at = at
        self.missing_count = 0
        self.last_missing_at = None
        self.legacy_hold = False
        self.hold_since = None
        self.confirmed_missing = False

    def missing(self, at, policy):
        if self.legacy_hold and self.hold_since is None:
            return  # Neither positives nor replayed negatives can order unknown legacy evidence.
        if self.hold_since is not None and at <= self.hold_since:
            return
        if self.last_missing_at is not None:
            elapsed = at - self.last_missing_at
            if elapsed < policy.min_interval:
                return
            if elapsed > policy.max_interval:
                self.missing_count = 0
        self.missing_count += 1
        self.last_missing_at = at
        if self.missing_count >= policy.score_missing_count:
            self.confirmed_missing = True

    def allows(self, consumer, policy):
        if consumer not in ("score", "signature"):
            raise ValueError("consumer must be score or signature")
        threshold = getattr(policy, consumer + "_missing_count")
        return not (self.legacy_hold or self.confirmed_missing or self.missing_count >= threshold)

    def as_dict(self):
        return {"state": self.state, "missing_count": self.missing_count,
                "last_missing_at": _iso(self.last_missing_at), "last_positive_at": _iso(self.last_positive_at),
                "legacy_hold": self.legacy_hold, "hold_since": _iso(self.hold_since),
                "confirmed_missing": self.confirmed_missing}

    @classmethod
    def from_dict(cls, value):
        result = cls(value["missing_count"], _time(value["last_missing_at"]),
                     _time(value["last_positive_at"]), value["legacy_hold"],
                     _time(value["hold_since"]), value["confirmed_missing"])
        if result.state != value["state"]:
            raise ValueError("inconsistent evidence state")
        return result


class StoreEvidence:
    def __init__(self, external_id, policy, names, kinds, input_fingerprint, ignored_events,
                 representative_names=(), latest_observed_at=None, unmapped_legacy=()):
        self.external_id = str(external_id)
        self.policy = policy
        self._names = names
        self._kinds = kinds
        self.input_fingerprint = input_fingerprint
        self.ignored_events = ignored_events
        # Empty means no explicit representatives in the latest accepted event,
        # not permission to refill the list from stale reviews or mutable signals.
        self.representative_names = tuple(representative_names)
        self.latest_observed_at = latest_observed_at
        self.unmapped_legacy = tuple(unmapped_legacy)

    @property
    def has_observations(self):
        """Whether this generation has an accepted observation, including partials.

        This does not assert a complete menu census or positive evidence for every
        kind; consumers still use the per-name/kind state and their own threshold.
        """
        return self.latest_observed_at is not None

    @property
    def states(self):
        return {kind: track.state for kind, track in sorted(self._kinds.items())}

    @property
    def name_states(self):
        return {name: track.state for name, track in sorted(self._names.items())}

    @property
    def publication_blockers(self):
        """Preserved legacy ambiguity is not permission to suppress serving data.

        A new verified positive or two independent complete negatives can resolve
        an identifiable track. An unidentified legacy record needs manual review.
        Name holds matter even when another current menu restores the same kind.
        """
        blockers = []
        for dimension, tracks in (("name", self._names), ("kind", self._kinds)):
            for value, track in sorted(tracks.items()):
                if track.legacy_hold and (track.hold_since is None or not track.confirmed_missing):
                    blockers.append({"reason": "UNRESOLVED_LEGACY", "dimension": dimension,
                                     "value": value, "evidence_at": _iso(track.hold_since),
                                     "missing_count": track.missing_count})
        blockers.extend({"reason": "UNMAPPED_LEGACY", **row} for row in self.unmapped_legacy)
        return blockers

    @property
    def publication_status(self):
        return "NEEDS_REVIEW" if self.publication_blockers else "READY"

    def require_publishable(self):
        if self.publication_blockers:
            raise ValueError("NEEDS_REVIEW: unresolved legacy evidence; keep existing serving rows unchanged")

    def allows_kind(self, kind, consumer="score"):
        track = self._kinds.get(canonical_kind(kind) or kind, _Track())
        return track.allows(consumer, self.policy)

    def allows_name(self, name, consumer="signature"):
        track = self._names.get(normalize_name(name), _Track())
        kind = canonical_kind(name)
        return track.allows(consumer, self.policy) and (kind is None or self.allows_kind(kind, consumer))

    def filter_menus(self, menus):
        return [menu for menu in menus if self.allows_name(menu.get("name"), "score")
                and self.allows_kind(menu.get("kind"), "score")]

    def _body(self):
        return {"format_version": FORMAT_VERSION, "classifier_fingerprint": CLASSIFIER_FINGERPRINT,
                "external_id": self.external_id, "policy": self.policy.as_dict(),
                "names": {k: v.as_dict() for k, v in sorted(self._names.items())},
                "kinds": {k: v.as_dict() for k, v in sorted(self._kinds.items())},
                "representative_names": list(self.representative_names),
                "latest_observed_at": _iso(self.latest_observed_at),
                "unmapped_legacy": list(self.unmapped_legacy),
                "input_fingerprint": self.input_fingerprint, "ignored_events": self.ignored_events}

    @property
    def fingerprint(self):
        return _digest(self._body())

    def as_dict(self):
        body = self._body()
        return dict(body, fingerprint=_digest(body))

    @classmethod
    def from_dict(cls, value):
        value = dict(value)
        expected = value.pop("fingerprint", None)
        if expected != _digest(value):
            raise ValueError("evidence fingerprint mismatch")
        if value.get("format_version") != FORMAT_VERSION:
            raise ValueError("unsupported evidence format version")
        if value.get("classifier_fingerprint") != CLASSIFIER_FINGERPRINT:
            raise ValueError("evidence classifier has changed; rebuild required")
        return cls(value["external_id"], EvidencePolicy.from_dict(value["policy"]),
                   {k: _Track.from_dict(v) for k, v in value["names"].items()},
                   {k: _Track.from_dict(v) for k, v in value["kinds"].items()},
                   value["input_fingerprint"], value["ignored_events"],
                   value["representative_names"], _time(value["latest_observed_at"]), value["unmapped_legacy"])


def build_store_evidence(observations, legacy_removals, menus, external_id, policy=None):
    """Replay a single place generation in arrival order, ignoring stale or duplicate observations.

    Baseline menu rows name what can be missing; their mere survival in serving
    is not a dated positive observation. Legacy removals hold even after approval.
    """
    if external_id in (None, ""):
        raise ValueError("current external_id is required")
    external_id = str(external_id)
    policy = policy or EvidencePolicy()
    observations, legacy_removals, menus = list(observations), list(legacy_removals), list(menus)
    names, kinds, baseline_kinds, unmapped_legacy = {}, {}, {}, []
    inputs = {"menus": [], "legacy_removals": [], "observations": []}
    for menu in menus:
        name = normalize_name(menu.get("name"))
        kind = canonical_kind(menu.get("kind")) or canonical_kind(menu.get("name"))
        if name:
            names.setdefault(name, _Track())
        if kind:
            kinds.setdefault(kind, _Track())
            if name:
                baseline_kinds.setdefault(name, set()).add(kind)
        inputs["menus"].append({"id": str(menu.get("id", "")), "name": name, "kind": kind})

    def add_hold(track, at):
        if not track.legacy_hold:
            track.hold_since = at
        elif track.hold_since is None or at is None:
            track.hold_since = None
        else:
            track.hold_since = max(track.hold_since, at)
        track.legacy_hold = True

    for removal in legacy_removals:
        if removal.get("source", "NAVER") != "NAVER":
            continue
        removal_external_id = removal.get("external_id")
        if removal_external_id not in (None, "") and str(removal_external_id) != external_id:
            continue
        name = normalize_name(removal.get("menu_name"))
        kind = canonical_kind(removal.get("kind")) or canonical_kind(removal.get("menu_name"))
        at = _time(removal.get("evidence_at"))
        if name:
            add_hold(names.setdefault(name, _Track()), at)
        if kind:
            add_hold(kinds.setdefault(kind, _Track()), at)
        if not name and not kind:
            unmapped_legacy.append({"proposal_id": str(removal.get("proposal_id", "")),
                                    "menu_id": str(removal.get("menu_id", "")), "evidence_at": _iso(at)})
        # Approval state is deliberately not an evidence input.
        inputs["legacy_removals"].append({"proposal_id": str(removal.get("proposal_id", "")),
                                         "menu_id": str(removal.get("menu_id", "")),
                                         "name": name, "kind": kind, "evidence_at": _iso(at),
                                         "snapshot_id": str(removal.get("snapshot_id") or ""),
                                         "proposal_created_at": _iso(_time(removal.get("proposal_created_at"))),
                                         "time_basis": removal.get("time_basis")})

    def order(event):
        at = _time(event.get("received_at")) or _time(event.get("observed_at"))
        ident = str(event.get("id", event.get("observation_id", "")))
        return (at or datetime.min.replace(tzinfo=KST), int(ident) if ident.isdigit() else 0, ident)

    seen, watermark, representative_names = {}, None, ()
    ignored = {"unverified_or_other_place": 0, "late": 0, "missing_time": 0}
    for event in sorted(observations, key=order):
        if (event.get("source") != "NAVER" or str(event.get("external_id", "")) != external_id
                or event.get("identity_verified") is not True):
            ignored["unverified_or_other_place"] += 1
            continue
        at = _time(event.get("observed_at"))
        if at is None:
            ignored["missing_time"] += 1
            continue
        menu_names = sorted({name for name in (event.get("menu_names") or [])
                             if isinstance(name, str) and name.strip()})
        representatives = sorted({name for name in (event.get("representative_names") or [])
                                  if isinstance(name, str) and name.strip()})
        direct_names = sorted(set(menu_names) | set(representatives))
        payload = {"observed_at": _iso(at), "menu_names": menu_names,
                   "representative_names": representatives,
                   "menu_complete": event.get("menu_complete") is True,
                   "parser_version": event.get("parser_version")}
        ident = str(event.get("observation_id") or event.get("id") or "")
        if not ident:
            raise ValueError("observation identity is required")
        digest = _digest(payload)
        if ident in seen:
            if seen[ident] != digest:
                raise ValueError("same observation identity has conflicting content")
            continue
        seen[ident] = digest
        if watermark is not None and at < watermark:
            ignored["late"] += 1
            continue
        watermark = at
        representative_names = tuple(representatives)
        inputs["observations"].append({"id": ident, **payload})
        present_names = {normalize_name(name) for name in direct_names}
        present_kinds = {kind for name in direct_names if (kind := canonical_kind(name)) is not None}
        # Existing category rows may name a kind even when the sales name cannot
        # be classified. The row supplies taxonomy, never current presence: only
        # an explicit same-name observation activates this mapping.
        for name in direct_names:
            if canonical_kind(name) is None:
                present_kinds.update(baseline_kinds.get(normalize_name(name), ()))
        for name in present_names:
            names.setdefault(name, _Track()).positive(at)
        for kind in present_kinds:
            kinds.setdefault(kind, _Track()).positive(at)
        if event.get("menu_complete") is True:
            for name, track in names.items():
                if name not in present_names:
                    track.missing(at, policy)
            for kind, track in kinds.items():
                if kind not in present_kinds:
                    track.missing(at, policy)
    for key in inputs:
        inputs[key] = sorted(inputs[key], key=lambda item: json.dumps(item, sort_keys=True, ensure_ascii=False))
    return StoreEvidence(external_id, policy, names, kinds, _digest(inputs), ignored,
                         representative_names, watermark,
                         sorted(unmapped_legacy, key=lambda row: json.dumps(row, sort_keys=True)))
