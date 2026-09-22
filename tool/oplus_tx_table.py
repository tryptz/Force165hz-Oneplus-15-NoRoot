#!/usr/bin/env python3
"""What a build numbers the vendor calls with, from its own OTA.

Every transaction code this app sends was recovered from one build's
`oplus-framework.jar`. AIDL numbers a method by its position in the interface,
so another build that declares one method fewer ahead of it shifts the whole
table (issue #17: a Nord 6 numbers `requestGameRefreshRate` 11, not 12). This
answers that for any build, off a published full OTA, without flashing it and
without downloading all 8 GB of it:

    # what is in the package, and how big each partition is
    ./oplus_tx_table.py partitions <ota-url>

    # rebuild one partition, fetching only the bytes that partition owns
    ./oplus_tx_table.py fetch <ota-url> system -o system.img
    mount -o loop,ro system.img /mnt/x     # or: fsck.erofs --extract=x system.img

    # read the table out of the jar
    ./oplus_tx_table.py table /mnt/x/system/framework/oplus-framework.jar \
        com.oplus.screenmode.IOplusScreenMode

The OTA URL can come from Oxygen Updater's public API, which is how the Nord 6
build in the README was found:

    curl -s https://oxygenupdater.com/api/v2.10/devices/enabled        # find the device id
    curl -s https://oxygenupdater.com/api/v2.10/updateMethods/<id>     # 'Stable (full)'
    curl -s https://oxygenupdater.com/api/v2.10/mostRecentUpdateData/<id>/<method>

The URLs it hands back are signed and expire within the day; re-query for a
fresh one rather than resuming an old one.

Needs nothing but python3 and curl. The `fetch` step verifies what it rebuilt
against the sha256 the payload's own manifest carries, so a truncated download
fails loudly instead of producing an image that half-mounts.
"""
import bz2, hashlib, io, lzma, os, struct, subprocess, sys, zipfile


# ---------------------------------------------------------------- remote file

class HttpFile(io.RawIOBase):
    """Seekable read-only file over HTTP range requests, backed by curl.

    OTA zips store `payload.bin` uncompressed, so a range over the zip is a
    range over the payload: the manifest is a few hundred KB at the front, and
    each partition's data is one contiguous run inside it.
    """

    def __init__(self, url):
        self.url, self._pos = url, 0
        out = subprocess.run(["curl", "-sS", "--max-time", "60", "-r", "0-0", "-D", "-",
                              "-o", "/dev/null", url], capture_output=True, text=True).stdout
        for line in out.splitlines():
            if line.lower().startswith("content-range:"):
                self._size = int(line.split("/")[-1].strip())
                break
        else:
            raise SystemExit("no Content-Range: the URL may have expired (they are signed)")

    seekable = readable = lambda self: True
    def tell(self): return self._pos
    @property
    def size(self): return self._size

    def seek(self, off, whence=os.SEEK_SET):
        self._pos = off if whence == os.SEEK_SET else (
            self._pos + off if whence == os.SEEK_CUR else self._size + off)
        return self._pos

    def read(self, n=-1):
        n = self._size - self._pos if n is None or n < 0 else min(n, self._size - self._pos)
        if n <= 0: return b""
        data = self.fetch(self._pos, n)
        self._pos += len(data)
        return data

    def readinto(self, b):
        data = self.read(len(b))
        b[:len(data)] = data
        return len(data)

    def fetch(self, off, n, retries=4):
        for _ in range(retries):
            p = subprocess.run(["curl", "-sS", "--max-time", "600",
                                "-r", f"{off}-{off+n-1}", self.url], capture_output=True)
            if p.returncode == 0 and len(p.stdout) == n:
                return p.stdout
        raise IOError(f"range {off}+{n} failed")


def payload_offset(f):
    """Where payload.bin's bytes start inside the OTA zip."""
    z = zipfile.ZipFile(f)
    i = z.getinfo("payload.bin")
    f.seek(i.header_offset)
    _sig, _v, _fl, _c, _t, _d, _crc, _cs, _us, nlen, elen = struct.unpack("<IHHHHHIIIHH", f.read(30))
    return i.header_offset + 30 + nlen + elen


# -------------------------------------------------------------- payload.bin

def _varint(b, i):
    v = s = 0
    while True:
        x = b[i]; i += 1
        v |= (x & 0x7f) << s
        if not x & 0x80: return v, i
        s += 7


def _fields(b):
    """Just enough protobuf to walk update_metadata.proto: (field number, value)."""
    i = 0
    while i < len(b):
        tag, i = _varint(b, i)
        fn, wt = tag >> 3, tag & 7
        if wt == 0: v, i = _varint(b, i)
        elif wt == 2:
            n, i = _varint(b, i); v = b[i:i+n]; i += n
        elif wt == 5: v = struct.unpack("<I", b[i:i+4])[0]; i += 4
        elif wt == 1: v = struct.unpack("<Q", b[i:i+8])[0]; i += 8
        else: raise ValueError(f"wire type {wt}")
        yield fn, v


