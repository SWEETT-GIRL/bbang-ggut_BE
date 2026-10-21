"""GitHub review transport with explicit publication and read-only merge inspection.

The publisher accepts an already prepared head. It never runs git checkout,
executes repository scripts, merges PRs or creates an approval. Unknown delivery
is reconciled by a deterministic marker without re-running the model.
"""
import base64
from dataclasses import dataclass
from hashlib import sha256
import json
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import quote

from .approval import (ApprovalHold, PublicationBinding, PullRequestSnapshot, ReviewManifest,
                       COMMIT, REPO, canonical, code_tree_digest)


class DeliveryUnknown(RuntimeError):
    pass


BRANCH = re.compile(r"(?:feat|fix|docs)/[a-z0-9][a-z0-9._/-]{0,120}\Z")
BASE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/-]{0,127}\Z")
MARKER = re.compile(r"<!-- response-agent:[0-9a-f]{64} -->\Z")
COMMIT_STYLE = re.compile(r"(?:✨ feat|🐛 fix|📝 docs|🧪 test|♻️ refactor|⚡ perf|🔧 chore): [^\n]*[가-힣][^\n]*\Z")


def _guard(value, code):
    if not value: raise ApprovalHold(code)


class GhTransport:
    def __call__(self, arguments):
        # All callers use fixed command templates, never shell text from a response.
        _guard(arguments[:1] == ["api"] or arguments[:2] in (["pr", "create"], ["pr", "edit"]), "GITHUB_COMMAND_FORBIDDEN")
        try:
            result = subprocess.run(["gh", *arguments], capture_output=True, text=True,
                                    timeout=45, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise DeliveryUnknown("GITHUB_TRANSPORT_UNKNOWN") from None
        if result.returncode != 0:
            # stderr may include access details. Retain only a public code.
            raise DeliveryUnknown("GITHUB_TRANSPORT_UNKNOWN")
        if len(result.stdout.encode()) > 16 * 1024 * 1024:
            raise DeliveryUnknown("GITHUB_RESPONSE_LIMIT")
        return result.stdout


@dataclass(frozen=True)
class PreparedReview:
    manifest: ReviewManifest
    head_branch: str
    head_sha: str
    base_branch: str
    title: str
    body: str

    def __post_init__(self):
        _guard(isinstance(self.head_branch,str) and BRANCH.fullmatch(self.head_branch)
               and ".." not in self.head_branch and "//" not in self.head_branch and not self.head_branch.endswith("/"), "BRANCH_CONVENTION")
        _guard(isinstance(self.base_branch,str) and BASE.fullmatch(self.base_branch)
               and ".." not in self.base_branch and "//" not in self.base_branch, "BASE_BRANCH")
        _guard(isinstance(self.head_sha,str) and COMMIT.fullmatch(self.head_sha), "HEAD_SHA")
        _guard(isinstance(self.title,str) and re.fullmatch(r"\[(?:FEAT|FIX|DOCS)\] [^\n]{1,180}",self.title)
               and re.search(r"[가-힣]",self.title), "PR_TITLE_CONVENTION")
        _guard(isinstance(self.body,str) and len(self.body.encode()) <= 48 * 1024
               and all(section in self.body for section in
                       ("## 💡 어떤 작업을 하셨나요?", "## 📝 관련 이슈", "## 🔍 리뷰어에게"))
               and "<!-- response-agent:" not in self.body, "PR_BODY_CONVENTION")
        _guard(self.body == render_review_body(self.manifest), "PR_BODY_MUST_USE_TRUSTED_RENDERER")


def render_review_body(manifest):
    """Fixed host summary. Detailed bytes are reviewed in the digest-bound files."""
    doc = manifest.document
    action = {
        "PARSER_REPAIR": "이 PR 병합은 고정 artifact의 배포·canary·저장 원본 재처리·변경 제안을 승인합니다. 실제 가게 정보 쓰기는 별도 data-only PR 승인이 필요합니다.",
        "DATA_APPLY": "이 PR 병합은 첨부한 정확한 대상과 전후 값만 기존 조건부 갱신 경로로 반영하도록 승인합니다. 코드 배포나 대상 확대는 허용하지 않습니다.",
        "REPORT_ONLY": "보류된 사건의 설명서입니다. 이 PR을 병합해도 배포·재처리·운영 데이터 쓰기는 실행되지 않습니다.",
    }[doc["kind"]]
    return ("## 💡 어떤 작업을 하셨나요?\n\n외부 응답 변경에 대한 검수 자료를 제공합니다.\n\n"
            f"- 사건: `{doc['incident_id']}`\n- 종류: `{doc['kind']}`\n- 검수 manifest: `{manifest.digest}`\n"
            f"- 고정 대상: {len(doc['targets'])}건\n- 만료: `{doc['expires_at']}`\n\n"
            "## 📝 관련 이슈\n\n- 해당 없음\n\n## 🔍 리뷰어에게\n\n" + action +
            "\n\n독립 검증·모델 생성 테스트·원본 재처리·실제 운영 반영 결과는 첨부 설명서에서 구분합니다.\n")


class GitHubAdapter:
    def __init__(self, *, allowed_repository, workspace, transport=None, origin_url=None):
        _guard(isinstance(allowed_repository,str) and REPO.fullmatch(allowed_repository), "GITHUB_REPOSITORY")
        self.repository = allowed_repository
        self.workspace = Path(workspace).resolve()
        self.transport = transport or GhTransport()
        if origin_url is None:
            try:
                result = subprocess.run(["git","-C",str(self.workspace),"remote","get-url","origin"],
                                        capture_output=True,text=True,timeout=5,check=False)
                _guard(result.returncode == 0,"ORIGIN_UNVERIFIED")
                origin_url = result.stdout.strip()
            except (OSError,subprocess.TimeoutExpired):
                raise ApprovalHold("ORIGIN_UNVERIFIED") from None
        _guard(origin_url in {f"https://github.com/{allowed_repository}.git",f"https://github.com/{allowed_repository}",
                             f"git@github.com:{allowed_repository}.git"}, "ORIGIN_REPOSITORY_MISMATCH")

    def _api(self, suffix):
        response = self.transport(["api",f"repos/{self.repository}/{suffix}"])
        try: return json.loads(response)
        except (TypeError,ValueError): raise DeliveryUnknown("GITHUB_RESPONSE_INVALID") from None

    def _blob_digest(self, sha):
        _guard(isinstance(sha,str) and COMMIT.fullmatch(sha),"GITHUB_BLOB_ID")
        blob = self._api("git/blobs/"+sha)
        try:
            _guard(blob["encoding"] == "base64" and blob["size"] <= 1024*1024,"GITHUB_FILE_LIMIT")
            raw = base64.b64decode(blob["content"],validate=False)
            _guard(len(raw) == blob["size"],"GITHUB_FILE_SIZE")
        except (KeyError,TypeError,ValueError): raise DeliveryUnknown("GITHUB_BLOB_INVALID") from None
        return sha256(raw).hexdigest()

    def _tree(self, commit_sha):
        _guard(isinstance(commit_sha,str) and COMMIT.fullmatch(commit_sha),"GITHUB_COMMIT_ID")
        tree = self._api(f"git/trees/{commit_sha}?recursive=1")
        _guard(tree.get("truncated") is False and isinstance(tree.get("tree"),list),"GITHUB_TREE_TRUNCATED")
        entries = {}
        for entry in tree["tree"]:
            if entry.get("type") == "tree": continue
            _guard(entry.get("type") == "blob" and entry.get("path") not in entries,"GITHUB_TREE_UNSUPPORTED")
            entries[entry["path"]] = {"sha":entry["sha"],"mode":entry["mode"]}
        return entries

    def _files_at(self, entries, paths):
        _guard(all(path in entries for path in paths),"GITHUB_FILE_MISSING")
        return {path:self._blob_digest(entries[path]["sha"]) for path in sorted(paths)}

    def _pr_files(self, number):
        paths = []
        for page in range(1,11):
            rows = self._api(f"pulls/{number}/files?per_page=100&page={page}")
            _guard(isinstance(rows,list),"GITHUB_FILES_UNVERIFIED")
            for row in rows:
                _guard(row.get("status") in {"modified","added"},"GITHUB_FILE_OPERATION")
                paths.append(row["filename"])
            if len(rows) < 100:
                _guard(len(paths) == len(set(paths)),"GITHUB_FILES_DUPLICATED")
                return paths
        raise ApprovalHold("GITHUB_FILES_TRUNCATED")

    def read_snapshot(self, binding, manifest):
        _guard(binding.repository == self.repository and binding.manifest_digest == manifest.digest,"PR_BINDING")
        pr = self._api(f"pulls/{binding.pr_number}")
        _guard(pr.get("number") == binding.pr_number and pr["base"]["repo"]["full_name"] == self.repository,"PR_REPOSITORY_MISMATCH")
        head = pr["head"]["sha"]
        entries = self._tree(head)
        paths = self._pr_files(binding.pr_number)
        files = self._files_at(entries,paths)
        merged = pr.get("merged") is True
        merge_sha = pr.get("merge_commit_sha") if merged else None
        merged_entries = self._tree(merge_sha) if merged else None
        merged_files = self._files_at(merged_entries,paths) if merged else None
        # Detect a concurrent force-push/merge while files and trees were inspected.
        latest = self._api(f"pulls/{binding.pr_number}")
        _guard((latest["head"]["sha"],latest.get("merged"),latest.get("merge_commit_sha")) ==
               (head,pr.get("merged"),pr.get("merge_commit_sha")),"PR_CHANGED_DURING_READ")
        return PullRequestSnapshot(self.repository,binding.pr_number,head,merged,merge_sha,
            (pr.get("merged_by") or {}).get("id"),pr.get("merged_at"),pr.get("draft") is True,
            canonical(files),code_tree_digest(entries,manifest.document["review_id"]),
            code_tree_digest(merged_entries,manifest.document["review_id"]) if merged else None,
            (pr["head"].get("repo") or {}).get("full_name",""),
            canonical(merged_files) if merged else None)

    def validate_prepared_head(self, request):
        doc = request.manifest.document
        ref = self._api("git/ref/heads/"+quote(request.head_branch,safe=""))
        _guard(ref["object"]["sha"] == request.head_sha,"PREPARED_HEAD_CHANGED")
        comparison = self._api(f"compare/{quote(request.base_branch,safe='')}...{request.head_sha}")
        _guard(comparison.get("base_commit",{}).get("sha") == doc["base_commit"],"PREPARED_BASE_CHANGED")
        rows = comparison.get("files",[])
        _guard(isinstance(rows,list) and 0 < len(rows) < 300,"PREPARED_DIFF_UNVERIFIED")
        _guard(all(row.get("status") in {"modified","added"} for row in rows),"PREPARED_FILE_OPERATION")
        paths = [row["filename"] for row in rows]
        _guard(len(paths) == len(set(paths)) and set(paths) == set(doc["files"]),"PREPARED_FILE_ALLOWLIST")
        commits = comparison.get("commits",[])
        _guard(0 < len(commits) == comparison.get("total_commits") <= 100,"PREPARED_COMMITS_UNVERIFIED")
        _guard(all(COMMIT_STYLE.fullmatch(c["commit"]["message"].split("\n",1)[0]) for c in commits),"COMMIT_CONVENTION")
        entries = self._tree(request.head_sha)
        _guard(self._files_at(entries,paths) == doc["files"],"PREPARED_CONTENT_CHANGED")
        if doc["kind"] != "REPORT_ONLY":
            _guard(code_tree_digest(entries,doc["review_id"]) == doc["candidate_tree_digest"],"PREPARED_TREE_CHANGED")
        return True

    def find_marker(self, request, marker):
        _guard(MARKER.fullmatch(marker),"OUTBOX_MARKER")
        matches = []
        owner = self.repository.split("/",1)[0]
        for page in range(1,11):
            rows = self._api(f"pulls?state=all&head={quote(owner+':'+request.head_branch,safe='')}&per_page=100&page={page}")
            _guard(isinstance(rows,list),"GITHUB_RECONCILIATION_INVALID")
            for row in rows:
                if marker in (row.get("body") or ""):
                    _guard(row["head"]["sha"] == request.head_sha
                           and row["base"]["repo"]["full_name"] == self.repository,"DELIVERY_BINDING_CHANGED")
                    matches.append(row)
            if len(rows) < 100: break
        else: raise DeliveryUnknown("GITHUB_RECONCILIATION_TRUNCATED")
        if len(matches) > 1: raise DeliveryUnknown("GITHUB_DUPLICATE_MARKER")
        return self._reference(matches[0]) if matches else None

    def _reference(self, pr):
        return {"repository":self.repository,"pr_number":pr["number"],"head_sha":pr["head"]["sha"],
                "url":f"https://github.com/{self.repository}/pull/{pr['number']}"}

    def publish(self, request, marker, *, reconciliation_only=False, update_pr_number=None, before_write=None):
        _guard(MARKER.fullmatch(marker),"OUTBOX_MARKER")
        existing = self.find_marker(request,marker)
        if existing is not None: return existing
        if reconciliation_only: raise DeliveryUnknown("DELIVERY_NOT_RECONCILED")
        self.validate_prepared_head(request)
        # A host-generated scope notice is always appended; arbitrary model prose is not authority.
        body = render_review_body(request.manifest) + "\n" + marker + "\n"
        with tempfile.TemporaryDirectory(prefix="response-agent-pr-") as directory:
            body_path = Path(directory)/"body.md"
            body_path.write_text(body,encoding="utf-8")
            body_path.chmod(0o600)
            if update_pr_number is None:
                args = ["pr","create","--repo",self.repository,"--head",request.head_branch,"--base",request.base_branch,
                        "--title",request.title,"--body-file",str(body_path)]
                if request.manifest.document["kind"] == "REPORT_ONLY": args.append("--draft")
            else:
                _guard(isinstance(update_pr_number,int) and not isinstance(update_pr_number,bool) and update_pr_number > 0,"PR_NUMBER")
                existing_pr = self._api(f"pulls/{update_pr_number}")
                _guard(existing_pr["head"]["sha"] == request.head_sha and existing_pr.get("state") == "open"
                       and existing_pr["head"]["ref"] == request.head_branch
                       and existing_pr["base"]["repo"]["full_name"] == self.repository,"UPDATE_PR_BINDING")
                _guard(request.manifest.document["kind"] != "REPORT_ONLY" or existing_pr.get("draft") is True,"REPORT_MUST_BE_DRAFT")
                args = ["pr","edit",str(update_pr_number),"--repo",self.repository,"--title",request.title,"--body-file",str(body_path)]
            if before_write is not None: before_write()
            self.transport(args)
        reference = self.find_marker(request,marker)
        if reference is None: raise DeliveryUnknown("DELIVERY_NOT_RECONCILED")
        return reference


def deliver_claim(store, adapter, claim):
    """A delivery retry only reads stored artifacts; it has no model/runtime callback."""
    try:
        payload = claim.payload
        manifest = ReviewManifest(payload["manifest"])
        _guard(manifest.digest == claim.manifest_digest,"OUTBOX_MANIFEST_CHANGED")
        request = PreparedReview(manifest,**payload["prepared"])
        reference = adapter.publish(request,claim.marker,reconciliation_only=claim.reconciliation_required,
                                    update_pr_number=payload.get("update_pr_number"),before_write=lambda:store.renew(claim))
        publication = PublicationBinding(reference["repository"],reference["pr_number"],reference["head_sha"],manifest.digest)
        store.finish(claim,provider_reference=reference,publication_binding=publication)
        return reference
    except (DeliveryUnknown,ApprovalHold) as error:
        store.finish(claim,error_code=str(error))
        return None


def render_terminal_review(report, sanitized_case, structure_diff):
    """Deterministic public explanation from host evidence, never model-authored prose.

    Raw fixtures, IDs, original response text, tool arguments/results, patch
    reasoning and model decision strings are deliberately not rendered. Unknown
    summary fields are discarded. Structure rows accept only bounded normalized
    menu paths and known shape enums; malformed rows fail closed.
    """
    from decimal import Decimal, InvalidOperation
    from .approval import content_digest
    _guard(report.get("status") in {"AWAITING_REVIEW","HELD","ERROR"},"REVIEW_REPORT_STATE")
    _guard(isinstance(sanitized_case,dict) and isinstance(structure_diff,list) and len(structure_diff)<=32,"REVIEW_INPUT")
    shapes = {"MISSING","NULL","OBJECT","ARRAY","STRING","NUMBER","BOOLEAN","UNKNOWN"}
    paths = {"menus", "menus.items", "menus[].__typename", "menus[].price", "menus[].images",
             "menus[].images[]", "menus[].name", "menus.total", "newBusinessHours", "businessHours[].day"}
    rows = []
    for row in structure_diff:
        required={"path","before_type","after_type"}
        aliases={"before_typename","after_typename"}
        _guard(isinstance(row,dict) and set(row) in (required,required|aliases)
               and row["path"] in paths and row["before_type"] in shapes and row["after_type"] in shapes,
               "REVIEW_STRUCTURE_DIFF")
        if aliases <= set(row):
            _guard(row["path"] == "menus[].__typename" and isinstance(sanitized_case.get("recipe"),str)
                   and sanitized_case["recipe"] in {"complete-fixed-won-menu-entity-v1"}
                   and all(isinstance(row[key],str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}",row[key]) for key in aliases),
                   "REVIEW_ALIAS_PROVENANCE")
        rows.append(dict(row))
    confirmed = reserved = Decimal(0)
    requests = report.get("requests",[])
    _guard(isinstance(requests,list) and len(requests)<=100,"REVIEW_REQUEST_JOURNAL")
    for request in requests:
        try:
            amount = Decimal(str(request.get("cost_usd",request.get("reserved_usd"))))
            _guard(amount.is_finite() and amount>=0,"REVIEW_COST")
        except (InvalidOperation,TypeError): raise ApprovalHold("REVIEW_COST") from None
        if "cost_usd" in request: confirmed+=amount
        else: reserved+=amount
    def case_counts(value):
        if not isinstance(value,list): return None
        return {"count":len(value),"passed":sum(row.get("passed") is True for row in value if isinstance(row,dict))}
    checks = []
    for entry in report.get("validations",[]):
        result = entry.get("result",{})
        candidate = result.get("candidate",{})
        regression = candidate.get("regression",{})
        checks.append({"final":entry.get("final") is True,"passed":result.get("passed") is True,
                       "result_digest":content_digest(result),
                       "independent_cases":case_counts(result.get("cases")),
                       "generated_cases":case_counts(result.get("proposed_cases")),
                       "regression_found":regression.get("found") if isinstance(regression.get("found"),int) else None})
    evidence = {"schema_version":1,"status":report["status"],"case_digest":content_digest(sanitized_case),
                "execution_report_digest":content_digest(report),"structure_diff":rows,"validation":checks,
                "patch_count":len(report.get("patches",[])),"model_requests":len(requests),
                "confirmed_estimated_usd":str(confirmed),"reserved_usd":str(reserved),
                "deployment_executed":False,"operating_reprocess_executed":False,"serving_applied":0}
    # Only trusted structural fields/counters/digests reach the explanation.
    details=json.dumps(evidence,ensure_ascii=False,sort_keys=True,indent=2)
    alias_lines = "".join(f"지원 엔티티 `{row['before_typename']}` → 새 엔티티 `{row['after_typename']}`\n\n"
                          for row in rows if "before_typename" in row)
    return ("# 외부 응답 구조 변경 검토\n\n" + alias_lines +
            "아래 결과는 저장된 실행 근거에서 생성했습니다. 모델이 쓴 설명이나 생성 테스트 통과만으로 반영을 승인하지 않습니다.\n\n"
            "가격·대상 의미가 불명확하거나 검증이 실패하면 보류합니다. 파서 PR과 실제 데이터 PR의 승인은 분리합니다.\n\n"
            "```json\n"+details+"\n```\n\n"
            "배포·저장 원본 재처리·운영 반영은 이 분석 보고서의 성과로 집계하지 않았습니다.\n")
