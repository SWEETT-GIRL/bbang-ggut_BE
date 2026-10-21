"""Deliver one fixed informational PR status comment; never approve, merge or apply."""
import argparse
import json
import os
from pathlib import Path
import uuid

import psycopg2

from .cli import ROOT
from .github_review import GitHubAdapter
from .status_notification import PgStatusStore, GitHubStatusPublisher, deliver_status


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository',required=True)
    parser.add_argument('--repository-path',type=Path,default=ROOT)
    parser.add_argument('--live',action='store_true')
    args=parser.parse_args(argv)
    dsn=os.environ.get('AGENT_PUBLICATION_DSN')
    if not dsn:parser.error('AGENT_PUBLICATION_DSN is required')
    try:
        store=PgStatusStore(lambda:psycopg2.connect(dsn,connect_timeout=5))
        if not args.live:
            print(json.dumps(store.preview()));return 0
        claim=store.claim('status-publisher-'+uuid.uuid4().hex)
        if claim is None:
            print(json.dumps({'state':'IDLE'}));return 0
        adapter=GitHubAdapter(allowed_repository=args.repository,workspace=args.repository_path)
        result=deliver_status(store,GitHubStatusPublisher(adapter),claim,args.repository)
        print(json.dumps({'state':'DELIVERED' if result else 'PENDING_OR_UNKNOWN','reference':result},ensure_ascii=False))
        return 0 if result else 1
    except Exception:
        print(json.dumps({'state':'UNKNOWN','reason_code':'STATUS_PUBLISHER_UNAVAILABLE'}));return 2


if __name__=='__main__':raise SystemExit(main())
