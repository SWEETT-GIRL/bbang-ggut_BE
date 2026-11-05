"""검증한 기준선 묶음을 관리한다. 최초 생성은 cloud_baseline, 이후 승격은 완료된 회차만 허용한다."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys

from batch_execution import atomic_json, utc_now
from cloud_public_pull import DATASETS, file_hash

FACTS = ('matching/localdata_mapping.tsv', 'matching/new_review_judgments.jsonl', 'matching/registered_links.tsv')


def allowed_files(datasets):
    if not datasets or set(datasets) - set(DATASETS): raise ValueError('explicit supported datasets required')
    return (*FACTS, *(f'matching/localdata_raw/{name}.jsonl' for name in sorted(set(datasets))))


def validate(root, datasets=None):
    root = Path(root).resolve()
    manifest = json.loads((root / 'manifest.json').read_text())
    names = allowed_files(manifest['datasets'])
    if manifest.get('schema_version') != 1 or set(manifest['files']) != set(names):
        raise ValueError('seed manifest file set mismatch')
    if datasets and not set(datasets) <= set(manifest['datasets']): raise ValueError('seed has no requested dataset')
    for name in names:
        path = root / name
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root):
            raise ValueError('seed must contain regular files inside its directory')
        if path.stat().st_size != manifest['files'][name]['bytes'] or file_hash(path) != manifest['files'][name]['sha256']:
            raise ValueError('seed checksum mismatch: ' + name)
    return manifest


def create_bundle(source, output, datasets):
    source, output = source.resolve(), output.resolve()
    if source == output or output.is_relative_to(source): raise ValueError('export outside the source repository')
    if (output / 'manifest.json').exists(): raise ValueError('existing bundle is immutable; use a new output directory')
    output.mkdir(parents=True, exist_ok=True)
    entries = {}
    for name in allowed_files(datasets):
        dest = output / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        if dest.exists(): raise ValueError('refuse to replace an existing bundle file')
        path = source / name
        if path.is_symlink() or not path.is_file(): raise ValueError('missing regular baseline input: ' + name)
        shutil.copyfile(path, dest)
        with dest.open('rb') as stream: os.fsync(stream.fileno())
        entries[name] = {'bytes': dest.stat().st_size, 'sha256': file_hash(dest)}
    manifest = {'schema_version': 1, 'created_at': utc_now(), 'datasets': sorted(set(datasets)), 'files': entries,
                'purpose': 'baseline and identity evidence; never an approval to mutate serving data'}
    atomic_json(output / 'manifest.json', manifest)
    validate(output, datasets)
    return manifest


def promote(cycle, output):
    """후속 후보/폐업 처리까지 완료한 회차만 다음 비교 기준으로 수동 승격한다."""
    scope = json.loads((cycle / 'scope.json').read_text())
    state = json.loads((cycle / 'checkpoint.json').read_text())
    required = {'pull', 'new-delta', 'verify', 'bread', 'confirm', 'candidates', 'closures'}
    if scope['track'] != 'public' or not required <= state['stages'].keys():
        raise ValueError('complete public cycle required')
    for stage in required:
        result = state['stages'][stage]
        if result['exit_code'] != 0: raise ValueError('incomplete cycle cannot become a baseline')
        for name, expected in result['outputs'].items():
            path = cycle / name
            if not path.resolve().is_relative_to(cycle.resolve()) or file_hash(path) != expected:
                raise ValueError('cycle artifact changed')
    return create_bundle(cycle, output, scope['datasets'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    check = commands.add_parser('verify')
    check.add_argument('directory', type=Path)
    advance = commands.add_parser('promote')
    advance.add_argument('--cycle', type=Path, required=True)
    advance.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'promote':
            result = promote(args.cycle, args.output)
        else:
            result = validate(args.directory)
        print(json.dumps({'files': len(result['files']), 'bytes': sum(row['bytes'] for row in result['files'].values()),
                          'datasets': result['datasets']}, ensure_ascii=False))
        return 0
    except Exception as error:
        print('seed preparation failed: ' + type(error).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
