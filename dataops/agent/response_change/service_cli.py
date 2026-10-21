"""Prepare/install an explicitly owned per-user launchd service.

prepare writes a reviewable frozen initial plan without starting a process or
connecting to PostgreSQL. start/reconcile require --live and use the release
service fence. A LaunchAgent recovers after user login, not before login.
This command never discovers/kills arbitrary PIDs, migrates a database, collects
external data, approves a repair, or grants the service an operating writer.
"""
import argparse
import json
import os
from pathlib import Path

import psycopg2
from dotenv import dotenv_values

from .cli import ROOT
from .full_registry import FullReleaseRegistry
from .host_runtime import ManagedHostAdapter, ManagedServiceConfig, RUNTIME_FIELDS, private_file, require
from .release import PgReleaseJournal, ReleaseHold
from .approval import ApprovalHold

CONFIG_FIELDS = frozenset({'service_id','host_directory','java','server_properties','server_port','registry_directory'})
RUNTIME_FIELDS_REQUIRED = frozenset(RUNTIME_FIELDS) | {'service_id','artifact_path'}


def configuration(path):
    value=json.loads(private_file(Path(path)).read_text())
    require(isinstance(value,dict) and set(value)==CONFIG_FIELDS,'SERVICE_CONFIGURATION_FIELDS')
    for field in ('host_directory','java','server_properties','registry_directory'):
        require(isinstance(value[field],str) and Path(value[field]).is_absolute(),'ABSOLUTE_SERVICE_PATH_REQUIRED')
    return value


def prepare(host,registry,runtime_path,build_receipt_digest):
    runtime=json.loads(private_file(Path(runtime_path)).read_text())
    require(isinstance(runtime,dict) and set(runtime)==RUNTIME_FIELDS_REQUIRED,'INITIAL_RUNTIME_FIELDS')
    receipt=registry.build_receipt(build_receipt_digest)
    require(runtime['artifact_sha256']==receipt['artifact_sha256']
            and runtime['artifact_path']==receipt['artifact_path'],'INITIAL_COMMITTED_BUILD_CHANGED')
    return host.prepare_installation(runtime,build_receipt_digest)


def summary(receipt):
    return {'state':receipt['state'],'plan_digest':receipt['plan_digest'],
        'build_receipt_digest':receipt['request']['build_receipt_digest'],
        'service_id':receipt['request']['runtime']['service_id'],
        'artifact_sha256':receipt['plan']['runtime']['artifact_sha256'],
        'launch_nonce':receipt['plan']['launch_nonce'],'admission_path':receipt['plan']['admission_path'],
        'restart_policy':'KEEP_ALIVE_AFTER_USER_LOGIN','operating_writes':0}


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configuration',required=True,type=Path)
    parser.add_argument('action',choices=('prepare','show','start','reconcile'))
    parser.add_argument('--runtime',type=Path)
    parser.add_argument('--build-receipt')
    parser.add_argument('--live',action='store_true',help='Explicitly perform initial launch/enrollment or reconcile it')
    parser.add_argument('--resume-absent',action='store_true',help='Retry an initial launch only if its durable intent exists and launchd proves the job absent')
    args=parser.parse_args(argv)
    try:
        require(not args.resume_absent or args.action=='start','RESUME_REQUIRES_INITIAL_START')
        config=configuration(args.configuration)
        host=ManagedHostAdapter(ManagedServiceConfig(config['service_id'],Path(config['host_directory']),Path(config['java']),
            Path(config['server_properties']),config['server_port']))
        if args.action=='prepare':
            require(args.runtime is not None and args.build_receipt is not None and not args.live,'PREPARE_REQUIRES_BUILD_AND_RUNTIME')
            receipt=prepare(host,FullReleaseRegistry(Path(config['registry_directory'])),args.runtime,args.build_receipt)
            result=summary(receipt)
        elif args.action=='show':
            require(not args.live,'SHOW_IS_READONLY');result=summary(host._initial())
        else:
            require(args.live,'EXPLICIT_INITIAL_SERVICE_EXECUTION_REQUIRED')
            settings=dotenv_values(ROOT/'.env')
            dsn=os.environ.get('AGENT_RELEASE_DSN') or settings.get('AGENT_RELEASE_DSN')
            require(isinstance(dsn,str) and bool(dsn),'AGENT_RELEASE_DSN_REQUIRED')
            journal=PgReleaseJournal(lambda:psycopg2.connect(dsn,connect_timeout=5))
            with journal.service_lock(config['service_id']) as fence:
                if args.action=='reconcile':result=host.reconcile_initial(fence)
                else:
                    live=host.start_initial(fence,resume_absent=args.resume_absent)
                    result={'outcome':'DONE','state':'ENROLLED','runtime':live,'operating_writes':0}
        print(json.dumps(result,ensure_ascii=False));return 3 if result.get('outcome')=='UNKNOWN' else 0
    except (ApprovalHold,ReleaseHold) as error:
        print(json.dumps({'state':'HELD','code':str(error)},ensure_ascii=False));return 2
    except Exception:
        print(json.dumps({'state':'RECOVERY_REQUIRED','code':'INITIAL_SERVICE_UNAVAILABLE'}));return 3


if __name__=='__main__':raise SystemExit(main())
