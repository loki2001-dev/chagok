#!/usr/bin/env python3
"""Build deterministic offline tiles from Geofabrik OSM PBF. Requires osmium==4.3.2."""
import argparse
import collections
import hashlib
import json
import math
import struct
import zipfile
from pathlib import Path

TYPES = {'motorway': 0, 'trunk': 1, 'primary': 2, 'secondary': 2, 'tertiary': 2,
         'unclassified': 2, 'residential': 3, 'living_street': 3, 'service': 4, 'track': 5}
TYPES.update({k + '_link': 6 for k in ('motorway', 'trunk', 'primary', 'secondary', 'tertiary')})
SCALE = 50  # .02 degree tiles. The segment bbox is registered in every overlapping tile.

def tiles(a, b):
    for y in range(math.floor(min(a[0], b[0])*SCALE), math.floor(max(a[0], b[0])*SCALE)+1):
        for x in range(math.floor(min(a[1], b[1])*SCALE), math.floor(max(a[1], b[1])*SCALE)+1):
            yield f'{y}_{x}'

def simplify(points, tolerance=1.0):
    """Iterative Douglas-Peucker, max 1m perpendicular deviation in local projection."""
    if len(points) < 3:
        return points
    xscale = 111195 * math.cos(math.radians(sum(p[0] for p in points)/len(points)))
    xy = [(p[1]*xscale, p[0]*111195) for p in points]
    keep = {0, len(points)-1}
    pending = [(0, len(points)-1)]
    while pending:
        start, end = pending.pop()
        ax, ay = xy[start]
        dx, dy = xy[end][0]-ax, xy[end][1]-ay
        length = dx*dx+dy*dy
        furthest, distance = None, tolerance*tolerance
        for i in range(start+1, end):
            px, py = xy[i][0]-ax, xy[i][1]-ay
            t = max(0, min(1, (px*dx+py*dy)/length)) if length else 0
            squared = (px-t*dx)**2+(py-t*dy)**2
            if squared > distance:
                furthest, distance = i, squared
        if furthest is not None:
            keep.add(furthest)
            pending.extend([(start, furthest), (furthest, end)])
    return [points[i] for i in sorted(keep)]

def digest_file(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()

def build(source, output):
    import osmium
    data = collections.defaultdict(bytearray)
    counts = collections.Counter()
    skipped = collections.Counter()
    class Handler(osmium.SimpleHandler):
        def way(self, way):
            kind = way.tags.get('highway')
            if kind not in TYPES or way.tags.get('area') == 'yes':
                return
            # Keep private/restricted roads as ambiguity candidates, not a routing permission.
            if not all(n.location.valid() for n in way.nodes):
                skipped['missing_geometry'] += 1
                return
            points = simplify([(n.lat, n.lon) for n in way.nodes])
            for a, b in zip(points, points[1:]):
                if a == b:
                    continue
                # Source extract includes neighbouring territory: retain its complete geometry.
                record = struct.pack('>qBiiii', way.id, TYPES[kind],
                                     round(a[0]*1e6), round(a[1]*1e6), round(b[0]*1e6), round(b[1]*1e6))
                for tile in tiles(a, b):
                    data[tile].extend(record)
                counts[kind] += 1
    Handler().apply_file(str(source), locations=True, idx='flex_mem')
    reader = osmium.io.Reader(str(source))
    timestamp = reader.header().get('osmosis_replication_timestamp')
    reader.close()
    manifest = {'format': 1, 'simplificationMeters': 1.0, 'tileScale': SCALE, 'recordBytes': 25,
                'source': 'https://download.geofabrik.de/asia/south-korea.html',
                'sourceTimestamp': timestamp, 'sourceSha256': digest_file(source),
                'attribution': '© OpenStreetMap contributors', 'license': 'https://opendatacommons.org/licenses/odbl/1-0/',
                'tiles': len(data), 'segments': sum(counts.values()), 'classes': dict(sorted(counts.items())), 'excluded': dict(skipped)}
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, content in [('manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2).encode())] + [(k+'.bin', v) for k, v in sorted(data.items())]:
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, content, compresslevel=9)
    output.with_suffix('.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
    output.with_suffix('.sha256').write_text(digest_file(output)+'\n')
    print(json.dumps({**manifest, 'bytes': output.stat().st_size}, ensure_ascii=False))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    build(args.source, args.output)