OPS = {0: "REPLACE", 1: "REPLACE_BZ", 6: "ZERO", 8: "REPLACE_XZ"}


class Payload:
    """DeltaArchiveManifest: block_size=3, partitions=13; PartitionUpdate:
    name=1, new_partition_info=7 (size=1, hash=2), operations=8; InstallOperation:
    type=1, data_offset=2, data_length=3, dst_extents=6 (start=1, num=2)."""

    def __init__(self, f, base):
        self.f, self.base = f, base
        magic, _ver, manifest_size = struct.unpack(">4sQQ", f.fetch(base, 20))
        if magic != b"CrAU": raise SystemExit("not an A/B payload")
        sig_size = struct.unpack(">I", f.fetch(base + 20, 4))[0]
        blob = f.fetch(base + 24, manifest_size)
        self.data_base = base + 24 + manifest_size + sig_size
        self.block_size, self.partitions = 4096, []
        for fn, v in _fields(blob):
            if fn == 3: self.block_size = v
            elif fn == 13: self.partitions.append(self._partition(v))

    def _partition(self, blob):
        p = {"name": None, "ops": [], "size": 0, "hash": None}
        for fn, v in _fields(blob):
            if fn == 1: p["name"] = v.decode()
            elif fn == 7:
                for ifn, iv in _fields(v):
                    if ifn == 1: p["size"] = iv
                    elif ifn == 2: p["hash"] = iv
            elif fn == 8: p["ops"].append(self._op(v))
        return p

    def _op(self, blob):
        o = {"type": 0, "data_offset": 0, "data_length": 0, "dst": []}
        for fn, v in _fields(blob):
            if fn == 1: o["type"] = v
            elif fn == 2: o["data_offset"] = v
            elif fn == 3: o["data_length"] = v
            elif fn == 6:
                e = [0, 0]
                for efn, ev in _fields(v):
                    if efn == 1: e[0] = ev
                    elif efn == 2: e[1] = ev
                o["dst"].append(tuple(e))
        return o

    def get(self, name):
        for p in self.partitions:
            if p["name"] == name: return p
        raise SystemExit(f"no partition {name}")


def fetch_partition(url, name, out):
    f = HttpFile(url)
    p = Payload(f, payload_offset(f))
    part = p.get(name)
    ops = sorted(part["ops"], key=lambda o: o["data_offset"])
    lo = min(o["data_offset"] for o in ops if o["data_length"])
    hi = max(o["data_offset"] + o["data_length"] for o in ops if o["data_length"])
    blob = out + ".blob"
    print(f"{name}: {len(ops)} ops, {hi-lo:,} bytes of {f.size:,}")
    # resumable: these CDNs drop long transfers, and the signed URL expires
    for attempt in range(6):
        have = os.path.getsize(blob) if os.path.exists(blob) else 0
        if have >= hi - lo: break
        with open(blob, "ab") as fh:
            subprocess.run(["curl", "-sS", "--max-time", "3600",
                            "-r", f"{p.data_base+lo+have}-{p.data_base+hi-1}", url], stdout=fh)
        print(f"  {os.path.getsize(blob):,} / {hi-lo:,}")
    if os.path.getsize(blob) != hi - lo:
        raise SystemExit("download incomplete: get a fresh URL and rerun")
    with open(blob, "rb") as src, open(out, "wb") as dst:
        dst.truncate(part["size"])
        for o in ops:
            raw = b""
            if o["data_length"]:
                src.seek(o["data_offset"] - lo)
                raw = src.read(o["data_length"])
            t = o["type"]
            data = (raw if t == 0 else bz2.decompress(raw) if t == 1 else
                    lzma.decompress(raw) if t == 8 else None if t == 6 else
                    sys.exit(f"unhandled op {OPS.get(t, t)}"))
            pos = 0
            for start, num in o["dst"]:
                n = num * p.block_size
                dst.seek(start * p.block_size)
                dst.write(b"\0" * n if data is None else data[pos:pos+n])
                pos += n
    got = hashlib.sha256(open(out, "rb").read()).hexdigest()
    want = part["hash"].hex() if part["hash"] else None
    print(f"{out}: {os.path.getsize(out):,} bytes, sha256 {got[:16]}…",
          "MATCHES the manifest" if got == want else f"MISMATCH (manifest {want})")
    if want and got != want: raise SystemExit(1)
    os.remove(blob)


# --------------------------------------------------------------------- dex

