"""Run the actual reusable workflow script with a fake transport; no Slack calls."""
import json
from pathlib import Path
import subprocess
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[4]


class SlackWorkflowTest(unittest.TestCase):
    def test_pr_metadata_keeps_deployment_gate_closed_for_unrelated_commits(self):
        harness = r'''
const assert = require('node:assert/strict');
const execute = new (Object.getPrototypeOf(async function(){}).constructor)('core','context','github', SCRIPT);
const context = {repo:{owner:'test',repo:'repo'},sha:'a'.repeat(40),payload:{repository:{full_name:'test/repo'}}};
const matched = {merged_at:'2026-11-05',base:{ref:'develop-v2',repo:{full_name:'test/repo'}},
  merge_commit_sha:context.sha,number:1019,title:'알림 카드 수정'};
async function run(prs,files=[{filename:'api/example.java'}]) {
  const outputs={};
  const core={setOutput:(key,value)=>outputs[key]=value,warning:()=>{},notice:()=>{}};
  const github={rest:{repos:{listPullRequestsAssociatedWithCommit:'prs'},pulls:{listFiles:'files'}},
    paginate:async method=>method==='prs'?prs:files};
  await execute(core,context,github);
  return outputs;
}
(async()=>{
  const allowed=await run([matched]);
  assert.equal(allowed.allowed,'true');assert.equal(allowed.pr_number,1019);assert.equal(allowed.pr_title,matched.title);
  for(const prs of [[],[{...matched,merged_at:null}],[{...matched,merge_commit_sha:'other'}],
    [{...matched,base:{ref:'main',repo:{full_name:'test/repo'}}}],
    [{...matched,base:{ref:'develop-v2',repo:{full_name:'test/other'}}}]]) {
    const denied=await run(prs);assert.equal(denied.allowed,'false');assert.equal(denied.pr_number,'');
  }
  if(IS_V2) assert.equal((await run([matched],[{filename:'infra/redis/compose.yml'}])).allowed,'false');
})().catch(error=>{console.error(error);process.exitCode=1});
'''
        for filename in ('cd-batch.yml', 'cd-v2.yml'):
            with self.subTest(workflow=filename):
                workflow = (ROOT / '.github/workflows' / filename).read_text()
                script = textwrap.dedent(workflow.split('          script: |\n', 1)[1].split('\n  build:', 1)[0])
                source = harness.replace('SCRIPT', json.dumps(script)).replace('IS_V2', json.dumps(filename == 'cd-v2.yml'))
                subprocess.run(['node', '-e', source], check=True, timeout=15)

    def test_workflow_outcomes_and_secret_safe_transport_failures(self):
        workflow = (ROOT / '.github/workflows/slack-notify.yml').read_text()
        script = textwrap.dedent(workflow.split('          script: |\n', 1)[1])
        harness = r'''
const assert = require('node:assert/strict');
const execute = new (Object.getPrototypeOf(async function(){}).constructor)('core','context','fetch', SCRIPT);
const context = {repo:{owner:'test',repo:'repo'},sha:'a'.repeat(40),ref:'refs/heads/develop-v2',runId:42,
  payload:{pull_request:{number:7,title:'알림 <수정> & 정리',head:{ref:'feature/<!channel>"$()',sha:'b'.repeat(40)},base:{ref:'develop-v2'}}}};
const secret = 'https://hooks.slack.com/services/TEST/TEST/never-send';
async function run(results, state='', mode='ok', webhook=secret, kind='CD') {
  const logs=[], sent=[];
  const core={notice:s=>logs.push(s),warning:s=>logs.push(s),summary:{addRaw(s){logs.push(s);return this},async write(){}}};
  Object.assign(process.env, {SLACK_WEBHOOK_URL:webhook,NOTIFY_COMPONENT:'DataOps 배치',NOTIFY_KIND:kind,
    NOTIFY_RESULTS:JSON.stringify(results),DEPLOYMENT_STATE:state,GITHUB_SERVER_URL:'https://github.com',GITHUB_RUN_ATTEMPT:'2'});
  await execute(core,context,async (url,options)=>{
    sent.push({url,options,body:JSON.parse(options.body)});
    if(mode==='throw') throw new Error(secret);
    return {ok:mode!=='reject',text:async()=>mode==='reject'?secret:'ok'};
  });
  assert(!logs.join('\n').includes(secret));
  return {logs,sent};
}
const card = r => r.sent[0].body.attachments[0];
const content = r => JSON.stringify(card(r).blocks);
(async()=>{
  const success={build:{result:'success'},deploy:{result:'success'}};
  for(const [state,expected] of [['prepared','실행 중 서비스 검증 없음'],['refreshed','ASG 인스턴스 교체 완료'],['installed','배치 이미지 설치 완료']]) {
    const r=await run(success,state); assert.equal(r.sent.length,1); assert(content(r).includes(expected));
    assert.equal(r.sent[0].options.redirect,'error'); assert.equal(card(r).blocks[0].type,'header');
    assert.equal(card(r).color,state==='prepared'?'#F2C744':'#2ECC71');
    assert(content(r).includes('feature/&lt;!channel&gt;'));
    assert(content(r).includes('알림 &lt;수정&gt; &amp; 정리'));
    assert(!content(r).includes('<!channel>'));
    assert(content(r).includes('bbbbbbbbbbbb'));
    assert.equal(card(r).blocks.find(b=>b.type==='actions').elements[0].url,'https://github.com/test/repo/actions/runs/42');
    for(const field of card(r).blocks.flatMap(b=>b.fields||[])) {assert(field.verbatim);assert(field.text.length<=2000)}
    if(state==='prepared') assert(!card(r).fallback.includes('배포 완료'));
  }
  const failure=await run({build:{result:'failure'},deploy:{result:'skipped'}},'installed');
  assert(card(failure).fallback.includes('실패')); assert(!card(failure).fallback.includes('설치 완료'));
  assert(content(failure).includes('*실패 단계*\\nbuild'));
  assert.equal(card(failure).color,'#E74C3C');
  const cancelled=await run({build:{result:'success'},deploy:{result:'cancelled'}});
  assert(card(cancelled).fallback.includes('취소'));
  assert.equal(card(cancelled).color,'#F2C744');
  const ci=await run({tests:{result:'failure'}},'', 'ok',secret,'CI');
  assert(card(ci).fallback.includes('CI 실패'));
  assert(!card(await run(success)).fallback.includes('배포 완료'));
  // push 이벤트도 gate가 확인한 PR을 보여주고, 알림 재실행 시 빌드 당시의 태그를 쓴다.
  context.payload={};
  const pushed=await run({...success,'merged-pr':{result:'success',outputs:{pr_title:'수정'.repeat(1000),pr_number:'1019'}},
    build:{result:'success',outputs:{image_tag:'batch-original-attempt-1'}}},'installed');
  assert(content(pushed).includes('#1019'));assert(!content(pushed).includes('refs/heads/'));
  assert(content(pushed).includes('batch-original-attempt-1'));assert(content(pushed).includes('aaaaaaaaaaaa'));
  for(const field of card(pushed).blocks.flatMap(b=>b.fields||[])) assert(field.text.length<=2000);
  for(const mode of ['throw','reject']) {const r=await run(success,'',mode);assert(r.logs.some(s=>s.includes('notification failed')))}
  assert.equal((await run(success,'','ok','')).sent.length,0);
  for(const bad of ['http://hooks.slack.com/services/test','https://example.com/services/test',secret+'?token=private']) {
    assert.equal((await run(success,'','ok',bad)).sent.length,0);
  }
})().catch(error=>{console.error(error);process.exitCode=1});
'''
        subprocess.run(['node', '-e', harness.replace('SCRIPT', json.dumps(script))], check=True, timeout=15)
