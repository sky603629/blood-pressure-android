"""Authenticated family readings with atomic, installation-scoped snapshot synchronization."""
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Annotated, Literal
from uuid import UUID
import csv
import hashlib
import io
import json
import os

from fastapi import Depends, FastAPI, HTTPException, Path as ApiPath, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field, StrictInt, StrictStr, ValidationError, model_validator
from starlette.concurrency import run_in_threadpool

from storage import RecordConflict, SnapshotConflict, Store

MAX_BODY = 16 * 1024
STATIC = Path(__file__).parent / "static"


class Reading(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schemaVersion: Annotated[StrictInt, Field(ge=1, le=1)]
    recordId: UUID
    systolic: Annotated[StrictInt, Field(ge=50, le=260)]
    diastolic: Annotated[StrictInt, Field(ge=30, le=160)]
    pulse: Annotated[StrictInt, Field(ge=30, le=220)]
    measuredAt: Annotated[StrictInt, Field(ge=-2208988800000, le=4102444800000)]
    note: Annotated[StrictStr, Field(max_length=2048)] = ""
    timeZone: Annotated[StrictStr, Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_+./:-]+$")]
    utcOffsetMinutes: Annotated[StrictInt, Field(ge=-840, le=840)]
    source: Literal["manual", "camera"]

    @model_validator(mode="after")
    def check_pressures(self):
        if self.diastolic >= self.systolic:
            raise ValueError("Diastolic pressure must be lower than systolic pressure")
        return self


class SyncReading(Reading):
    schemaVersion: Annotated[StrictInt, Field(ge=2, le=2)]
    localId: Annotated[StrictInt, Field(ge=1, le=9223372036854775807)]
    source: Literal["manual", "camera", "import", "legacy"]


class SnapshotStart(BaseModel):
    model_config = ConfigDict(extra="forbid")
    installationId: UUID
    version: Annotated[StrictInt, Field(ge=1, le=9223372036854775807)]
    recordCount: Annotated[StrictInt, Field(ge=0, le=20000)]
    highWatermark: Annotated[StrictInt, Field(ge=0, le=9223372036854775807)]
    digest: Annotated[StrictStr, Field(pattern=r"^[a-f0-9]{64}$")]

    @model_validator(mode="after")
    def check_count(self):
        if self.recordCount > self.highWatermark:
            raise ValueError("Invalid record watermark")
        return self


class SnapshotPage(BaseModel):
    model_config = ConfigDict(extra="forbid")
    records: Annotated[list[SyncReading], Field(min_length=1, max_length=25)]


async def json_body(request, limit):
    if request.headers.get("content-type", "").split(";")[0].strip().lower() != "application/json":
        raise HTTPException(415, "Expected application/json")
    size = request.headers.get("content-length")
    if size is not None and (not size.isdigit() or int(size) > limit):
        raise HTTPException(413, "Request body too large")
    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > limit:
            raise HTTPException(413, "Request body too large")
    return bytes(body)


def create_app(database_path=None):
    store = Store(database_path or os.environ.get("BP_DATABASE", "data/blood-pressure.sqlite3"))
    asset_versions = {name: hashlib.sha256((STATIC / name).read_bytes()).hexdigest()[:16]
                      for name in ("dashboard.js", "dashboard.css")}
    index_html = (STATIC / "index.html").read_text(encoding="utf-8")
    for name, version in asset_versions.items():
        index_html = index_html.replace(f'"/{name}"', f'"/{name}?v={version}"')

    @asynccontextmanager
    async def lifespan(app):
        await run_in_threadpool(store.initialize)
        yield

    api = FastAPI(title="家庭血压记录", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)
    api.state.store = store
    bearer = HTTPBearer(auto_error=False)

    def supplied_token(credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]):
        if (credentials is None or not 8 <= len(credentials.credentials) <= 256 or
                any(ord(c) <= 32 or ord(c) >= 127 for c in credentials.credentials)):
            raise HTTPException(401, "Authentication required", headers={"WWW-Authenticate": "Bearer"})
        return credentials.credentials

    def client_auth(token: Annotated[str, Depends(supplied_token)]):
        if len(token) < 32:
            raise HTTPException(401, "Invalid upload key")
        device = store.authenticate_device(token)
        if device is None:
            raise HTTPException(401, "Invalid upload key")
        return device

    def admin_auth(token: Annotated[str, Depends(supplied_token)]):
        if not store.authenticate_admin(token):
            raise HTTPException(401, "Invalid viewing key")

    @api.middleware("http")
    async def response_headers(request, call_next):
        response = await call_next(request)
        # Only content-addressed public assets may opt into caching. Private responses never do.
        response.headers.setdefault("Cache-Control", "no-store")
        response.headers.update({
            "X-Content-Type-Options": "nosniff",
            "Referrer-Policy": "no-referrer", "X-Frame-Options": "DENY",
            "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; "
                "connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; "
                "base-uri 'none'; form-action 'none'",
        })
        return response

    @api.exception_handler(RequestValidationError)
    async def invalid_query(request, error):
        return JSONResponse({"detail": "Invalid request parameters"}, status_code=422)

    @api.get("/healthz")
    def health():
        return {"status": "ok", "schemaVersion": 1}

    @api.get("/api/v1/device")
    def device_info(device: Annotated[dict, Depends(client_auth)]):
        return {"deviceId": device["id"], "memberName": device["name"]}

    @api.post("/api/v1/measurements")
    async def receive(request: Request, device: Annotated[dict, Depends(client_auth)]):
        if request.headers.get("content-type", "").split(";")[0].strip().lower() != "application/json":
            raise HTTPException(415, "Expected application/json")
        size = request.headers.get("content-length")
        if size is not None and (not size.isdigit() or int(size) > MAX_BODY):
            raise HTTPException(413, "Request body too large")
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > MAX_BODY:
                raise HTTPException(413, "Request body too large")
        try:
            reading = Reading.model_validate(json.loads(body))
        except (ValidationError, ValueError, UnicodeError):
            raise HTTPException(422, "Invalid measurement") from None
        record = reading.model_dump(mode="json")
        try:
            created = await run_in_threadpool(store.save, device["id"], record)
        except RecordConflict:
            raise HTTPException(409, "Record ID already has different contents") from None
        return JSONResponse({"saved": True, "recordId": record["recordId"], "created": created},
                            status_code=201 if created else 200)

    async def sync_model(request, model, limit):
        body = await json_body(request, limit)
        try:
            return body, model.model_validate(json.loads(body)).model_dump(mode="json")
        except (ValidationError, ValueError, UnicodeError):
            raise HTTPException(422, "Invalid synchronization request") from None

    async def sync_call(function, *args):
        try:
            return await run_in_threadpool(function, *args)
        except SnapshotConflict as error:
            raise HTTPException(409, str(error)) from None

    @api.post("/api/v2/snapshots")
    async def begin(request: Request, device: Annotated[dict, Depends(client_auth)]):
        _, metadata = await sync_model(request, SnapshotStart, MAX_BODY)
        return await sync_call(store.begin_snapshot, device["id"], metadata)

    @api.put("/api/v2/snapshots/{sync_id}/pages/{page_index}")
    async def stage(sync_id: UUID, page_index: Annotated[int, ApiPath(ge=0)], request: Request,
                    device: Annotated[dict, Depends(client_auth)]):
        body, page = await sync_model(request, SnapshotPage, 512*1024)
        return await sync_call(store.stage_page, device["id"], str(sync_id), page_index, body, page["records"])

    @api.post("/api/v2/snapshots/{sync_id}/commit")
    async def commit(sync_id: UUID, device: Annotated[dict, Depends(client_auth)]):
        return await sync_call(store.commit_snapshot, device["id"], str(sync_id))

    @api.get("/api/v1/devices", dependencies=[Depends(admin_auth)])
    def members():
        return {"devices": store.devices()}

    def filters(device_id: UUID | None = None, from_ms: int | None = None, to_ms: int | None = None,
                device_ids: Annotated[list[UUID] | None, Query(max_length=100)] = None):
        if from_ms is not None and to_ms is not None and from_ms >= to_ms:
            raise HTTPException(422, "Date range must have a positive duration")
        if device_id is not None and device_ids is not None:
            raise HTTPException(422, "Choose a single member or a member group")
        selected = tuple(dict.fromkeys(str(value) for value in device_ids)) if device_ids is not None else (
            str(device_id) if device_id is not None else None)
        return selected, from_ms, to_ms

    @api.get("/api/v1/measurements", dependencies=[Depends(admin_auth)])
    def records(selection: Annotated[tuple, Depends(filters)],
                limit: Annotated[int, Query(ge=1, le=200)] = 50,
                offset: Annotated[int, Query(ge=0)] = 0):
        return store.records(*selection, limit, offset)

    @api.get("/api/v1/measurements.csv", dependencies=[Depends(admin_auth)])
    def report(selection: Annotated[tuple, Depends(filters)]):
        def escaped_cell(value):
            # Prevent spreadsheet formula execution in member names and free-form notes.
            return "'" + value if value.lstrip().startswith(("=", "+", "-", "@")) else value

        def rows():
            buffer = io.StringIO(newline="")
            writer = csv.writer(buffer)
            writer.writerow(["家人", "测量时间", "时段", "高压(mmHg)", "低压(mmHg)", "脉搏(次/分)", "备注"])
            yield "\ufeff" + buffer.getvalue()
            for item in store.iter_records(*selection):
                date = datetime.fromtimestamp(item["measuredAt"] / 1000,
                        timezone(timedelta(minutes=item["utcOffsetMinutes"])))
                period = "凌晨" if date.hour < 5 else "上午" if date.hour < 12 else "下午" if date.hour < 18 else "晚上"
                buffer.seek(0)
                buffer.truncate(0)
                writer.writerow([escaped_cell(item["memberName"]), "测量于 " + date.strftime("%Y-%m-%d %H:%M:%S"),
                    period, item["systolic"], item["diastolic"], item["pulse"], escaped_cell(item["note"])])
                yield buffer.getvalue()
        return StreamingResponse(rows(), media_type="text/csv; charset=utf-8",
                                 headers={"Content-Disposition": 'attachment; filename="blood-pressure.csv"'})

    @api.get("/api/v1/overview", dependencies=[Depends(admin_auth)])
    def overview(selection: Annotated[tuple, Depends(filters)]):
        return store.overview(*selection)

    @api.get("/")
    def dashboard():
        return HTMLResponse(index_html)

    def asset(name, media_type, version):
        if version is not None and version != asset_versions[name]:
            raise HTTPException(404, "Asset version unavailable; reload the dashboard")
        headers = {"Cache-Control": "public, max-age=31536000, immutable"} if version is not None else {}
        return FileResponse(STATIC / name, media_type=media_type, headers=headers)

    @api.get("/dashboard.js")
    def script(v: str | None = None):
        return asset("dashboard.js", "text/javascript", v)

    @api.get("/dashboard.css")
    def stylesheet(v: str | None = None):
        return asset("dashboard.css", "text/css", v)

    return api


app = create_app()