class Dex:
    """Only the parts that hold a constant: string/type/field ids, class_defs,
    and the static_values array where `static final int TRANSACTION_x` lives."""

    def __init__(self, b):
        self.b = b
        if b[:4] != b"dex\n": raise ValueError("not a dex")
        (self.string_ids_size, self.string_ids_off, self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off, self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off, self.class_defs_size, self.class_defs_off,
         _ds, _do) = struct.unpack_from("<14I", b, 56)

    def uleb(self, off):
        v = s = 0
        while True:
            x = self.b[off]; off += 1
            v |= (x & 0x7f) << s
            if not x & 0x80: return v, off
            s += 7

    def string(self, idx):
        off = struct.unpack_from("<I", self.b, self.string_ids_off + 4 * idx)[0]
        _n, off = self.uleb(off)
        return self.b[off:self.b.index(b"\0", off)].decode("utf-8", "replace")

    def type(self, idx):
        return self.string(struct.unpack_from("<I", self.b, self.type_ids_off + 4 * idx)[0])

    def proto(self, idx):
        _sh, ret, poff = struct.unpack_from("<III", self.b, self.proto_ids_off + 12 * idx)
        params = []
        if poff:
            n = struct.unpack_from("<I", self.b, poff)[0]
            params = [self.type(struct.unpack_from("<H", self.b, poff + 4 + 2 * i)[0])
                      for i in range(n)]
        return params, self.type(ret)

    def methods_of(self, descriptor):
        out = {}
        for i in range(self.method_ids_size):
            cls, proto, name = struct.unpack_from("<HHI", self.b, self.method_ids_off + 8 * i)
            if self.type(cls) == descriptor:
                out[self.string(name)] = self.proto(proto)
        return out

    def value(self, off):
        """encoded_value. NULL and BOOLEAN carry no payload byte: reading one
        anyway walks every later value onto the wrong field."""
        at = self.b[off]; off += 1
        vtype, arg = at & 0x1f, at >> 5
        if vtype == 0x1e: return None, off
        if vtype == 0x1f: return bool(arg), off
        if vtype == 0x1c:
            n, off = self.uleb(off)
            out = []
            for _ in range(n):
                v, off = self.value(off); out.append(v)
            return out, off
        if vtype == 0x1d:
            _t, off = self.uleb(off)
            n, off = self.uleb(off)
            for _ in range(n):
                _k, off = self.uleb(off)
                _v, off = self.value(off)
            return "<annotation>", off
        raw = self.b[off:off + arg + 1]; off += arg + 1
        if vtype in (0x00, 0x02, 0x04, 0x06):
            return int.from_bytes(raw, "little", signed=True), off
        if vtype == 0x17:
            return self.string(int.from_bytes(raw, "little")), off
        return int.from_bytes(raw, "little"), off

    def static_fields(self, descriptor):
        for i in range(self.class_defs_size):
            cls, _a, _s, _i, _src, _an, data_off, static_off = struct.unpack_from(
                "<8I", self.b, self.class_defs_off + 32 * i)
            if self.type(cls) != descriptor: continue
            if not data_off: return []
            off = data_off
            n_static, off = self.uleb(off)
            for _ in range(3): _x, off = self.uleb(off)
            fields, idx = [], 0
            for _ in range(n_static):
                diff, off = self.uleb(off)
                _acc, off = self.uleb(off)
                idx += diff
                _c, _t, name = struct.unpack_from("<HHI", self.b, self.field_ids_off + 8 * idx)
                fields.append([self.string(name), None])
            if static_off:
                n, off = self.uleb(static_off)
                for j in range(n):
                    v, off = self.value(off)
                    if j < len(fields): fields[j][1] = v
            return fields
        return None


def dexes(path):
    if path.endswith((".jar", ".apk", ".zip")):
        with zipfile.ZipFile(path) as z:
            return [(n, Dex(z.read(n))) for n in sorted(z.namelist()) if n.endswith(".dex")]
    return [(path, Dex(open(path, "rb").read()))]


def table(path, iface):
    stub, ifc = f"L{iface.replace('.', '/')}$Stub;", f"L{iface.replace('.', '/')};"
    for name, d in dexes(path):
        fields = d.static_fields(stub)
        if not fields: continue
        print(f"{iface}$Stub in {name}")
        methods = d.methods_of(ifc)
        for code, m in sorted((v, n[len("TRANSACTION_"):]) for n, v in fields
                              if n.startswith("TRANSACTION_") and isinstance(v, int)):
            params, ret = methods.get(m, (None, None))
            sig = f"({', '.join(params)}) -> {ret}" if params is not None else ""
            print(f"  {code:4d}  0x{code:02x}  {m}{sig}")
        return
    print(f"{iface}$Stub is not in {path}")


if __name__ == "__main__":
    if len(sys.argv) < 2: sys.exit(__doc__)
    cmd = sys.argv[1]
    if cmd == "partitions":
        f = HttpFile(sys.argv[2])
        p = Payload(f, payload_offset(f))
        rows = sorted(((sum(o["data_length"] for o in x["ops"]), x["name"], x["size"])
                       for x in p.partitions), reverse=True)
        print(f"{'partition':20s} {'image':>14s} {'to download':>14s}")
        for blob, name, size in rows:
            print(f"{name:20s} {size:14,d} {blob:14,d}")
    elif cmd == "fetch":
        out = sys.argv[sys.argv.index("-o") + 1] if "-o" in sys.argv else sys.argv[3] + ".img"
        fetch_partition(sys.argv[2], sys.argv[3], out)
    elif cmd == "table":
        table(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)
