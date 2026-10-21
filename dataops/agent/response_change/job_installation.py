"""Prepare and explicitly install exact owned per-user launchd job definitions.

Preparation writes only a reviewable immutable plan under a private directory.
--live validates its supplied digest and bootstraps absent owned labels. It never
bootouts, replaces a different definition, starts a collection immediately, or
retries an unconfirmed bootstrap. launchctl print is the registration authority.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import stat
import subprocess

from .approval import content_digest
from .host_runtime import private_directory, private_file, require, write_json
from .job_runner import configuration, environment, command, verify_installation, private_log, job_lock, SAFE_PATH
from .release import ReleaseHold

COMPONENT = re.compile(r'[a-z0-9][a-z0-9-]{0,47}\Z')
VERSION = 'OWNED_LAUNCHD_JOBS_V1'
SMOKE_MODULES = ('agent.response_change.job_runner', 'agent.response_change.worker_cli',
    'agent.response_change.orchestration_cli', 'agent.response_change.publication_cli',
    'agent.response_change.status_publication_cli')
SMOKE_DEPENDENCIES = ('psycopg2', 'dotenv', 'requests', 'langchain_anthropic', 'langgraph.graph')


def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def interpreter_smoke(python, code_root, state_directory):
    """Import the frozen entry points using the lexical venv, without credentials.

    This confirms dependency availability only. It neither constructs a provider
    nor contacts a database, model, GitHub, or the running service.
    """
    cache=private_directory(Path(state_directory)/'import-probe-cache')
    script='''import importlib,json,pathlib,sys
root=pathlib.Path(sys.argv[1]).resolve()
modules=json.loads(sys.argv[2]);dependencies=json.loads(sys.argv[3])
for name in dependencies:importlib.import_module(name)
for name in modules:
    module=importlib.import_module(name)
    assert pathlib.Path(module.__file__).resolve().is_relative_to(root)
print(json.dumps({'kind':'FROZEN_IMPORT_SMOKE_V1','python':sys.executable,'version':sys.version,
    'prefix':sys.prefix,'modules':modules,'dependencies':dependencies},sort_keys=True))
'''
    probe_env={'HOME':str(Path.home()),'PATH':SAFE_PATH,'LANG':'en_US.UTF-8','TZ':'Asia/Seoul',
        'PYTHONDONTWRITEBYTECODE':'1','PYTHONNOUSERSITE':'1','PYTHONUNBUFFERED':'1',
        'AGENT_RUNTIME_CACHE_DIRECTORY':str(cache)}
    try:
        result=subprocess.run([str(python),'-c',script,str(code_root),json.dumps(SMOKE_MODULES),json.dumps(SMOKE_DEPENDENCIES)],
            cwd=code_root,env=probe_env,stdin=subprocess.DEVNULL,capture_output=True,timeout=45,check=False)
        require(result.returncode==0,'INSTALLED_PYTHON_DEPENDENCIES_UNAVAILABLE')
        value=json.loads(result.stdout)
        require(isinstance(value,dict) and value.get('kind')=='FROZEN_IMPORT_SMOKE_V1'
            and value.get('python')==str(python) and value.get('modules')==list(SMOKE_MODULES)
            and value.get('dependencies')==list(SMOKE_DEPENDENCIES),'INSTALLED_PYTHON_IMPORT_PROOF_INVALID')
        return value
    except (OSError,subprocess.SubprocessError,ValueError) as exc:
        raise ReleaseHold('INSTALLED_PYTHON_DEPENDENCIES_UNAVAILABLE') from exc


def system_timezone():
    actual=Path('/etc/localtime');expected=Path('/usr/share/zoneinfo/Asia/Seoul')
    require(actual.is_file() and expected.is_file() and sha(actual)==sha(expected),'SYSTEM_TIMEZONE_MUST_BE_ASIA_SEOUL')
    return {'timezone':'Asia/Seoul','zoneinfo_sha256':sha(actual)}


def _write_once(path,raw):
    path=Path(path)
    require(path.parent.resolve()==path.parent,'OWNED_INSTALLATION_DIRECTORY_REQUIRED')
    try:descriptor=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    except FileExistsError:
        require(private_file(path).read_bytes()==raw,'FROZEN_INSTALLATION_FILE_CHANGED');return
    with os.fdopen(descriptor,'wb') as output:
        output.write(raw);output.flush();os.fsync(output.fileno())
    descriptor=os.open(path.parent,os.O_RDONLY)
    try:os.fsync(descriptor)
    finally:os.close(descriptor)


def _plist(service_id,config,config_path,python,code_root):
    require(isinstance(config['job_id'],str) and COMPONENT.fullmatch(config['job_id']),'JOB_LABEL_COMPONENT')
    label=f"org.bbanggut.dataops.{service_id}.job.{config['job_id']}"
    state=Path(config['state_directory'])
    value={'Label':label,'ProgramArguments':[str(python),'-m','agent.response_change.job_runner','--configuration',str(config_path),
        '--configuration-digest',sha(config_path),'--installation-receipt-digest',sha(config['installation_receipt']),
        '--python-digest',sha(python),'--live'],
        'WorkingDirectory':str(code_root),'RunAtLoad':config['kind']!='collection','KeepAlive':False,'Umask':63,
        'StandardOutPath':str(state/'launchd.stdout.log'),'StandardErrorPath':str(state/'launchd.stderr.log'),
        'EnvironmentVariables':{'HOME':str(Path.home()),'PATH':SAFE_PATH,'LANG':'en_US.UTF-8','TZ':'Asia/Seoul',
            'PYTHONDONTWRITEBYTECODE':'1','PYTHONNOUSERSITE':'1','PYTHONUNBUFFERED':'1'}}
    if config['kind']=='collection':value['StartCalendarInterval']={'Hour':9,'Minute':0}
    else:value['StartInterval']=60
    return label,plistlib.dumps(value,sort_keys=True)


def prepare_plan(*,service_id,installation_receipt,configurations,python,state_directory,launch_agents_directory=None,timezone_reader=system_timezone):
    require(isinstance(service_id,str) and COMPONENT.fullmatch(service_id),'SERVICE_LABEL_COMPONENT')
    require(isinstance(configurations,(list,tuple)) and 1<=len(configurations)<=16,'INSTALLATION_JOB_BOUND')
    receipt_path=private_file(Path(installation_receipt))
    root=verify_installation(receipt_path,require_executing=False)
    python=Path(python)
    # Preserve a venv's lexical path; resolving it would discard its site packages.
    require(python.is_absolute() and python.is_file() and os.access(python,os.X_OK),'FIXED_PYTHON_EXECUTABLE_REQUIRED')
    state=private_directory(Path(state_directory))
    launch=Path(launch_agents_directory) if launch_agents_directory else Path.home()/'Library/LaunchAgents'
    require(launch.is_absolute() and launch.resolve()==launch,'LAUNCH_AGENTS_PATH_INVALID')
    zone=timezone_reader();require(zone.get('timezone')=='Asia/Seoul','SYSTEM_TIMEZONE_MUST_BE_ASIA_SEOUL')
    jobs=[];seen=set();states=set()
    for item in configurations:
        path=private_file(Path(item));config=configuration(path)
        require(config['installation_receipt']==str(receipt_path),'JOB_INSTALLATION_RECEIPT_CHANGED')
        require(config['job_id'] not in seen and config['state_directory'] not in states,'DUPLICATE_JOB_OR_STATE_DIRECTORY')
        require(not Path(config['state_directory']).resolve().is_relative_to(root),'JOB_STATE_INSIDE_FROZEN_SOURCE')
        command(config,root);environment(config)
        seen.add(config['job_id']);states.add(config['state_directory'])
        label,raw=_plist(service_id,config,path,python,root)
        jobs.append({'job_id':config['job_id'],'kind':config['kind'],'configuration':str(path),'configuration_sha256':sha(path),
            'state_directory':config['state_directory'],'label':label,'plist_path':str(launch/(label+'.plist')),
            'plist_sha256':hashlib.sha256(raw).hexdigest(),'plist_base64':base64.b64encode(raw).decode()})
    receipt=json.loads(receipt_path.read_text())
    smoke=interpreter_smoke(python,root,state)
    plan={'kind':VERSION,'service_id':service_id,'uid':os.getuid(),'installation_receipt':str(receipt_path),
        'installation_receipt_sha256':sha(receipt_path),'source_commit':receipt['source_commit'],'code_directory':str(root),
        'python':str(python),'python_realpath':str(python.resolve()),'python_sha256':sha(python),
        'state_directory':str(state),'launch_agents_directory':str(launch),'timezone':zone,'interpreter_smoke':smoke,
        'jobs':sorted(jobs,key=lambda row:row['job_id'])}
    plan['plan_digest']=content_digest(plan)
    target=state/('plan-'+plan['plan_digest']+'.json')
    _write_once(target,(json.dumps(plan,ensure_ascii=False,sort_keys=True,indent=2)+'\n').encode())
    return {'plan_path':str(target),'plan_digest':plan['plan_digest'],'labels':[row['label'] for row in plan['jobs']],
        'schedules':{row['job_id']:('KST 09:00 daily; no immediate collection' if row['kind']=='collection' else '60 seconds; RunAtLoad') for row in jobs},'launchd_changes':0}


class Launchctl:
    def run(self,arguments):
        return subprocess.run(['/bin/launchctl',*arguments],capture_output=True,timeout=15,check=False,
            env={'PATH':'/usr/bin:/bin','HOME':str(Path.home()),'LANG':'C.UTF-8'})

    def observe(self,job,uid):
        result=self.run(['print',f"gui/{uid}/{job['label']}"])
        if result.returncode==113 and b'Could not find service' in result.stderr:return 'ABSENT'
        if result.returncode!=0:return 'UNKNOWN'
        text=result.stdout.decode('utf-8',errors='replace')
        definition=plistlib.loads(base64.b64decode(job['plist_base64']))
        paths=re.findall(r'^\s*path = (.+)\s*$',text,re.MULTILINE)
        programs=re.findall(r'^\s*program = (.+)\s*$',text,re.MULTILINE)
        block=re.search(r'^\s*arguments = \{\s*\n(.*?)^\s*\}',text,re.MULTILINE|re.DOTALL)
        arguments=[line.strip() for line in block.group(1).splitlines() if line.strip()] if block else []
        if paths!=[job['plist_path']] or programs!=[definition['ProgramArguments'][0]] or arguments!=definition['ProgramArguments']:
            return 'FOREIGN'
        return 'LOADED'

    def bootstrap(self,job,uid):
        return self.run(['bootstrap',f'gui/{uid}',job['plist_path']])


def validate_plan(path,expected_digest,*,timezone_reader=system_timezone):
    plan=json.loads(private_file(Path(path)).read_text())
    require(isinstance(plan,dict) and plan.get('kind')==VERSION and plan.get('uid')==os.getuid()
        and plan.get('plan_digest')==expected_digest==content_digest({k:v for k,v in plan.items() if k!='plan_digest'}),'INSTALLATION_PLAN_CHANGED')
    require(timezone_reader()==plan['timezone'],'SYSTEM_TIMEZONE_CHANGED')
    root=verify_installation(Path(plan['installation_receipt']),require_executing=False)
    require(str(root)==plan['code_directory'] and sha(private_file(Path(plan['installation_receipt'])))==plan['installation_receipt_sha256'],'INSTALLATION_RECEIPT_CHANGED')
    python=Path(plan['python'])
    require(str(python.resolve())==plan['python_realpath'] and sha(python)==plan['python_sha256'] and os.access(python,os.X_OK),'PYTHON_EXECUTABLE_CHANGED')
    require(isinstance(plan.get('service_id'),str) and COMPONENT.fullmatch(plan['service_id']),'SERVICE_LABEL_COMPONENT')
    require(1<=len(plan['jobs'])<=16 and len({row['job_id'] for row in plan['jobs']})==len(plan['jobs']),'INSTALLATION_JOB_BOUND')
    for job in plan['jobs']:
        config_path=private_file(Path(job['configuration']))
        require(sha(config_path)==job['configuration_sha256'],'FROZEN_JOB_CONFIGURATION_CHANGED')
        config=configuration(config_path);command(config,root);environment(config)
        label,raw=_plist(plan['service_id'],config,config_path,python,root)
        require(config['installation_receipt']==plan['installation_receipt'] and config['state_directory']==job['state_directory']
            and config['kind']==job['kind'] and config['job_id']==job['job_id'] and label==job['label']
            and str(Path(plan['launch_agents_directory'])/(label+'.plist'))==job['plist_path']
            and raw==base64.b64decode(job['plist_base64']) and hashlib.sha256(raw).hexdigest()==job['plist_sha256'], 'FROZEN_JOB_PLAN_CHANGED')
    require(interpreter_smoke(python,root,Path(plan['state_directory']))==plan['interpreter_smoke'],
        'INSTALLED_PYTHON_IMPORT_PROOF_CHANGED')
    return plan


def install_plan(path,expected_digest,*,driver=None,timezone_reader=system_timezone):
    plan=validate_plan(path,expected_digest,timezone_reader=timezone_reader)
    driver=driver or Launchctl();state=private_directory(Path(plan['state_directory']))
    launch=Path(plan['launch_agents_directory'])
    launch.mkdir(mode=0o700,parents=True,exist_ok=True)
    require(launch.resolve()==launch and launch.stat().st_uid==os.getuid() and launch.stat().st_mode&0o022==0,'OWNED_LAUNCH_AGENTS_DIRECTORY_REQUIRED')
    results=[]
    with job_lock(state):
        receipts=private_directory(state/'receipts')
        for job in plan['jobs']:
            request={'plan_digest':plan['plan_digest'],'label':job['label'],'plist_path':job['plist_path'],'plist_sha256':job['plist_sha256']}
            ledger=receipts/(job['label']+'.json');destination=Path(job['plist_path'])
            previous=json.loads(private_file(ledger).read_text()) if ledger.exists() else None
            if previous:
                require(previous.get('request')==request,'OWNED_JOB_INSTALLATION_CHANGED')
            observed=driver.observe(job,plan['uid'])
            if previous is None:
                require(observed=='ABSENT' and not destination.exists() and not destination.is_symlink(),'FOREIGN_LAUNCHD_JOB')
                previous={'request':request,'state':'FILE_INTENT'};write_json(ledger,previous)
            if destination.exists() or destination.is_symlink():
                require(private_file(destination).read_bytes()==base64.b64decode(job['plist_base64']),'FOREIGN_LAUNCHD_DEFINITION')
            elif previous['state']!='FILE_INTENT':
                raise ReleaseHold('OWNED_LAUNCHD_DEFINITION_MISSING')
            if observed=='LOADED':
                require(previous['state'] in {'BOOTSTRAP_INTENT','UNKNOWN','INSTALLED'},'UNCONFIRMED_JOB_OWNERSHIP')
                previous['state']='INSTALLED';write_json(ledger,previous);results.append({'label':job['label'],'state':'INSTALLED'});continue
            require(observed!='FOREIGN','FOREIGN_LAUNCHD_REGISTRATION')
            if previous['state'] in {'BOOTSTRAP_INTENT','UNKNOWN','INSTALLED'}:
                previous['state']='UNKNOWN';write_json(ledger,previous)
                results.append({'label':job['label'],'state':'UNKNOWN'});continue
            require(observed=='ABSENT','LAUNCHD_REGISTRATION_UNKNOWN')
            _write_once(destination,base64.b64decode(job['plist_base64']))
            job_state=private_directory(Path(job['state_directory']))
            for name in ('launchd.stdout.log','launchd.stderr.log'):
                with private_log(job_state/name):pass
            previous['state']='BOOTSTRAP_INTENT';write_json(ledger,previous)
            try:driver.bootstrap(job,plan['uid'])
            except (OSError,subprocess.SubprocessError):pass
            observed=driver.observe(job,plan['uid'])
            previous['state']='INSTALLED' if observed=='LOADED' else 'UNKNOWN'
            write_json(ledger,previous);results.append({'label':job['label'],'state':previous['state']})
    return {'state':'INSTALLED' if all(row['state']=='INSTALLED' for row in results) else 'UNKNOWN',
        'plan_digest':plan['plan_digest'],'jobs':results,'collection_run_at_load':False,'timezone':'Asia/Seoul'}


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan',type=Path);parser.add_argument('--plan-digest');parser.add_argument('--live',action='store_true')
    parser.add_argument('--service-id');parser.add_argument('--installation-receipt',type=Path)
    parser.add_argument('--configuration',action='append',type=Path);parser.add_argument('--python',type=Path)
    parser.add_argument('--state-directory',type=Path);parser.add_argument('--launch-agents-directory',type=Path)
    args=parser.parse_args(argv);os.umask(0o077)
    try:
        if args.plan:
            require(bool(args.plan_digest),'PLAN_DIGEST_REQUIRED')
            if args.live:result=install_plan(args.plan,args.plan_digest)
            else:
                plan=validate_plan(args.plan,args.plan_digest)
                result={'state':'PREVIEW','plan_digest':plan['plan_digest'],'labels':[row['label'] for row in plan['jobs']],'launchd_changes':0}
        else:
            require(not args.live and all(value is not None for value in (args.service_id,args.installation_receipt,args.configuration,args.python,args.state_directory)), 'PREPARE_PLAN_BEFORE_INSTALLATION')
            result=prepare_plan(service_id=args.service_id,installation_receipt=args.installation_receipt,configurations=args.configuration,
                python=args.python,state_directory=args.state_directory,launch_agents_directory=args.launch_agents_directory)
        print(json.dumps(result,ensure_ascii=False));return 0 if result.get('state')!='UNKNOWN' else 2
    except (ReleaseHold,ValueError,OSError,TypeError,KeyError):
        print(json.dumps({'state':'HELD','code':'JOB_INSTALLATION_UNVERIFIED'}));return 2


if __name__=='__main__':raise SystemExit(main())
