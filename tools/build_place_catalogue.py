#!/usr/bin/env python3
"""Convert public repair/fuel CSV snapshots to the app's versioned catalogue. No network/API keys."""
import argparse
import csv
import hashlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path

SOURCES = {
    'REPAIR': 'https://www.data.go.kr/data/15028204/standard.do',
    'FUEL': 'https://www.data.go.kr/data/15129441/standard.do',
}

def convert(path, kind, encoding='utf-8-sig'):
    result, skipped = [], 0
    with Path(path).open(encoding=encoding, newline='') as stream:
        for row in csv.DictReader(stream):
            def field(*names):
                return next((row[n].strip() for n in names if row.get(n) and row[n].strip()), '')
            # Repair standard: 1 = operating, 2 = closed, 3 = suspended.
            state = field('영업상태', '영업상태명')
            if state and state not in ('1', '영업', '정상', '영업/정상'):
                skipped += 1
                continue
            name = field('자동차정비업체명', '주유소명', '사업장명', 'name')
            address = field('소재지도로명주소', '도로명주소', '소재지지번주소', 'address')
            try:
                lat, lon = float(field('위도', 'latitude')), float(field('경도', 'longitude'))
                if not name or not address or not math.isfinite(lat) or not math.isfinite(lon) or not (-90 <= lat <= 90 and -180 <= lon <= 180):
                    raise ValueError('invalid name/address/coordinates')
            except ValueError as error:
                raise ValueError(f'{path}: row {len(result)+skipped+2}: {error}') from error
            identity = f'{kind}|{name}|{address}'
            result.append(dict(id=kind.lower()+':'+hashlib.sha256(identity.encode()).hexdigest()[:24],
                name=name, kind=kind, latitude=lat, longitude=lon, address=address, source=SOURCES[kind]))
    return result, skipped


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repair', required=True, type=Path)
    parser.add_argument('--fuel', required=True, type=Path)
    parser.add_argument('--version', required=True, type=int, help='strictly increasing snapshot version')
    parser.add_argument('--encoding', default='utf-8-sig', help='use cp949 for older public CSV exports')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    repair, rskip = convert(args.repair, 'REPAIR', args.encoding)
    fuel, fskip = convert(args.fuel, 'FUEL', args.encoding)
    places = sorted(repair+fuel, key=lambda p: p['id'])
    if args.version < 1 or not 1 <= len(places) <= 100000 or len({p['id'] for p in places}) != len(places):
        parser.error('version, row count or duplicate IDs are invalid; reconcile source rows first')
    payload = json.dumps(dict(schemaVersion=1, version=args.version,
        publishedAt=datetime.now(timezone.utc).isoformat(), places=places), ensure_ascii=False, separators=(',', ':')).encode()
    if len(payload) > 20*1024*1024:
        parser.error('catalogue exceeds app 20 MiB limit')
    temp = args.output.with_suffix('.tmp')
    temp.write_bytes(payload)
    temp.replace(args.output)
    print(f'{len(repair)} repair + {len(fuel)} fuel; {rskip+fskip} non-operating rows excluded')

if __name__ == '__main__':
    main()
