"""Run one scheduling tick. No daemon loop, model call, publication or auto-merge.

launchd may invoke --live every configured poll_seconds. With no action flag the
command only reads queue counts. --reconcile reads an UNKNOWN attempt's existing
receipts and settles its scheduling state; it never calls an effect again.
Only AGENT_ORCHESTRATION_DSN is read from the environment; .env is not loaded.
"""
import argparse
import json
import os
from pathlib import Path

import psycopg2

from .approval import ApprovalHold, ApprovalVerifier, PgReviewStore, SHA, ID
from .data_execution import ExactDataApplyController
from .full_registry import FullReleaseRegistry
from .github_review import GitHubAdapter, BASE
from .host_runtime import ManagedHostAdapter, ManagedServiceConfig, private_file
from .java_bridge import JavaBridgeConfig, JavaReplayAdapter
from .orchestration import Orchestrator, PgOrchestrationStore, VerifiedMergeResolver, PHASES, require
from .release import PgReleaseJournal, ReleaseController, ReleaseHold
from .release_cli import configuration as release_configuration
from .review_preparation import ExactReviewPreparation, GitExactReviewPreparer

CONFIG_FIELDS = frozenset({'release_configuration','repository_path','baseline_pointer','recovery_receipt',
    'worker_output_directory','base_branch','poll_seconds','review_ttl_seconds','discovery_limit'})


def configuration(path):
    value = json.loads(private_file(Path(path)).read_text())
    require(isinstance(value, dict) and set(value) == CONFIG_FIELDS, 'ORCHESTRATION_CONFIGURATION_FIELDS')
    for name in ('release_configuration','repository_path','baseline_pointer','recovery_receipt','worker_output_directory'):
        require(isinstance(value[name], str) and Path(value[name]).is_absolute(), 'ABSOLUTE_ORCHESTRATION_PATH_REQUIRED')
    require(isinstance(value['base_branch'], str) and BASE.fullmatch(value['base_branch']) and '..' not in value['base_branch'], 'BASE_BRANCH')
    for name, lower, upper in (('poll_seconds',10,86400), ('review_ttl_seconds',3600,604800), ('discovery_limit',1,100)):
        require(type(value[name]) is int and lower <= value[name] <= upper, 'ORCHESTRATION_CONFIGURATION_BOUNDS')
    return value


class LazyAdapter:
    """Unrelated phase configuration cannot prevent receipt reconciliation."""
    def __init__(self, factory):
        self.factory, self.instance = factory, None

    def __getattr__(self, name):
        if self.instance is None:
            self.instance = self.factory()
        return getattr(self.instance, name)


def build_orchestrator(connect, config, release_config):
    root = Path(config['repository_path'])
    registry = FullReleaseRegistry(Path(release_config['registry_directory']))
    preparer = GitExactReviewPreparer(root, repository=release_config['repository'])
    reviews = ExactReviewPreparation(registry=registry, preparer=preparer, store=PgReviewStore(connect), base_branch=config['base_branch'])

    def candidate():
        from .candidate_preparation import CandidatePreparationService
        from .current_bundle import CurrentParserBundleProvider
        from .baseline_promotion import BaselinePointerStore
        baseline = BaselinePointerStore(registry=registry, connect=connect, service_id=release_config['service_id'], path=Path(config['baseline_pointer'])).read()
        provider = CurrentParserBundleProvider(Path(baseline['deployment_manifest']), baseline['deployment_manifest_digest'])
        return CandidatePreparationService(connect=connect, registry=registry, provider=provider, reviews=reviews,
            repository=root, output_root=Path(config['worker_output_directory']), base_release=baseline['base_release'],
            revision=1, review_ttl_seconds=config['review_ttl_seconds'], canary_limit=20)

    def authority():
        return (GitHubAdapter(allowed_repository=release_config['repository'], workspace=root),
                ApprovalVerifier(allowed_repository=release_config['repository'], allowed_actor_ids=release_config['allowed_actor_ids']))

    def release():
        provider, verifier = authority()
        host = ManagedHostAdapter(ManagedServiceConfig(release_config['service_id'],Path(release_config['host_directory']),
            Path(release_config['java']),Path(release_config['server_properties']),release_config['server_port']))
        replay = JavaReplayAdapter(JavaBridgeConfig(Path(release_config['java']),Path(release_config['replay_properties']),
            Path(release_config['replay_directory'])), registry)
        return ReleaseController(journal=PgReleaseJournal(connect),registry=registry,host=host,replay=replay,provider=provider,verifier=verifier)

    def data():
        provider, verifier = authority()
        return ExactDataApplyController(connect=connect,registry=registry,provider=provider,verifier=verifier,
            config=JavaBridgeConfig(Path(release_config['java']),Path(release_config['apply_properties']),Path(release_config['apply_directory'])))

    def promoter():
        from .baseline_promotion import BaselinePointerStore, BaselinePromotionService
        from .recovery import RecoveryVerifier
        provider, verifier = authority()
        host = ManagedHostAdapter(ManagedServiceConfig(release_config['service_id'],Path(release_config['host_directory']),
            Path(release_config['java']),Path(release_config['server_properties']),release_config['server_port']))
        pointers = BaselinePointerStore(registry=registry,connect=connect,service_id=release_config['service_id'],path=Path(config['baseline_pointer']))
        return BaselinePromotionService(connect=connect,registry=registry,repository=root,pointers=pointers,
            host=host,journal=PgReleaseJournal(connect),provider=provider,verifier=verifier,
            verify_recovery=RecoveryVerifier(connect,Path(config['recovery_receipt'])))

    return Orchestrator(store=PgOrchestrationStore(connect,release_config['service_id']), candidate=LazyAdapter(candidate),
        release=LazyAdapter(release), data=LazyAdapter(data), reviews=reviews, merge_resolver=VerifiedMergeResolver(preparer),promoter=LazyAdapter(promoter),
        poll_seconds=config['poll_seconds'], review_ttl_seconds=config['review_ttl_seconds'], discovery_limit=config['discovery_limit'])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configuration', type=Path, required=True)
    action = parser.add_mutually_exclusive_group()
    action.add_argument('--live', action='store_true')
    action.add_argument('--reconcile', action='store_true')
    parser.add_argument('--phase', choices=tuple(PHASES), default='all')
    args = parser.parse_args(argv)
    os.umask(0o077)
    try:
        config = configuration(args.configuration)
        release_config = release_configuration(config['release_configuration'])
        dsn = os.environ.get('AGENT_ORCHESTRATION_DSN')
        require(isinstance(dsn, str) and bool(dsn), 'AGENT_ORCHESTRATION_DSN_REQUIRED')
        connect = lambda: psycopg2.connect(dsn, connect_timeout=5)
        if not args.live and not args.reconcile:
            result = PgOrchestrationStore(connect,release_config['service_id']).preview()
        else:
            result = build_orchestrator(connect, config, release_config).tick(phase=args.phase,reconcile=args.reconcile)
        print(json.dumps(result, ensure_ascii=False))
        return 0 if result.get('state') not in {'HELD','UNKNOWN'} else 2
    except (ApprovalHold, ReleaseHold) as error:
        code = str(error)
        print(json.dumps({'state': 'BUSY' if code == 'ORCHESTRATION_BUSY' else 'HELD', 'reason_code': code}))
        return 0 if code == 'ORCHESTRATION_BUSY' else 2
    except Exception:
        print(json.dumps({'state':'UNKNOWN','reason_code':'ORCHESTRATOR_UNAVAILABLE'}))
        return 3


if __name__ == '__main__':
    raise SystemExit(main())
