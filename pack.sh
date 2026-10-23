#!/usr/bin/env bash
# 打包“干净源码”：白名单方式，只收代码与文档，绝不收运行时数据
set -euo pipefail
P=${1:-/sdcard/bridgeapp-lite}
V=$(grep -o 'versionName="[^"]*"' $P/AndroidManifest.xml | head -1 | sed 's/.*="//;s/"//')
OUT=${2:-/sdcard/Download}/stepbridge-src-$V.tar.gz
python3 - "$P" "$OUT" <<'PY'
import os, sys, tarfile
root, out = sys.argv[1], sys.argv[2]
include = ['src', 'res', 'assets', 'AndroidManifest.xml', 'README.md', 'SECURITY.md',
           'LICENSE', 'CHANGELOG.md', 'ROADMAP.md', 'TOOLCHAIN.md', 'build.sh', 'pack.sh', '.gitignore']
skip_ext = ('.jsonl', '.png', '.jpg', '.apk', '.docx', '.idsig', '.zip', '.tar', '.gz')
skip_name = {'token.txt', 'status.json', 'config.json'}
added = 0
with tarfile.open(out, 'w:gz') as tf:
    for item in include:
        p = os.path.join(root, item)
        if not os.path.exists(p):
            continue
        if os.path.isfile(p):
            if os.path.basename(p) in skip_name or p.endswith(skip_ext):
                continue
            tf.add(p, arcname=item); added += 1
            continue
        for dirpath, dirnames, filenames in os.walk(p):
            dirnames[:] = [d for d in dirnames if d not in ('build', 'dist', '.git')]
            for f in filenames:
                if f in skip_name or f.endswith(skip_ext):
                    continue
                full = os.path.join(dirpath, f)
                tf.add(full, arcname=os.path.relpath(full, root))
                added += 1
print('entries:', added)
print('out:', out)
PY
ls -l "$OUT"
