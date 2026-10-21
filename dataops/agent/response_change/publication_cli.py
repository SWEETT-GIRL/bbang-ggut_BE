"""Deliver one already prepared PR of an explicitly selected kind.

Preview is read-only; REPORT_ONLY remains the default. --live claims one durable
outbox item of --kind, pushes its exact frozen head and reconciles publication.
The publisher cannot merge a PR, issue approval, deploy, call a model or apply data.
"""
import argparse
import json
import os
from pathlib import Path
import uuid

import psycopg2
from dotenv import dotenv_values

from .approval import PgReviewStore, ReviewManifest
from .cli import ROOT
from .github_review import GitHubAdapter, PreparedReview, deliver_claim
from .publication import GitReportPreparer


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--repository-path', type=Path, help='Git checkout distinct from immutable installed Python code; disables .env fallback')
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--kind', choices=('REPORT_ONLY','PARSER_REPAIR','DATA_APPLY'), default='REPORT_ONLY')
    args = parser.parse_args(argv)
    repository_path = args.repository_path.resolve() if args.repository_path else ROOT
    settings = {} if args.repository_path else dotenv_values(ROOT / '.env')
    dsn = os.environ.get('AGENT_PUBLICATION_DSN') or settings.get('AGENT_PUBLICATION_DSN')
    if not dsn:
        parser.error('AGENT_PUBLICATION_DSN is required; never use serving credentials')
    connect = lambda: psycopg2.connect(dsn, connect_timeout=5)
    if not args.live:
        db = connect()
        try:
            db.set_session(readonly=True)
            with db, db.cursor() as cursor:
                cursor.execute("SELECT state,count(*) FROM response_agent_notification_outbox GROUP BY state ORDER BY state")
                print(json.dumps({'mode': 'PREVIEW', 'states': dict(cursor.fetchall()), 'github_writes': 0}))
        finally:
            db.close()
        return 0
    store = PgReviewStore(connect)
    claim = store.claim(owner='publisher-' + uuid.uuid4().hex, lease_seconds=300, kinds=[args.kind])
    if claim is None:
        print(json.dumps({'status': 'IDLE'}))
        return 0
    request = PreparedReview(ReviewManifest(claim.payload['manifest']), **claim.payload['prepared'])
    if args.kind == 'REPORT_ONLY':
        preparer = GitReportPreparer(repository_path, repository=args.repository)
    else:
        from .review_preparation import GitExactReviewPreparer
        preparer = GitExactReviewPreparer(repository_path, repository=args.repository)
    preparer.push_prepared(request)
    result = deliver_claim(store, GitHubAdapter(allowed_repository=args.repository, workspace=repository_path), claim)
    print(json.dumps({'status': 'DELIVERED' if result else 'DELIVERY_UNKNOWN', 'reference': result,
                      'notification_received': 'UNVERIFIED'}, ensure_ascii=False))
    return 0 if result else 1


if __name__ == '__main__':
    raise SystemExit(main())
