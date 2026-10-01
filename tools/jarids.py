#!/usr/bin/env python3
"""列出若干 jar 的真实 modId。

只读 `[[mods]]` 段里的 `modId`。**不能**直接全文正则 —— neoforge.mods.toml 里
`[[dependencies.xxx]]` 段也写 `modId`（那是依赖声明），全文捞会把它模组的依赖
当成"本 jar 提供了这个 mod"，进而误判重复/冲突。

用法：python tools/jarids.py <jar> [<jar> ...]
"""
import os
import re
import sys
import zipfile


def real_mod_ids(text):
    """取 [[mods]] 段里的 modId（[[dependencies.*]] 里的是依赖，不算）。"""
    ids = []
    # 按段头切分，只保留段头是 mods]] 的那一段
    for seg in re.split(r'^\[\[', text, flags=re.M)[1:]:
        if re.match(r'mods\]\]', seg):
            m = re.search(r'modId\s*=\s*["\']([^"\']+)["\']', seg)
            if m:
                ids.append(m.group(1))
    return ids


def inspect(path):
    if not os.path.exists(path):
        return None, ['文件不存在']
    try:
        with zipfile.ZipFile(path) as z:
            tomls = [n for n in z.namelist()
                     if n.startswith('META-INF/') and n.endswith('mods.toml')]
            ids = []
            for n in tomls:
                ids += real_mod_ids(z.read(n).decode('utf-8', 'replace'))
            return tomls, ids
    except Exception as e:                                       # noqa: BLE001
        return None, [f'读取失败: {e}']


def main(argv):
    for p in argv:
        tomls, ids = inspect(p)
        print(os.path.basename(p))
        if tomls is not None:
            print(f'    mods.toml: {tomls or "(无)"}')
        print(f'    modId    : {ids or "(未解析到)"}')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
