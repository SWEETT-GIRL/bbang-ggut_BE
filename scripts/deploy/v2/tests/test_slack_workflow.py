"""Run the actual reusable workflow script with a fake transport; no Slack calls."""
import json
from pathlib import Path
import subprocess
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[4]


class SlackWorkflowTest(unittest.TestCase):
    def test_workflow_outcomes_and_secret_safe_transport_failures(self):
        workflow = (ROOT / '.github/workflows/slack-notify.yml').read_text()
        script = textwrap.dedent(workflow.split('          script: |\n', 1)[1])
        harness = r'''
const assert = require('node:assert/strict');
const execute = new (Object.getPrototypeOf(async function(){}).constructor)('core','context','fetch', SCRIPT);
const context = {repo:{owner:'test',repo:'repo'},sha:'a'.repeat(40),ref:'refs/heads/develop-v2',runId:42,
  payload:{pull_request:{number:7,head:{ref:'feature/<!channel>"$()',sha:'b'.repeat(40)},base:{ref:'develop-v2'}}}};
const secret = 'https://hooks.slack.com/services/TEST/TEST/never-send';
async function run(results, state='', mode='ok', webhook=secret, kind='CD') {
  const logs=[], sent=[];
  const core={notice:s=>logs.push(s),warning:s=>logs.push(s),summary:{addRaw(s){logs.push(s);return this},async write(){}}};
  Object.assign(process.env, {SLACK_WEBHOOK_URL:webhook,NOTIFY_COMPONENT:'DataOps',NOTIFY_KIND:kind,
    NOTIFY_RESULTS:JSON.stringify(results),DEPLOYMENT_STATE:state,GITHUB_SERVER_URL:'https://github.com',GITHUB_RUN_ATTEMPT:'2'});
  await execute(core,context,async (url,options)=>{
    sent.push({url,options,body:JSON.parse(options.body)});
    if(mode==='throw') throw new Error(secret);
    return {ok:mode!=='reject',text:async()=>mode==='reject'?secret:'ok'};
  });
  assert(!logs.join('\n').includes(secret));
  return {logs,sent};
}
(async()=>{
  const success={build:{result:'success'},deploy:{result:'success'}};
  for(const [state,expected] of [['prepared','실행 중 서비스 검증 없음'],['refreshed','ASG 인스턴스 교체 완료'],['installed','배치 실행 결과와 별개']]) {
    const r=await run(success,state); assert.equal(r.sent.length,1); assert(r.sent[0].body.text.includes(expected));
    assert.equal(r.sent[0].options.redirect,'error'); assert.equal(r.sent[0].body.blocks[0].text.type,'plain_text');
    assert(r.sent[0].body.blocks[0].text.text.includes('feature/<!channel>"$()'));
    assert(r.sent[0].body.blocks[0].text.text.includes('bbbbbbbbbbbb'));
  }
  const failure=await run({build:{result:'failure'},deploy:{result:'skipped'}},'installed');
  assert(failure.sent[0].body.text.includes('실패')); assert(!failure.sent[0].body.text.includes('설치 완료'));
  assert(failure.sent[0].body.blocks[0].text.text.includes('실패 단계: build'));
  const cancelled=await run({build:{result:'success'},deploy:{result:'cancelled'}});
  assert(cancelled.sent[0].body.text.includes('취소'));
  const ci=await run({tests:{result:'failure'}},'', 'ok',secret,'CI');
  assert(ci.sent[0].body.text.includes('CI 실패'));
  for(const mode of ['throw','reject']) {const r=await run(success,'',mode);assert(r.logs.some(s=>s.includes('notification failed')))}
  assert.equal((await run(success,'','ok','')).sent.length,0);
  for(const bad of ['http://hooks.slack.com/services/test','https://example.com/services/test',secret+'?token=private']) {
    assert.equal((await run(success,'','ok',bad)).sent.length,0);
  }
})().catch(error=>{console.error(error);process.exitCode=1});
'''
        subprocess.run(['node', '-e', harness.replace('SCRIPT', json.dumps(script))], check=True, timeout=15)
