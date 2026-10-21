"""Advance a published exact approval once. Preview is the default.

This controller never merges/approves a PR, runs a model, installs a service,
executes migrations, or enables the collector's serving writer. The service,
registry, database roles and private JVM properties must already be configured.
"""
import argparse
import json
import os
from pathlib import Path

import psycopg2
from psycopg2.extras import RealDictCursor
from dotenv import dotenv_values

from .approval import ApprovalHold, ApprovalVerifier, PublicationBinding, ReviewManifest, SHA
from .cli import ROOT
from .data_execution import ExactDataApplyController
from .full_registry import FullReleaseRegistry
from .github_review import GitHubAdapter
from .host_runtime import ManagedHostAdapter, ManagedServiceConfig, private_file, require
from .java_bridge import JavaBridgeConfig, JavaReplayAdapter
from .release import PgReleaseJournal, ReleaseController, ReleaseHold


CONFIG_FIELDS = frozenset({'repository','allowed_actor_ids','registry_directory','service_id','host_directory','java',
    'server_properties','server_port','replay_properties','replay_directory','apply_properties','apply_directory'})


def configuration(path):
    value = json.loads(private_file(Path(path)).read_text())
    require(isinstance(value, dict) and set(value) == CONFIG_FIELDS, 'CONTROLLER_CONFIGURATION_FIELDS')
    ApprovalVerifier(allowed_repository=value['repository'], allowed_actor_ids=value['allowed_actor_ids'])
    for key in ('registry_directory','host_directory','java','server_properties','replay_properties','replay_directory','apply_properties','apply_directory'):
        require(isinstance(value[key], str) and Path(value[key]).is_absolute(), 'ABSOLUTE_CONTROLLER_PATH_REQUIRED')
    return value


def load_review(connect, digest):
    require(isinstance(digest, str) and SHA.fullmatch(digest), 'MANIFEST_DIGEST_REQUIRED')
    connection = connect()
    try:
        connection.set_session(readonly=True)
        with connection, connection.cursor(cursor_factory=RealDictCursor) as cursor:
            cursor.execute('''SELECT m.document,p.repository,p.pr_number,p.head_sha FROM response_agent_review_manifest m
                JOIN response_agent_review_publication p USING(manifest_digest) WHERE m.manifest_digest=%s''', (digest,))
            row = cursor.fetchone(); require(row is not None, 'PUBLISHED_MANIFEST_REQUIRED')
            manifest = ReviewManifest(row['document'])
            require(manifest.digest == digest, 'MANIFEST_STORE_CHANGED')
            return manifest, PublicationBinding(repository=row['repository'], pr_number=row['pr_number'], head_sha=row['head_sha'], manifest_digest=digest)
    finally:
        connection.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configuration', required=True, type=Path)
    parser.add_argument('--manifest-digest', required=True)
    parser.add_argument('--live', action='store_true', help='Perform one effect, only under an authenticated merged PR')
    parser.add_argument('--reconcile', action='store_true', help='Read prior effect receipts; do not start/retry work')
    args = parser.parse_args(argv)
    try:
        config = configuration(args.configuration)
        settings = dotenv_values(ROOT / '.env')
        dsn = os.environ.get('AGENT_RELEASE_DSN') or settings.get('AGENT_RELEASE_DSN')
        require(isinstance(dsn, str) and bool(dsn), 'AGENT_RELEASE_DSN_REQUIRED')
        connect = lambda: psycopg2.connect(dsn, connect_timeout=5)
        manifest, binding = load_review(connect, args.manifest_digest)
        require(binding.repository == config['repository'], 'CONTROLLER_REPOSITORY_CHANGED')
        if not args.live and not args.reconcile:
            print(json.dumps({'mode':'PREVIEW','kind':manifest.document['kind'],'scope':manifest.document['scope'],
                'manifest_digest':manifest.digest,'pr_number':binding.pr_number,'targets':len(manifest.document['targets']),
                'side_effects':0}, ensure_ascii=False))
            return 0
        require(not (args.live and args.reconcile), 'CHOOSE_EXECUTION_OR_RECONCILIATION')
        require(manifest.document['kind'] in {'PARSER_REPAIR','DATA_APPLY'}, 'REPORT_ONLY_NOT_AUTHORITY')
        registry = FullReleaseRegistry(Path(config['registry_directory']))
        provider = GitHubAdapter(allowed_repository=config['repository'], workspace=ROOT)
        verifier = ApprovalVerifier(allowed_repository=config['repository'], allowed_actor_ids=config['allowed_actor_ids'])
        if manifest.document['kind'] == 'DATA_APPLY':
            controller = ExactDataApplyController(connect=connect,registry=registry,provider=provider,verifier=verifier,
                config=JavaBridgeConfig(Path(config['java']),Path(config['apply_properties']),Path(config['apply_directory'])))
            result = controller.status(manifest) if args.reconcile else controller.advance(manifest,binding)
            print(json.dumps(result,ensure_ascii=False))
        else:
            host = ManagedHostAdapter(ManagedServiceConfig(config['service_id'],Path(config['host_directory']),Path(config['java']),
                Path(config['server_properties']),config['server_port']))
            bridge = JavaReplayAdapter(JavaBridgeConfig(Path(config['java']),Path(config['replay_properties']),Path(config['replay_directory'])),registry)
            controller = ReleaseController(journal=PgReleaseJournal(connect),registry=registry,host=host,replay=bridge,provider=provider,verifier=verifier)
            result = controller.reconcile(manifest,binding) if args.reconcile else controller.advance(manifest,binding)
            print(json.dumps({key:result.get(key) for key in ('operation_id','manifest_digest','state','pending_action','hold_code','operating_writes')},ensure_ascii=False))
        return 0
    except (ApprovalHold,ReleaseHold) as error:
        print(json.dumps({'state':'HELD','code':str(error)},ensure_ascii=False))
        return 2
    except Exception:
        # Database URLs, configuration, raw responses and Java logs are not public error messages.
        print(json.dumps({'state':'RECOVERY_REQUIRED','code':'CONTROLLER_UNAVAILABLE'}))
        return 3


if __name__ == '__main__':
    raise SystemExit(main())
