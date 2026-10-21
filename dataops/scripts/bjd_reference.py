#!/usr/bin/env python3
"""법정동코드 정본 로더 (v3 P4-4 · D-07) — 버전·해시를 고정한 공식 기준표.

정본: 행정안전부 법정동코드 API 전체 스냅샷(reference/bjd_code_api_*.csv, scripts/bjd_reference_snapshot.py 로 받는다).
      현존 코드만 담기므로, 폐지 이력은 국토교통부 파일(reference/bjd_code_20260813.csv)로 보조한다.

계층을 찾는 방법
  시도   = 상위 코드(locathigh_cd)를 따라 올라간 최상위 행. 세종은 3611000000 이 최상위다(3600000000 행 없음)
  시군구 = code[:5] + "00000"   (세종은 최상위 행과 같다)
  읍면동 = code[:8] + "00"
시+구 도시(성남시 분당구 등)는 API 상위 코드가 시를 건너뛰어 도로 간다 — 이름을 풀 때는 상위 코드가 아니라
전체 이름의 접두로 계산한다(leaf). API 에 상위 코드가 빈 행이 있어(2026-09-26 스냅샷 2행) 그때는 코드 자리로 찾는다.

파일이 고정 해시와 다르면 즉시 멈춘다 — 기준표가 바뀐 채로 조용히 판정하면 근거의 버전이 거짓이 된다.
출처·갱신 절차: reference/README.md
"""
import csv
import hashlib
import io
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

REFERENCE_FILE = ROOT / "reference" / "bjd_code_api_20260926.csv"
REFERENCE_VERSION = "mois-stanregin-api-20260926"
REFERENCE_SHA256 = "4333ed7bdb62ed7132e02650936877b848b7fd3168bbb00264534d0694130cb5"

# 폐지 이력 보조 — 현존 기준표에 없는 코드가 "원래 없던 코드"인지 "폐지된 코드"인지 가른다
HISTORY_FILE = ROOT / "reference" / "bjd_code_20260813.csv"
HISTORY_VERSION = "molit-bjd-20260813"
HISTORY_SHA256 = "d1062860dd739c8fe6da23f110b08b0147d909ec2430f6933586e0014a0379f3"

ROOT_PARENT = "0000000000"


@dataclass(frozen=True)
class Entry:
    code: str
    full_name: str
    exists: bool
    parent: str | None = None


class BjdReference:
    def __init__(self, entries, history=None):
        self._by_code = {e.code: e for e in entries}
        self._history = history or {}

    def __len__(self):
        return len(self._by_code)

    def get(self, code):
        return self._by_code.get(code)

    def history_status(self, code):
        """현존 기준표에 없는 코드의 이력 — '폐지' / '존재'(당시엔 있었으나 지금 현존 목록엔 없음) / None"""
        return self._history.get(code)

    def root_of(self, code):
        """상위 코드를 따라 올라간 최상위(시도) 행. 상위 코드가 비었거나 끊기면 코드 자리로 시도한다."""
        seen, entry = set(), self.get(code)
        while entry is not None and entry.code not in seen:
            seen.add(entry.code)
            parent = entry.parent
            if parent == ROOT_PARENT:
                return entry
            nxt = self.get(parent) if parent else None
            if nxt is None:
                nxt = self._structural_parent(entry.code)
            entry = nxt
        return None

    def _structural_parent(self, code):
        for candidate in (code[:8] + "00", code[:5] + "00000", code[:2] + "0" * 8):
            if candidate != code and candidate in self._by_code:
                return self._by_code[candidate]
        return None

    @staticmethod
    def sigungu_code(code):
        return code[:5] + "0" * 5

    @staticmethod
    def eupmyeondong_code(code):
        return code[:8] + "00"

    @staticmethod
    def has_ri(code):
        return code[8:] != "00"

    def leaf(self, code, parent_code):
        """부모 전체 이름을 떼어 낸 이 단계의 이름 — 부모가 없거나 접두가 맞지 않으면 None."""
        entry, parent = self.get(code), self.get(parent_code)
        if entry is None or parent is None:
            return None
        if entry.full_name == parent.full_name:
            return ""   # 세종 시군구처럼 한 단계가 비어 있는 경우
        prefix = parent.full_name + " "
        return entry.full_name[len(prefix):] if entry.full_name.startswith(prefix) else None


def _verified_bytes(path, expected_sha256):
    raw = Path(path).read_bytes()
    actual = hashlib.sha256(raw).hexdigest()
    if expected_sha256 and actual != expected_sha256:
        raise SystemExit(f"법정동 기준표 해시 불일치 — {Path(path).name}: {actual} (기대 {expected_sha256}). "
                         "기준표를 바꿨다면 버전·sha256 을 함께 올려라")
    return raw


def load_history(path=HISTORY_FILE, expected_sha256=HISTORY_SHA256):
    raw = _verified_bytes(path, expected_sha256)
    reader = csv.reader(io.StringIO(raw.decode("cp949")))
    header = next(reader)
    if header[:3] != ["법정동코드", "법정동명", "폐지여부"]:
        raise SystemExit(f"폐지 이력 파일 헤더가 예상과 다르다: {header}")
    return {row[0].strip(): row[2].strip() for row in reader}


def load(path=REFERENCE_FILE, expected_sha256=REFERENCE_SHA256, with_history=True):
    raw = _verified_bytes(path, expected_sha256)
    reader = csv.DictReader(io.StringIO(raw.decode("utf-8")))
    entries = []
    for row in reader:
        code = row["region_cd"].strip()
        if len(code) != 10 or not code.isdigit():
            raise SystemExit(f"법정동 기준표 형식 이상 행: {row}")
        parent = (row["locathigh_cd"] or "").strip() or None
        entries.append(Entry(code, " ".join(row["locatadd_nm"].split()), True, parent))
    return BjdReference(entries, load_history() if with_history else None)


if __name__ == "__main__":
    ref = load()
    print(f"{REFERENCE_VERSION}: {len(ref):,}행 (폐지 이력 {HISTORY_VERSION})")
