"""最小 NBT 解析器（只依赖标准库），用于离线核对 BD 网络存档内容。

用法: python nbtdump.py <file.dat> [--path data.UnifiedStorage]
"""
import gzip
import struct
import sys

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG, TAG_FLOAT, TAG_DOUBLE = 0, 1, 2, 3, 4, 5, 6
TAG_BYTE_ARRAY, TAG_STRING, TAG_LIST, TAG_COMPOUND = 7, 8, 9, 10
TAG_INT_ARRAY, TAG_LONG_ARRAY = 11, 12


class Reader:
    def __init__(self, b):
        self.b = b
        self.i = 0

    def u1(self):
        v = self.b[self.i]
        self.i += 1
        return v

    def raw(self, n):
        v = self.b[self.i:self.i + n]
        self.i += n
        return v

    def i1(self):
        return struct.unpack('>b', self.raw(1))[0]

    def i2(self):
        return struct.unpack('>h', self.raw(2))[0]

    def i4(self):
        return struct.unpack('>i', self.raw(4))[0]

    def i8(self):
        return struct.unpack('>q', self.raw(8))[0]

    def f4(self):
        return struct.unpack('>f', self.raw(4))[0]

    def f8(self):
        return struct.unpack('>d', self.raw(8))[0]

    def s(self):
        n = struct.unpack('>H', self.raw(2))[0]
        return self.raw(n).decode('utf-8', 'replace')


def read_payload(r, tid):
    if tid == TAG_BYTE:
        return r.i1()
    if tid == TAG_SHORT:
        return r.i2()
    if tid == TAG_INT:
        return r.i4()
    if tid == TAG_LONG:
        return r.i8()
    if tid == TAG_FLOAT:
        return r.f4()
    if tid == TAG_DOUBLE:
        return r.f8()
    if tid == TAG_BYTE_ARRAY:
        return r.raw(r.i4())
    if tid == TAG_STRING:
        return r.s()
    if tid == TAG_LIST:
        et = r.u1()
        n = r.i4()
        return [read_payload(r, et) for _ in range(n)]
    if tid == TAG_COMPOUND:
        out = {}
        while True:
            t = r.u1()
            if t == TAG_END:
                return out
            name = r.s()
            out[name] = read_payload(r, t)
    if tid == TAG_INT_ARRAY:
        return [r.i4() for _ in range(r.i4())]
    if tid == TAG_LONG_ARRAY:
        return [r.i8() for _ in range(r.i4())]
    raise ValueError('unknown tag %d' % tid)


def load(path):
    b = open(path, 'rb').read()
    if b[:2] == b'\x1f\x8b':
        b = gzip.decompress(b)
    r = Reader(b)
    t = r.u1()
    if t != TAG_COMPOUND:
        raise ValueError('root is not compound: %d' % t)
    r.s()
    return read_payload(r, TAG_COMPOUND)


def walk(node, prefix=''):
    if isinstance(node, dict):
        for k, v in node.items():
            walk(v, prefix + '.' + k if prefix else k)
    elif isinstance(node, list):
        for idx, v in enumerate(node):
            walk(v, '%s[%d]' % (prefix, idx))
    else:
        s = repr(node)
        if len(s) > 200:
            s = s[:200] + '...'
        print('%s = %s' % (prefix, s))


if __name__ == '__main__':
    path = sys.argv[1]
    root = load(path)
    if '--path' in sys.argv:
        p = sys.argv[sys.argv.index('--path') + 1]
        node = root
        for part in p.split('.'):
            node = node[part]
        walk(node, p)
    else:
        walk(root)
