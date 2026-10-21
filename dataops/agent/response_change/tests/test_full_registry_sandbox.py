"""Opt-in offline counterexamples; no server, database, model or network is started."""
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest

from agent.response_change.approval import ApprovalHold
from agent.response_change import current_manifest, current_validator
from agent.response_change.full_registry import probe_artifact


@unittest.skipUnless(os.getenv('RUN_FULL_REGISTRY_SANDBOX_TESTS') == 'true', 'offline Docker probe opt-in')
class FullRegistrySandboxTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root=Path(__file__).resolve().parents[3]
        cls.temporary=tempfile.TemporaryDirectory(prefix='agent-full-registry-counterexamples-')
        cls.work=Path(cls.temporary.name)
        cls.runtime=json.loads((current_validator.RUNTIME/'manifest.json').read_text())
        cls.base=cls.work/'service.jar'
        shutil.copyfile(os.environ['AGENT_PROBE_TEST_JAR'],cls.base)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def compile(self,name,source,fixtures):
        candidate={current_manifest.PARSER_PATH:source,
            current_manifest.PROFILE_PATH:(self.root/current_manifest.PROFILE_PATH).read_text()}
        report=current_validator.compile_and_run(self.root,candidate,fixtures,self.work/name,self.runtime)
        self.assertEqual('OK',report['status'],report.get('error'))
        self.assertTrue(current_validator.regression_passes(report['regression']),report['regression'])
        return candidate,report

    def test_component_removal_passes_parser_tests_but_actual_spring_probe_rejects_it(self):
        baseline=probe_artifact(self.base,self.runtime['image_id'],self.work/'baseline-probe')
        self.assertEqual(64,len(baseline['parser_digest']))
        original=(self.root/current_manifest.PARSER_PATH).read_text()
        self.assertIn('@Component\n',original)
        candidate,report=self.compile('without-component',original.replace('@Component\n',''),[])
        broken=self.work/'without-component.jar'
        current_validator._candidate_jar(self.base,broken,report['class_bytes'],candidate[current_manifest.PROFILE_PATH].encode())
        with self.assertRaisesRegex(ApprovalHold,'OFFLINE_BUILD_OR_PROBE_FAILED'):
            probe_artifact(broken,self.runtime['image_id'],self.work/'broken-probe')

    def test_input_mutation_is_rejected_even_when_all_fixed_regressions_pass(self):
        original=(self.root/current_manifest.PARSER_PATH).read_text()
        signature='public ParsedPlace parse(JsonNode apolloState) {'
        injected='''
        if (apolloState.path("mutationProbe").asBoolean(false)) {
            ((ObjectNode)apolloState.path("PlaceDetailBase:123")).put("id","123");
            for (var entry : apolloState.path("ROOT_QUERY").properties()) {
                ((ObjectNode)entry.getValue().path("placeMenus")).put("menuCount",0);
            }
        }
'''
        self.assertIn(signature,original)
        raw={'mutationProbe':True,'PlaceDetailBase:123':{'id':'wrong','name':'가게'},
             'ROOT_QUERY':{'placeDetail({"input":{"id":"123"}})':{'placeMenus':{'items':[],'menuCount':99}}}}
        _,report=self.compile('mutated-input',original.replace(signature,signature+injected),
            [{'id':'mutation-probe','external_id':'123','raw':raw}])
        self.assertEqual('IllegalStateException: PARSER_INPUT_MUTATION',report['cases'][0]['error'])
        self.assertNotIn('actual',report['cases'][0])


if __name__=='__main__':unittest.main()
