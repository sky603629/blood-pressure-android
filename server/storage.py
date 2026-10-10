"""SQLite storage with atomic device snapshots and hashed credentials."""
from contextlib import closing, contextmanager
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import hmac
import json
import secrets
import sqlite3
import uuid


def token_digest(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


class RecordConflict(Exception):
    pass


class SnapshotConflict(Exception):
    pass


def local_record_id(installation_id, local_id):
    # Matches Java UUID.nameUUIDFromBytes; this is an identifier, not a security hash.
    digest = hashlib.md5(f"{installation_id}:{local_id}".encode("utf-8")).digest()
    return str(uuid.UUID(bytes=digest, version=3))


class Store:
    def __init__(self, path: str):
        self.path = Path(path)

    @contextmanager
    def connection(self):
        with closing(sqlite3.connect(self.path, timeout=10)) as db:
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA foreign_keys = ON")
            with db:
                yield db

    def initialize(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            self.path.chmod(0o600)
            version = db.execute("PRAGMA user_version").fetchone()[0]
            if version > 2:
                raise RuntimeError("Database was created by a newer service version")
            db.execute("PRAGMA journal_mode = WAL")
            if version == 0:
                db.executescript("""
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE, enabled INTEGER NOT NULL DEFAULT 1);
                CREATE TABLE IF NOT EXISTS measurements (
                    device_id TEXT NOT NULL REFERENCES devices(id),
                    record_id TEXT NOT NULL, systolic INTEGER NOT NULL,
                    diastolic INTEGER NOT NULL, pulse INTEGER NOT NULL,
                    measured_at INTEGER NOT NULL, note TEXT NOT NULL,
                    time_zone TEXT NOT NULL, utc_offset_minutes INTEGER NOT NULL,
                    source TEXT NOT NULL, received_at INTEGER NOT NULL,
                    PRIMARY KEY(device_id, record_id));
                CREATE INDEX IF NOT EXISTS idx_measurements_time
                    ON measurements(measured_at DESC);
                PRAGMA user_version = 1;
                """)
            if version < 2:
                db.execute("BEGIN IMMEDIATE")
                db.execute("ALTER TABLE devices ADD COLUMN installation_id TEXT")
                db.execute("ALTER TABLE devices ADD COLUMN snapshot_version INTEGER NOT NULL DEFAULT 0")
                db.execute("ALTER TABLE devices ADD COLUMN snapshot_digest TEXT NOT NULL DEFAULT ''")
                db.execute("ALTER TABLE measurements ADD COLUMN snapshot_version INTEGER NOT NULL DEFAULT 0")
                db.execute("""CREATE TABLE sync_jobs (
                    id TEXT PRIMARY KEY, device_id TEXT NOT NULL UNIQUE REFERENCES devices(id),
                    installation_id TEXT NOT NULL, version INTEGER NOT NULL,
                    record_count INTEGER NOT NULL, high_watermark INTEGER NOT NULL,
                    digest TEXT NOT NULL, created_at INTEGER NOT NULL)""")
                db.execute("""CREATE TABLE sync_pages (
                    job_id TEXT NOT NULL REFERENCES sync_jobs(id) ON DELETE CASCADE,
                    page_index INTEGER NOT NULL, body BLOB NOT NULL, digest TEXT NOT NULL,
                    PRIMARY KEY(job_id,page_index))""")
                db.execute("PRAGMA user_version = 2")

    def create_admin(self, *, rotate=False):
        token = secrets.token_urlsafe(32)
        with self.connection() as db:
            existing = db.execute("SELECT 1 FROM settings WHERE key='admin_token_hash'").fetchone()
            if existing and not rotate:
                raise ValueError("An administrator key already exists; use rotate-admin explicitly")
            db.execute("INSERT OR REPLACE INTO settings(key,value) VALUES('admin_token_hash',?)",
                       (token_digest(token),))
        return token

    def create_device(self, name):
        name = name.strip()
        if not 1 <= len(name) <= 80 or any(ord(c) < 32 for c in name):
            raise ValueError("Member name must contain 1-80 printable characters")
        token, device_id = secrets.token_urlsafe(32), str(uuid.uuid4())
        with self.connection() as db:
            db.execute("INSERT INTO devices(id,name,token_hash) VALUES(?,?,?)",
                       (device_id, name, token_digest(token)))
        return {"id": device_id, "name": name, "token": token}

    def authenticate_admin(self, token):
        with self.connection() as db:
            row = db.execute("SELECT value FROM settings WHERE key='admin_token_hash'").fetchone()
        if not row:
            return False
        if row[0].startswith("pbkdf2$"):
            _, salt, expected = row[0].split("$")
            actual = hashlib.pbkdf2_hmac("sha256", token.encode("utf-8"), bytes.fromhex(salt), 210000).hex()
            return hmac.compare_digest(expected, actual)
        return hmac.compare_digest(row[0], token_digest(token))

    def set_admin_key(self, key):
        if not 8 <= len(key) <= 256 or any(ord(c) <= 32 or ord(c) >= 127 for c in key):
            raise ValueError("Viewing key must contain 8-256 non-space ASCII characters")
        salt = secrets.token_bytes(16)
        digest = hashlib.pbkdf2_hmac("sha256", key.encode("utf-8"), salt, 210000).hex()
        with self.connection() as db:
            db.execute("INSERT OR REPLACE INTO settings(key,value) VALUES('admin_token_hash',?)",
                       (f"pbkdf2${salt.hex()}${digest}",))

    def authenticate_device(self, token):
        with self.connection() as db:
            row = db.execute("SELECT id,name FROM devices WHERE token_hash=? AND enabled=1",
                             (token_digest(token),)).fetchone()
        return dict(row) if row else None

    def disable_device(self, device_id):
        with self.connection() as db:
            return db.execute("UPDATE devices SET enabled=0 WHERE id=?", (device_id,)).rowcount

    def devices(self):
        with self.connection() as db:
            return [dict(row) for row in db.execute("""
                SELECT d.id,d.name,d.enabled,COUNT(m.record_id) AS recordCount
                FROM devices d LEFT JOIN measurements m ON m.device_id=d.id
                GROUP BY d.id ORDER BY d.name,d.id
            """)]

    def save(self, device_id, record):
        fields = ("recordId", "systolic", "diastolic", "pulse", "measuredAt", "note",
                  "timeZone", "utcOffsetMinutes", "source")
        values = tuple(record[key] for key in fields)
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            device = db.execute("SELECT installation_id FROM devices WHERE id=?", (device_id,)).fetchone()
            if device and device[0] is not None:
                raise RecordConflict()
            cursor = db.execute("""
                INSERT OR IGNORE INTO measurements
                (device_id,record_id,systolic,diastolic,pulse,measured_at,note,
                 time_zone,utc_offset_minutes,source,received_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """, (device_id, *values, int(datetime.now(timezone.utc).timestamp() * 1000)))
            if cursor.rowcount:
                return True
            existing = db.execute("""
                SELECT record_id,systolic,diastolic,pulse,measured_at,note,
                       time_zone,utc_offset_minutes,source FROM measurements
                WHERE device_id=? AND record_id=?
            """, (device_id, record["recordId"])).fetchone()
            if existing is None or tuple(existing) != values:
                raise RecordConflict()
        return False

    def begin_snapshot(self, device_id, metadata):
        installation, version, count, watermark, digest = (metadata[k] for k in
            ("installationId", "version", "recordCount", "highWatermark", "digest"))
        now = int(datetime.now(timezone.utc).timestamp() * 1000)
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            device = db.execute("SELECT * FROM devices WHERE id=? AND enabled=1", (device_id,)).fetchone()
            if not device or (device["installation_id"] is not None and device["installation_id"] != installation):
                raise SnapshotConflict("This upload key is already paired with another installation")
            if version < device["snapshot_version"]:
                raise SnapshotConflict("Outdated snapshot")
            if version == device["snapshot_version"]:
                actual_count = db.execute("SELECT COUNT(*) FROM measurements WHERE device_id=?", (device_id,)).fetchone()[0]
                if digest != device["snapshot_digest"] or count != actual_count:
                    raise SnapshotConflict("Snapshot version has different contents")
                return {"committed": True, "version": version, "digest": digest, "recordCount": count}
            if device["installation_id"] is None and count == 0 and watermark == 0 and db.execute(
                    "SELECT 1 FROM measurements WHERE device_id=? LIMIT 1", (device_id,)).fetchone():
                raise SnapshotConflict("An empty new installation cannot replace existing server records")
            db.execute("DELETE FROM sync_jobs WHERE created_at<?", (now-86400000,))
            job = db.execute("SELECT * FROM sync_jobs WHERE device_id=?", (device_id,)).fetchone()
            if job:
                if job["installation_id"] != installation or job["version"] > version:
                    raise SnapshotConflict("Another installation or newer snapshot is in progress")
                if job["version"] == version:
                    if (job["digest"],job["record_count"],job["high_watermark"]) != (digest,count,watermark):
                        raise SnapshotConflict("Snapshot version has different contents")
                    return {"committed": False, "syncId":job["id"], "version":version, "digest":digest}
                db.execute("DELETE FROM sync_jobs WHERE id=?", (job["id"],))
            sync_id = str(uuid.uuid4())
            db.execute("INSERT INTO sync_jobs VALUES(?,?,?,?,?,?,?,?)",
                       (sync_id,device_id,installation,version,count,watermark,digest,now))
            return {"committed":False, "syncId":sync_id, "version":version, "digest":digest}

    def stage_page(self, device_id, sync_id, index, body, records):
        page_digest = hashlib.sha256(body).hexdigest()
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            job = db.execute("SELECT * FROM sync_jobs WHERE id=? AND device_id=?", (sync_id,device_id)).fetchone()
            if not job:
                raise SnapshotConflict("Snapshot not found or replaced")
            pages = (job["record_count"]+24)//25
            expected_count = min(25,job["record_count"]-index*25)
            if not 0 <= index < pages or len(records) != expected_count:
                raise SnapshotConflict("Page does not match the declared snapshot")
            for item in records:
                if (item["localId"] > job["high_watermark"] or item["recordId"] !=
                        local_record_id(job["installation_id"], item["localId"])):
                    raise SnapshotConflict("Record does not belong to this installation")
            existing = db.execute("SELECT digest FROM sync_pages WHERE job_id=? AND page_index=?",
                                  (sync_id,index)).fetchone()
            if existing and existing[0] != page_digest:
                raise SnapshotConflict("Page was already received with different contents")
            normalized = json.dumps({"records":records}, ensure_ascii=False, separators=(",",":")).encode("utf-8")
            db.execute("INSERT OR IGNORE INTO sync_pages VALUES(?,?,?,?)", (sync_id,index,normalized,page_digest))
        return {"staged":True,"syncId":sync_id,"pageIndex":index}

    def commit_snapshot(self, device_id, sync_id):
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            job = db.execute("SELECT * FROM sync_jobs WHERE id=? AND device_id=?", (sync_id,device_id)).fetchone()
            if not job:
                raise SnapshotConflict("Snapshot not found or replaced")
            device = db.execute("SELECT * FROM devices WHERE id=? AND enabled=1", (device_id,)).fetchone()
            if (not device or job["version"] <= device["snapshot_version"] or
                    (device["installation_id"] is not None and device["installation_id"] != job["installation_id"])):
                raise SnapshotConflict("Outdated or unauthorized snapshot")
            pages = db.execute("SELECT page_index,digest FROM sync_pages WHERE job_id=? ORDER BY page_index", (sync_id,)).fetchall()
            if len(pages) != (job["record_count"]+24)//25:
                raise SnapshotConflict("Snapshot is incomplete")
            fingerprint = hashlib.sha256()
            for page in pages:
                fingerprint.update((page["digest"]+"\n").encode("ascii"))
            if fingerprint.hexdigest() != job["digest"]:
                raise SnapshotConflict("Snapshot checksum does not match")
            previous_id, total = 0, 0
            now = int(datetime.now(timezone.utc).timestamp()*1000)
            for page in pages:
                body = db.execute("SELECT body FROM sync_pages WHERE job_id=? AND page_index=?",
                                  (sync_id,page["page_index"])).fetchone()[0]
                for item in json.loads(body)["records"]:
                    if item["localId"] <= previous_id:
                        raise SnapshotConflict("Records must be unique and ordered from old to new")
                    previous_id = item["localId"]
                    total += 1
                    db.execute("""INSERT INTO measurements
                        (device_id,record_id,systolic,diastolic,pulse,measured_at,note,
                         time_zone,utc_offset_minutes,source,received_at,snapshot_version)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(device_id,record_id) DO UPDATE SET
                        systolic=excluded.systolic,diastolic=excluded.diastolic,pulse=excluded.pulse,
                        measured_at=excluded.measured_at,note=excluded.note,
                        time_zone=CASE WHEN excluded.source='legacy' AND excluded.measured_at=measurements.measured_at THEN measurements.time_zone ELSE excluded.time_zone END,
                        utc_offset_minutes=CASE WHEN excluded.source='legacy' AND excluded.measured_at=measurements.measured_at THEN measurements.utc_offset_minutes ELSE excluded.utc_offset_minutes END,
                        source=CASE WHEN excluded.source='legacy' THEN measurements.source ELSE excluded.source END,
                        snapshot_version=excluded.snapshot_version
                        """, (device_id,item["recordId"],item["systolic"],item["diastolic"],item["pulse"],
                              item["measuredAt"],item["note"],item["timeZone"],item["utcOffsetMinutes"],
                              item["source"],now,job["version"]))
            if total != job["record_count"]:
                raise SnapshotConflict("Snapshot record count does not match")
            deleted = db.execute("DELETE FROM measurements WHERE device_id=? AND snapshot_version<?",
                                 (device_id,job["version"])).rowcount
            db.execute("UPDATE devices SET installation_id=?,snapshot_version=?,snapshot_digest=? WHERE id=?",
                       (job["installation_id"],job["version"],job["digest"],device_id))
            db.execute("DELETE FROM sync_jobs WHERE id=?", (sync_id,))
            return {"committed":True,"version":job["version"],"digest":job["digest"],
                    "recordCount":total,"deletedCount":deleted}

    @staticmethod
    def selection(device_id=None, from_ms=None, to_ms=None):
        where, params = [], []
        if isinstance(device_id, tuple):
            where.append("m.device_id IN ("+",".join("?" for _ in device_id)+")" if device_id else "0=1")
            params.extend(device_id)
        elif device_id is not None:
            where.append("m.device_id=?")
            params.append(device_id)
        for condition, value in (("m.measured_at>=?", from_ms), ("m.measured_at<?", to_ms)):
            if value is not None:
                where.append(condition)
                params.append(value)
        return (" WHERE " + " AND ".join(where)) if where else "", params

    @staticmethod
    def public_record(row):
        return {"deviceId": row["device_id"], "memberName": row["name"],
                "recordId": row["record_id"], "systolic": row["systolic"],
                "diastolic": row["diastolic"], "pulse": row["pulse"],
                "measuredAt": row["measured_at"], "note": row["note"],
                "timeZone": row["time_zone"], "utcOffsetMinutes": row["utc_offset_minutes"],
                "source": row["source"], "receivedAt": row["received_at"]}

    def records(self, device_id=None, from_ms=None, to_ms=None, limit=100, offset=0):
        where, params = self.selection(device_id, from_ms, to_ms)
        with self.connection() as db:
            total = db.execute("SELECT COUNT(*) FROM measurements m" + where, params).fetchone()[0]
            rows = db.execute("""
                SELECT m.*,d.name FROM measurements m JOIN devices d ON d.id=m.device_id
            """ + where + " ORDER BY m.measured_at DESC,m.device_id,m.record_id LIMIT ? OFFSET ?",
                              (*params, limit, offset)).fetchall()
        return {"total": total, "records": [self.public_record(row) for row in rows]}

    def iter_records(self, device_id=None, from_ms=None, to_ms=None):
        where, params = self.selection(device_id, from_ms, to_ms)
        with self.connection() as db:
            for row in db.execute("""
                SELECT m.*,d.name FROM measurements m JOIN devices d ON d.id=m.device_id
            """ + where + " ORDER BY m.measured_at DESC,m.device_id,m.record_id", params):
                yield self.public_record(row)

    def overview(self, device_id=None, from_ms=None, to_ms=None):
        """Full-filter statistics; daily means stay separated by device and phone-local date."""
        where, params = self.selection(device_id, from_ms, to_ms)
        local_day = "date((m.measured_at+m.utc_offset_minutes*60000)/1000.0,'unixepoch')"
        hour = "CAST(strftime('%H',(m.measured_at+m.utc_offset_minutes*60000)/1000.0,'unixepoch') AS INTEGER)"
        period = f"CASE WHEN {hour}<5 THEN 'night' WHEN {hour}<12 THEN 'morning' WHEN {hour}<18 THEN 'afternoon' ELSE 'evening' END"
        averages = "COUNT(*) AS count,AVG(m.systolic) AS systolic,AVG(m.diastolic) AS diastolic,AVG(m.pulse) AS pulse"
        with self.connection() as db:
            # Explicit read transaction keeps the summary, latest row and curve in one snapshot.
            db.execute("BEGIN")
            summary = dict(db.execute(f"SELECT {averages},COUNT(DISTINCT {local_day}) AS days,"
                "COUNT(DISTINCT m.device_id) AS members,MIN(m.systolic) AS minSystolic,"
                "MAX(m.systolic) AS maxSystolic,MIN(m.diastolic) AS minDiastolic,"
                "MAX(m.diastolic) AS maxDiastolic,MIN(m.pulse) AS minPulse,MAX(m.pulse) AS maxPulse "
                "FROM measurements m"+where,params).fetchone())
            latest = db.execute("SELECT m.*,d.name FROM measurements m JOIN devices d ON d.id=m.device_id"+
                where+" ORDER BY m.measured_at DESC,m.device_id,m.record_id LIMIT 1",params).fetchone()
            daily = [dict(row) for row in db.execute(f"SELECT m.device_id AS deviceId,d.name AS memberName,"
                f"{local_day} AS date,{averages},MIN(m.measured_at) AS firstAt,MAX(m.measured_at) AS lastAt "
                "FROM measurements m JOIN devices d ON d.id=m.device_id"+where+
                f" GROUP BY m.device_id,{local_day} ORDER BY date,m.device_id",params)]
            periods = [dict(row) for row in db.execute(f"SELECT m.device_id AS deviceId,{period} AS period,{averages} "
                "FROM measurements m"+where+f" GROUP BY m.device_id,{period}",params)]
        return {"summary":summary,"latest":self.public_record(latest) if latest else None,
                "daily":daily,"periods":periods}

    def backup(self, destination):
        target = Path(destination)
        target.parent.mkdir(parents=True, exist_ok=True)
        # Exclusive creation prevents accidentally replacing an existing backup or the live DB.
        with target.open("xb"):
            pass
        with self.connection() as source, closing(sqlite3.connect(target)) as backup:
            source.backup(backup)
        target.chmod(0o600)
