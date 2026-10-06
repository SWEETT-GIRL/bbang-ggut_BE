"""앱(FE) 타입 검사(tsc) 결과를 PR 에 붙일 한글 요약으로 바꾼다.

사용: python3 fe_contract_summary.py <tsc 출력 파일> <요약 마크다운 출력 파일> [FE contract.ts 경로]
- contract.ts 의 "Type '"address"' does not satisfy the constraint 'never'" 는 서버 응답에서 빠진 필드로 정리한다
- 그 밖의 tsc 에러는 이 BE 변경과 무관할 수 있어 따로 적는다
종료 코드: 에러가 있으면 1
"""
import re
import sys

CONTRACT_ERROR = re.compile(r"contract\.ts\((\d+),\d+\): error TS2344: Type '\"([^\"]+)\"' does not satisfy the constraint 'never'")
ANY_ERROR = re.compile(r"^(\S+\.tsx?)\((\d+),\d+\): error (TS\d+): (.*)$")
SERVER_SCHEMA = re.compile(r"Server\[['\"](\w+)['\"]\]")


def main(tsc_output_path, summary_path, contract_path=None):
    with open(tsc_output_path, encoding="utf-8") as tsc_output:
        lines = tsc_output.read().splitlines()
    contract_lines = read_lines(contract_path)

    missing_fields = []
    other_errors = []
    for line in lines:
        contract = CONTRACT_ERROR.search(line)
        if contract:
            line_number = int(contract.group(1))
            missing_fields.append((line_number, contract.group(2), schema_near(contract_lines, line_number)))
            continue
        error = ANY_ERROR.match(line)
        if error:
            other_errors.append(error.groups())

    if not missing_fields and not other_errors:
        summary = "## ✅ 앱(FE) 타입 검사 통과\n\n이 BE 변경으로 앱이 읽는 응답 필드가 빠지지 않았어요.\n"
        write(summary_path, summary)
        return 0

    parts = []
    if missing_fields:
        parts.append("## ⚠️ 이 BE 변경으로 앱(FE)이 읽는 응답 필드가 사라져요\n")
        parts.append("| 서버 응답 타입 | 빠진 필드 | 검사 위치 (FE `src/services/api/contract.ts`) |")
        parts.append("|---|---|---|")
        for line_number, field, schema in missing_fields:
            parts.append(f"| `{schema}` | `{field}` | {line_number}줄 |")
        parts.append("")
        parts.append("- 실수로 지웠다면 → BE 변경을 되돌려 주세요")
        parts.append("- 정말 지워야 한다면 → FE 에서 그 필드를 안 쓰도록 먼저 고쳐 배포한 뒤 다시 실행하세요 "
                     "(CLAUDE.md \"API 응답 호환\")")
        parts.append("")
    if other_errors:
        parts.append("## 그 밖의 FE 타입 에러\n")
        parts.append("이 BE 변경과 무관할 수 있어요 (FE 브랜치 자체의 에러인지 확인해 주세요).\n")
        parts.append("| 파일 | 줄 | 코드 | 내용 |")
        parts.append("|---|---|---|---|")
        for path, line_number, code, message in other_errors[:20]:
            parts.append(f"| `{path}` | {line_number} | {code} | {message.replace('|', '/')} |")
        if len(other_errors) > 20:
            parts.append(f"\n외 {len(other_errors) - 20}건 — Actions 로그를 확인하세요")
        parts.append("")
    write(summary_path, "\n".join(parts))
    return 1


def read_lines(path):
    if not path:
        return []
    try:
        with open(path, encoding="utf-8") as source:
            return source.read().splitlines()
    except OSError:
        return []


def schema_near(contract_lines, line_number):
    """에러 줄부터 몇 줄 안에서 Server['...'] 를 찾는다 — 검사 한 건이 여러 줄로 감싸여 있을 수 있다"""
    for line in contract_lines[line_number - 1:line_number + 6]:
        found = SERVER_SCHEMA.search(line)
        if found:
            return found.group(1)
    return "?"


def write(path, text):
    with open(path, "w", encoding="utf-8") as output:
        output.write(text)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None))
