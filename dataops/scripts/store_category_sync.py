"""BE StoreCategory.java → scripts/store_category_map.py 생성.

가게 분류(store.category)의 값은 BE StoreCategory enum 의 이름이고, 네이버·카카오 원문 → 코드 매핑도 그 enum 안에 있다(BE #730).
정본은 BE 하나여야 한다 — dataops 에 손으로 복사본을 두면 네이버 표기가 바뀔 때 두 곳을 고치게 된다. 그래서 BE 파일을 읽어
Python 표를 *생성*하고, 생성물에 BE 커밋을 박아 둔다. 표기가 바뀌면 BE enum 을 고치고 이 스크립트를 다시 돌린다.

BE fromExternalName() 과 같은 규칙으로 category_of() 를 만든다:
  - 값이 비어 있으면 None(미수집), 카카오 경로("음식점 > 양식 > 베이커리")는 잎에서 뿌리 방향으로 훑고, 어디에도 없으면 OTHER.
  - 네이버 문자열의 쉼표("떡,한과")는 이름의 일부라 쪼개지 않는다.

사용
    python scripts/store_category_sync.py                                  # ../bbang-ggut_BE-axis 의 origin/dev
    python scripts/store_category_sync.py --ref origin/develop-v2
"""
import argparse
import datetime as dt
import os
import re
import subprocess

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BE_PATH = "core/src/main/java/sweetgirl/croissant/store/enums/StoreCategory.java"
OUT = os.path.join(REPO_ROOT, "scripts", "store_category_map.py")


def read_enum(be_repo, ref):
    src = subprocess.run(["git", "-C", be_repo, "show", f"{ref}:{BE_PATH}"], capture_output=True, check=True).stdout.decode("utf-8")
    commit = subprocess.run(["git", "-C", be_repo, "rev-parse", "--short", ref], capture_output=True, text=True, check=True).stdout.strip()
    body = src.split("public enum StoreCategory {", 1)[1].split("private static final", 1)[0]
    body = re.sub(r"/\*.*?\*/", " ", body, flags=re.S)          # /* 카카오 */ 같은 표시 주석
    body = re.sub(r"//[^\n]*", " ", body)
    codes, mapping = [], {}
    for name, args in re.findall(r"\b([A-Z][A-Z_]+)\((.*?)\)\s*[,;]", body, flags=re.S):
        codes.append(name)
        for s in re.findall(r"\"([^\"]*)\"", args):
            if s in mapping:
                raise SystemExit(f"외부 카테고리 '{s}' 가 {mapping[s]} 와 {name} 에 겹친다 — BE enum 이 이걸 허용하지 않으니 파싱이 틀렸을 것")
            mapping[s] = name
    if "OTHER" not in codes or len(codes) < 10:
        raise SystemExit(f"enum 파싱 이상 — 코드 {codes}")
    return commit, codes, mapping


def render(commit, codes, mapping, be_repo, ref):
    lines = [
        '"""생성 파일 — 손으로 고치지 말 것. scripts/store_category_sync.py 가 BE StoreCategory.java 에서 만든다.',
        "",
        f"BE {be_repo} {ref} @ {commit} · {dt.date.today().isoformat()} · 코드 {len(codes)}종 · 외부 문자열 {len(mapping)}개",
        '"""',
        f'BE_COMMIT = "{commit}"',
        f"CODES = {codes!r}",
        "EXTERNAL_TO_CODE = {",
    ]
    for code in codes:
        names = [n for n, c in mapping.items() if c == code]
        if names:
            lines.append(f"    # {code}")
            lines += [f"    {n!r}: {code!r}," for n in names]
    lines += [
        "}",
        "",
        "",
        "def category_of(raw):",
        '    """BE StoreCategory.fromExternalName 과 같은 규칙. 비어 있으면 None(미수집), 매핑 없으면 OTHER."""',
        "    if raw is None or not raw.strip():",
        "        return None",
        '    for node in reversed(raw.split(">")):',
        "        code = EXTERNAL_TO_CODE.get(node.strip())",
        "        if code:",
        "            return code",
        '    return "OTHER"',
        "",
    ]
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--be-repo", default=os.path.join(REPO_ROOT, ".."))
    ap.add_argument("--ref", default="origin/develop-v2")
    args = ap.parse_args()
    commit, codes, mapping = read_enum(args.be_repo, args.ref)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(render(commit, codes, mapping, os.path.basename(os.path.abspath(args.be_repo)), args.ref))
    print(f"[category-sync] {OUT} — BE {args.ref}@{commit} · 코드 {len(codes)}종 · 외부 문자열 {len(mapping)}개")


if __name__ == "__main__":
    main()
