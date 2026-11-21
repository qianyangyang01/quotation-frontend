"""Conservative, byte-exact worker/schema compatibility gate. No database writes."""
import hashlib
import io
import json
import re
import struct
import zipfile

PREFIX = "BOOT-INF/classes/"
ROOTS = ["imports/AsyncPurchaseImportProcessor", "logistics/LogisticsImportService",
         "logistics/LogisticsUploadService", "logistics/LogisticsPriceHistoryWorker",
         "storage/AssetStorageService", "purchase/ShimoSyncService"]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def contract_hash(name, data):
    # Git on Windows uses CRLF; Flyway itself calculates checksums by logical lines.
    if name.endswith((".sql", ".yml", ".yaml", ".properties")):
        data = data.replace(b"\r\n", b"\n")
    return sha(data)


def constants(data):
    """Read JVM constant-pool UTF8 entries, including descriptors and generics."""
    stream = io.BytesIO(data)
    if stream.read(4) != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid JVM class")
    stream.read(4)
    count = struct.unpack(">H", stream.read(2))[0]
    index = 1
    strings = []
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = stream.read(1)[0]
        if tag == 1:
            size = struct.unpack(">H", stream.read(2))[0]
            strings.append(stream.read(size).decode("utf-8", errors="replace"))
        else:
            stream.read(sizes[tag])
        index += 2 if tag in (5, 6) else 1
    return strings


def entries(path):
    with zipfile.ZipFile(path) as archive:
        return {name: archive.read(name) for name in archive.namelist()
                if not name.endswith("/") and name.startswith((PREFIX, "BOOT-INF/lib/"))}


def closure(files):
    pending = [PREFIX + "com/milano/quotation/" + root + ".class" for root in ROOTS]
    seen = set()
    while pending:
        name = pending.pop()
        if name in seen or name not in files:
            continue
        seen.add(name)
        for text in constants(files[name]):
            pending.extend(PREFIX + ref + ".class" for ref in
                           re.findall(r"com/milano/quotation/[\w/$]+", text))
        # Inner classes may only be discovered reflectively by the outer class.
        pending.extend(n for n in files if n.startswith(name[:-6] + "$"))
    return seen


def verify(worker_jar, candidate_jar, adapters=None):
    old, new = entries(worker_jar), entries(candidate_jar)
    protected = closure(old) | closure(new)
    # Dependencies and resource contracts (including migrations) must be identical.
    protected |= {n for n in old.keys() | new.keys() if n.startswith("BOOT-INF/lib/")
                  or (n.startswith(PREFIX) and not n.endswith(".class"))}
    adapters = adapters or []
    approved = {(x["path"], x["worker_sha256"], x["web_sha256"]) for x in adapters}
    changed, adapted = [], []
    for name in sorted(protected):
        before, after = contract_hash(name, old.get(name, b"")), contract_hash(name, new.get(name, b""))
        if before != after:
            if (name, before, after) in approved and name.endswith(".class"):
                adapted.append(name)
            else:
                changed.append(name)
    if changed:
        raise ValueError("Worker/schema compatibility changed; use a separately rehearsed maintenance release: "
                         + json.dumps(changed, ensure_ascii=False))
    capability = PREFIX + "com/milano/quotation/common/WebReleaseConfiguration.class"
    if capability not in new:
        raise ValueError("Candidate has no verified web-only runtime")
    return {"protected_entries": len(protected), "approved_adapters": adapted,
            "worker_jar_sha256": sha(open(worker_jar, "rb").read()),
            "web_jar_sha256": sha(open(candidate_jar, "rb").read())}
