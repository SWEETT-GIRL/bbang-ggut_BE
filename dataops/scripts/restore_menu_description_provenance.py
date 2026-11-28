"""Restore missing description provenance from an operator-reviewed historical evidence bundle.

Dry-run by default. Writes only DataOps provenance/audit/schedule; serving data is read-only.
The bundle is an explicit, hash-pinned transfer of historical evidence, not a guessed source label.
Normal fresh-capture reconciliation and serving CAS perform any subsequent description update.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

from psycopg2.extras import Json, RealDictCursor

import local_db
from cloud_runtime import serving_connect


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def positive(value):
    return type(value) is int and value > 0


def validate_bundle(bundle):
    require(bundle.get('version') == 1, 'unsupported evidence version')
    require(positive(bundle.get('storeId')), 'invalid store identity')
    require(isinstance(bundle.get('externalId'), str) and bundle['externalId'].isdigit(), 'invalid external identity')
    snapshot = bundle['snapshot']
    require(snapshot['storeId'] == bundle['storeId'] and positive(snapshot['id']), 'snapshot identity mismatch')
    require(snapshot['namespace'] == 'legacy-local-dataops', 'legacy snapshot namespace required')
    root = snapshot['raw']['ROOT_QUERY']
    details = [value for key, value in root.items() if key.startswith('placeDetail(')]
    require(len(details) == 1, 'ambiguous place detail')
    detail = details[0]
    base = snapshot['raw'][detail['base']['__ref']]
    require(str(base['id']) == bundle['externalId'], 'raw place identity mismatch')
    references = {row['__ref'] for row in detail['baemin']['menus']}
    entries = bundle['entries']
    require(isinstance(entries, list) and 1 <= len(entries) <= 50, 'select 1-50 descriptions')
    seen = set()
    for entry in entries:
        menu_id = entry['menuId']
        require(positive(menu_id) and menu_id not in seen, 'invalid or repeated menu ID')
        seen.add(menu_id)
        require(entry['rawRef'] in references, 'menu is not a member of this place')
        raw = snapshot['raw'][entry['rawRef']]
        require(raw['__typename'] == 'PlaceDetail_BaeminMenu' and raw['source'] == 'baemin', 'raw source is not BAEMIN')
        require(raw['menuId'] == entry['sourceMenuId'] and raw['name'] == entry['name'], 'raw menu identity mismatch')
        require(isinstance(raw['desc'], str) and raw['desc'] == entry['description'], 'description differs from original raw')
        require(isinstance(entry['servingUpdatedAt'], str) and entry['servingUpdatedAt'], 'serving revision missing')
        proposal, applied, provenance = entry['proposal'], entry['applyLog'], entry['legacyProvenance']
        expected = {'name': entry['name'], 'price': entry['price'], 'description': entry['description']}
        require(int(raw['price']) == entry['price'], 'historical price mismatch')
        require(positive(proposal['id']) and proposal['status'] == 'APPROVED', 'historical approval missing')
        require(positive(applied['id']) and applied['proposalId'] == proposal['id'] and applied['origin'] == 'PROPOSAL', 'application does not match approval')
        target = 'menu.add.' + re.sub(r'\s+', '', entry['name'])
        for record in (proposal, applied):
            require(record['storeId'] == bundle['storeId'] and record['snapshotId'] == snapshot['id'], 'historical chain mismatch')
            require(record['target'] == target and record['newValue'] == expected, 'historical applied value mismatch')
        require(provenance['field'] == f'menu.{menu_id}' and provenance['snapshotId'] == snapshot['id']
                and provenance['storeId'] == bundle['storeId'], 'legacy menu mapping mismatch')
        require(proposal.get('source') in ('NAVER', 'BAEMIN') and provenance.get('source') in ('NAVER', 'BAEMIN'),
                'historical human provenance is protected')
    return entries


def load_bundle(path, expected_sha):
    require(re.fullmatch(r'[a-f0-9]{64}', expected_sha or '') is not None, 'expected SHA256 is required')
    content = Path(path).read_bytes()
    require(hashlib.sha256(content).hexdigest() == expected_sha, 'evidence file hash mismatch')
    bundle = json.loads(content)
    validate_bundle(bundle)
    return bundle


def restore(dataops, serving, bundle, digest, *, execute=False, recheck_store_ids=()):
    entries = validate_bundle(bundle)
    store_id = bundle['storeId']
    stores = sorted(set(recheck_store_ids) | {store_id})
    require(len(stores) <= 50 and all(positive(value) for value in stores), 'invalid recheck store IDs')
    restored = already = 0
    try:
        with dataops.cursor(cursor_factory=RealDictCursor) as cursor, serving.cursor(cursor_factory=RealDictCursor) as reader:
            cursor.execute("SET LOCAL lock_timeout = '3s'")
            cursor.execute("SET LOCAL statement_timeout = '10s'")
            for target in stores:
                # Same key as StoreObservationBarrier: no restoration during capture/approval.
                cursor.execute('SELECT pg_try_advisory_xact_lock(%s) AS locked', (-(1 << 63) | target,))
                require(cursor.fetchone()['locked'], 'store observation/approval is busy')
            cursor.execute("SELECT store_id, external_id, state, "
                           "(last_attempt_at IS NULL OR last_attempt_at < timezone('Asia/Seoul', current_timestamp) - interval '2 hours') AS lease_free "
                           "FROM store_sync_state WHERE source='NAVER' AND store_id = ANY(%s) FOR UPDATE", (stores,))
            states = {row['store_id']: row for row in cursor.fetchall()}
            require(set(states) == set(stores), 'recheck store is not seeded')
            require(all(row['state'] == 'ACTIVE' and row['lease_free'] for row in states.values()), 'recheck store is inactive or leased; retry after the two-hour lease')
            require(states[store_id]['external_id'] == bundle['externalId'], 'registered place changed')
            for entry in entries:
                field, whole = f"menu.{entry['menuId']}.description", f"menu.{entry['menuId']}"
                cursor.execute('SELECT bundle_sha256 FROM menu_provenance_restoration WHERE store_id=%s AND field=%s', (store_id, field))
                audit = cursor.fetchone()
                if audit:
                    require(audit['bundle_sha256'] == digest, 'field already restored using different evidence')
                    already += 1
                    continue
                cursor.execute('SELECT field,source FROM store_field_provenance WHERE store_id=%s AND field = ANY(%s) FOR UPDATE', (store_id, [field, whole]))
                sources = {row['field']: row['source'] for row in cursor.fetchall()}
                require(not sources, 'existing provenance must not be overwritten')
                reader.execute('SELECT m.store_id,m.name,m.price,m.description,m.updated_at::text AS updated_at, '
                               'm.created_by_member_id,m.deleted_at,s.naver_place_id,s.status '
                               'FROM public.menu m JOIN public.store s ON s.id=m.store_id WHERE m.id=%s', (entry['menuId'],))
                current = reader.fetchone()
                require(current is not None and current['store_id'] == store_id and current['naver_place_id'] == bundle['externalId']
                        and current['status'] == 'ACTIVE', 'serving menu/place identity changed')
                require(current['created_by_member_id'] is None and current['deleted_at'] is None, 'user or deleted menu is protected')
                require(all(current[key] == entry[key] for key in ('name', 'price', 'description'))
                        and current['updated_at'] == entry['servingUpdatedAt'], 'serving value/revision changed; re-audit required')
                # No historical snapshot ID is assigned to the cloud snapshot namespace.
                cursor.execute("INSERT INTO store_field_provenance(store_id,field,source,snapshot_id) VALUES (%s,%s,'BAEMIN',NULL)", (store_id, field))
                cursor.execute('INSERT INTO menu_provenance_restoration(store_id,field,bundle_sha256,evidence) VALUES (%s,%s,%s,%s)',
                               (store_id, field, digest, Json({'entry': entry, 'snapshot': bundle['snapshot'], 'externalId': bundle['externalId']})))
                restored += 1
            # A new claim supplies a new attempt sequence and capture. Never rewrite old HELD receipts.
            # StoreSyncState uses Java LocalDateTime in the batch container's Asia/Seoul timezone.
            cursor.execute("UPDATE store_sync_state SET next_due_at=timezone('Asia/Seoul', current_timestamp) "
                           "WHERE source='NAVER' AND store_id=ANY(%s)", (stores,))
        if execute:
            dataops.commit()
        else:
            dataops.rollback()
        return {'mode': 'execute' if execute else 'dry-run', 'restored': restored, 'already_restored': already, 'recheck_store_ids': stores}
    except BaseException:
        dataops.rollback()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--recheck-store-ids', type=int, nargs='*', default=[])
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    bundle = load_bundle(args.bundle, args.sha256)
    dataops, serving = local_db.connect(), None
    try:
        serving = serving_connect()
        print(json.dumps(restore(dataops, serving, bundle, args.sha256, execute=args.execute,
                                 recheck_store_ids=args.recheck_store_ids), ensure_ascii=False))
    finally:
        dataops.close()
        if serving is not None:
            serving.close()


if __name__ == '__main__':
    main()
