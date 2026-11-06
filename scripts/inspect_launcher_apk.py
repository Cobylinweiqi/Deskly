#!/usr/bin/env python3
"""静态提取 Launcher APK 里的数据库线索，无需 jadx / apktool。

只读取字节，绝不执行 APK。DEX 的字符串池是明文 MUTF-8，建表 SQL、
表名常量、数据库文件名、Provider authority 都能直接捞出来。

用法:
    python3 scripts/inspect_launcher_apk.py <app.apk|classes.dex|目录> [--all 输出.txt]

典型用途：确认某台 ROM 的桌面把布局存在哪张表、哪个 .db 文件里，
而不是靠机型名去猜 favorites / workspaceScreens。
"""

import argparse
import os
import re
import sys
import zipfile

CATEGORIES = [
    ("建表 / 改表 SQL", re.compile(r"create\s+table|alter\s+table|drop\s+table|create\s+index", re.I)),
    ("favorites 相关", re.compile(r"favorites", re.I)),
    ("屏表 / workspace", re.compile(r"workspaceScreens|screenRank|screenOrder|\bscreens\b", re.I)),
    ("数据库文件名", re.compile(r"[\w./-]+\.db\b", re.I)),
    ("ContentProvider authority", re.compile(r"content://|authorities|\.settings\b", re.I)),
    ("SQLite / Room 框架", re.compile(r"SQLiteOpenHelper|SQLiteDatabase|RoomDatabase|onUpgrade|onCreate\(SQLite", re.I)),
    ("布局列字段", re.compile(r"itemType|container|cellX|cellY|spanX|spanY|\brank\b", re.I)),
    ("可能的其它布局表", re.compile(r"launcher_favorites|favorite|workspace|icon|hotseat|shortcut", re.I)),
]


def uleb128(buf, pos):
    """返回 (值, 新位置)。DEX 的 ULEB128 最多 5 字节。"""
    value = 0
    shift = 0
    while pos < len(buf):
        b = buf[pos]
        pos += 1
        value |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
        if shift > 35:
            break
    return value, pos


def dex_strings(data):
    """解析 DEX header 的 string_ids，逐个取 string_data_item。"""
    if len(data) < 0x40 or data[:4] not in (b"dex\n", b"dey\n"):
        raise ValueError("不是 DEX 文件（magic 不匹配）")
    import struct
    count, off = struct.unpack_from("<II", data, 0x38)
    if not count or off <= 0 or off + count * 4 > len(data):
        raise ValueError("string_ids 区间越界，DEX 可能被加固或截断")
    out = []
    for i in range(count):
        (item_off,) = struct.unpack_from("<I", data, off + i * 4)
        if item_off <= 0 or item_off >= len(data):
            continue
        _, p = uleb128(data, item_off)
        end = data.find(b"\x00", p)
        if end < 0:
            end = min(len(data), p + 4096)
        try:
            s = data[p:end].decode("utf-8", errors="replace")
        except Exception:
            continue
        if s:
            out.append(s)
    return out


def collect(path):
    """返回 [(来源名, 字符串列表)]，并收集 assets 里的 .sql/.db 名字。"""
    result = []
    extras = []
    if os.path.isdir(path):
        for name in sorted(os.listdir(path)):
            if name.endswith((".dex", ".apk")):
                sub, sub_extras = collect(os.path.join(path, name))
                result.extend(sub)
                extras.extend(sub_extras)
        return result, extras
    if path.lower().endswith(".dex"):
        with open(path, "rb") as f:
            return [(os.path.basename(path), dex_strings(f.read()))], extras
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                try:
                    result.append((name, dex_strings(z.read(name))))
                except ValueError as e:
                    print("[跳过] %s: %s" % (name, e), file=sys.stderr)
            elif re.search(r"\.(sql|db|sqlite)$", name, re.I):
                extras.append(name)
    return result, extras


def main():
    ap = argparse.ArgumentParser(description="静态提取 Launcher APK 的数据库线索")
    ap.add_argument("target", help="APK / DEX / 目录")
    ap.add_argument("--all", help="把所有 DEX 字符串写到这个文件，便于自行检索")
    args = ap.parse_args()

    groups, extras = collect(args.target)
    if not groups:
        print("没找到任何可读的 DEX。可能被加固、用了 split APK，或路径不对。", file=sys.stderr)
        return 2

    all_strings = []
    for _, strings in groups:
        all_strings.extend(strings)
    unique = list(dict.fromkeys(all_strings))
    print("DEX %d 个，字符串 %d 条（去重后 %d）" %
          (len(groups), len(all_strings), len(unique)))
    if extras:
        print("包内 SQL / DB 资产: %s" % ", ".join(extras[:20]))

    if args.all:
        with open(args.all, "w", encoding="utf-8") as f:
            f.write("\n".join(unique))
        print("全量字符串已写入 %s" % args.all)

    for title, pattern in CATEGORIES:
        hits = [s for s in unique if pattern.search(s)]
        print("\n===== %s (%d) =====" % (title, len(hits)))
        for s in hits[:40]:
            print("  " + s.replace("\n", " ")[:300])
        if len(hits) > 40:
            print("  ... 还有 %d 条" % (len(hits) - 40))
    return 0


if __name__ == "__main__":
    sys.exit(main())
