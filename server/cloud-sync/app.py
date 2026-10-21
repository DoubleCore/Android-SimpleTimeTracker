"""Single-owner STT sync service. SQLite commit precedes every successful ACK."""
from contextlib import contextmanager
import hashlib
import hmac
import json
import os
from pathlib import Path
import sqlite3
import time
import uuid
from urllib.parse import parse_qs

MAX_BODY = 8192
PAGE_SIZE = 200


class ApiError(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), sort_keys=True)


def identifier(value):
    if not isinstance(value, str):
        raise ApiError(400, 'invalid_id')
    try:
        if str(uuid.UUID(value)) != value:
            raise ValueError()
    except (ValueError, AttributeError):
        raise ApiError(400, 'invalid_id') from None
    return value


class Store:
    def __init__(self, path):
        self.path = str(path)
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.executescript('''
                CREATE TABLE IF NOT EXISTS entity_extra(id TEXT PRIMARY KEY, payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS activities(id TEXT PRIMARY KEY, name TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS sessions(
                    id TEXT PRIMARY KEY, activity_id TEXT NOT NULL REFERENCES activities(id),
                    started_at INTEGER NOT NULL, ended_at INTEGER,
                    CHECK(ended_at IS NULL OR ended_at >= started_at));
                CREATE UNIQUE INDEX IF NOT EXISTS active_activity
                    ON sessions(activity_id) WHERE ended_at IS NULL;
                CREATE TABLE IF NOT EXISTS events(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS operations(
                    id TEXT PRIMARY KEY, digest TEXT NOT NULL, status INTEGER NOT NULL,
                    response TEXT NOT NULL);
            ''')
            db.execute('INSERT OR IGNORE INTO meta VALUES(?,?)', ('workspace_id', str(uuid.uuid4())))
            self.workspace_id = db.execute("SELECT value FROM meta WHERE key='workspace_id'").fetchone()[0]

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA foreign_keys=ON')
        db.execute('PRAGMA synchronous=FULL')
        try:
            with db:
                yield db
        finally:
            db.close()

    def sync(self, after):
        with self.connect() as db:
            db.execute('BEGIN')
            head = db.execute('SELECT COALESCE(MAX(seq),0) FROM events').fetchone()[0]
            if after > head:
                raise ApiError(409, 'cursor_ahead_restore_required')
            rows = db.execute('SELECT seq,payload FROM events WHERE seq>? ORDER BY seq LIMIT ?',
                              (after, PAGE_SIZE)).fetchall()
            events = [dict(seq=row['seq'], **json.loads(row['payload'])) for row in rows]
            cursor = rows[-1]['seq'] if rows else after
            return dict(protocol=1, workspace_id=self.workspace_id, cursor=cursor,
                        has_more=cursor < head, events=events, server_time=int(time.time()*1000))

    def execute(self, op):
        if not isinstance(op, dict):
            raise ApiError(400, 'invalid_operation')
        kind = op.get('kind')
        keys = {'id', 'device_id', 'kind', 'occurred_at'}
        if kind in {'put_activity', 'put_session', 'delete_session'}:
            keys |= {'data'}
        elif kind == 'activity':
            keys |= {'activity_id', 'name'}
        elif kind == 'start':
            keys |= {'activity_id', 'session_id'}
        elif kind == 'stop':
            keys |= {'session_id'}
        else:
            raise ApiError(400, 'invalid_kind')
        if set(op) != keys:
            raise ApiError(400, 'invalid_fields')
        for key in keys & {'id', 'device_id', 'activity_id', 'session_id'}:
            identifier(op[key])
        when = op['occurred_at']
        if type(when) is not int or not 946684800000 <= when <= int(time.time()*1000) + 60000:
            raise ApiError(400, 'invalid_clock')
        if kind == 'activity' and (not isinstance(op['name'], str) or not op['name'].strip()
                                   or len(op['name']) > 80):
            raise ApiError(400, 'invalid_name')
        digest = hashlib.sha256(encode(op).encode()).hexdigest()
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            previous = db.execute('SELECT * FROM operations WHERE id=?', (op['id'],)).fetchone()
            if previous:
                if previous['digest'] != digest:
                    raise ApiError(409, 'operation_id_reused')
                return previous['status'], json.loads(previous['response'])
            try:
                entity = self.apply(db, op)
                cursor = db.execute('INSERT INTO events(payload) VALUES(?)',
                                    (encode(entity),)).lastrowid if entity else None
                response = dict(protocol=1, workspace_id=self.workspace_id,
                                operation_id=op['id'], accepted=True, cursor=cursor)
                status = 200
            except ApiError as exc:
                # Domain rejection is also durable; retries cannot later change its outcome.
                response = dict(protocol=1, workspace_id=self.workspace_id,
                                operation_id=op['id'], accepted=False, error=exc.code)
                status = exc.status
            db.execute('INSERT INTO operations VALUES(?,?,?,?)',
                       (op['id'], digest, status, encode(response)))
        return status, response

    @staticmethod
    def apply(db, op):
        if op['kind'] in {'put_activity', 'put_session', 'delete_session'}:
            data = op['data']
            if not isinstance(data, dict):
                raise ApiError(400, 'invalid_data')
            entity_id = identifier(data.get('id'))
            if op['kind'] == 'put_activity':
                if not isinstance(data.get('name'), str) or not 1 <= len(data['name'].strip()) <= 80:
                    raise ApiError(400, 'invalid_name')
                db.execute('INSERT INTO activities VALUES(?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name',
                           (entity_id, data['name'].strip()))
                entity = 'activity'
            else:
                activity_id = identifier(data.get('activity_id'))
                started, ended = data.get('started_at'), data.get('ended_at')
                if type(started) is not int or started < 946684800000 or started > int(time.time()*1000)+60000:
                    raise ApiError(400, 'invalid_clock')
                if ended is not None and (type(ended) is not int or ended < started or ended > int(time.time()*1000)+60000):
                    raise ApiError(400, 'invalid_clock')
                if not db.execute('SELECT 1 FROM activities WHERE id=?', (activity_id,)).fetchone():
                    raise ApiError(409, 'activity_missing')
                if op['kind'] == 'delete_session':
                    db.execute('DELETE FROM sessions WHERE id=?', (entity_id,))
                    data = dict(data, deleted=True)
                else:
                    existing = db.execute('SELECT ended_at FROM sessions WHERE id=?', (entity_id,)).fetchone()
                    if ended is None and existing and existing['ended_at'] is not None:
                        raise ApiError(409, 'session_already_stopped')
                    if ended is None and db.execute('SELECT 1 FROM sessions WHERE activity_id=? AND ended_at IS NULL AND id!=?', (activity_id, entity_id)).fetchone():
                        raise ApiError(409, 'activity_already_running')
                    db.execute('INSERT INTO sessions VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET activity_id=excluded.activity_id,started_at=excluded.started_at,ended_at=excluded.ended_at',
                               (entity_id, activity_id, started, ended))
                entity = 'session'
            db.execute('INSERT OR REPLACE INTO entity_extra VALUES(?,?)', (entity_id, encode(data)))
            return dict(entity=entity, data=data)
        if op['kind'] == 'activity':
            if db.execute('SELECT 1 FROM activities WHERE id=?', (op['activity_id'],)).fetchone():
                raise ApiError(409, 'activity_exists')
            data = dict(id=op['activity_id'], name=op['name'].strip())
            db.execute('INSERT INTO activities VALUES(?,?)', (data['id'], data['name']))
            return dict(entity='activity', data=data)
        if op['kind'] == 'start':
            if not db.execute('SELECT 1 FROM activities WHERE id=?', (op['activity_id'],)).fetchone():
                raise ApiError(409, 'activity_missing')
            if db.execute('SELECT 1 FROM sessions WHERE id=?', (op['session_id'],)).fetchone():
                raise ApiError(409, 'session_exists')
            if db.execute('SELECT 1 FROM sessions WHERE activity_id=? AND ended_at IS NULL',
                          (op['activity_id'],)).fetchone():
                raise ApiError(409, 'activity_already_running')
            data = dict(id=op['session_id'], activity_id=op['activity_id'],
                        started_at=op['occurred_at'], ended_at=None)
            db.execute('INSERT INTO sessions VALUES(?,?,?,NULL)',
                       (data['id'], data['activity_id'], data['started_at']))
        else:
            row = db.execute('SELECT * FROM sessions WHERE id=?', (op['session_id'],)).fetchone()
            if not row:
                raise ApiError(409, 'session_missing')
            if row['ended_at'] is not None:
                return None  # Another device stopped this exact session; never stop a newer one.
            if op['occurred_at'] < row['started_at']:
                raise ApiError(409, 'stop_before_start')
            extra = db.execute('SELECT payload FROM entity_extra WHERE id=?', (row['id'],)).fetchone()
            data = json.loads(extra[0]) if extra else {}
            data.update(dict(row))
            data['ended_at'] = op['occurred_at']
            db.execute('UPDATE sessions SET ended_at=? WHERE id=?',
                       (data['ended_at'], data['id']))
        return dict(entity='session', data=data)


class Application:
    def __init__(self, path, token):
        if not isinstance(token, str) or len(token) < 32 or not token.isascii() or not token.isalnum():
            raise ValueError('STT_TOKEN must contain at least 32 ASCII letters/digits; use secrets.token_hex(32)')
        self.token = ('Bearer ' + token).encode()
        self.store = Store(path)

    def __call__(self, env, start_response):
        try:
            if not hmac.compare_digest(env.get('HTTP_AUTHORIZATION', '').encode(), self.token):
                raise ApiError(401, 'unauthorized')
            method, path = env['REQUEST_METHOD'], env['PATH_INFO']
            if method == 'GET' and path == '/v1/health':
                status, body = 200, dict(protocol=1, workspace_id=self.store.workspace_id)
            elif method == 'GET' and path == '/v1/sync':
                params = parse_qs(env.get('QUERY_STRING', ''), keep_blank_values=True)
                raw = params.get('after', ['0'])
                if set(params) - {'after'} or len(raw) != 1 or not raw[0].isascii() or not raw[0].isdigit() or len(raw[0]) > 18:
                    raise ApiError(400, 'invalid_cursor')
                status, body = 200, self.store.sync(int(raw[0]))
            elif method == 'POST' and path == '/v1/operations':
                if env.get('CONTENT_TYPE', '').split(';')[0].strip() != 'application/json':
                    raise ApiError(415, 'json_required')
                if env.get('HTTP_TRANSFER_ENCODING'):
                    raise ApiError(400, 'chunked_not_supported')
                raw_length = env.get('CONTENT_LENGTH', '')
                if not raw_length.isascii() or not raw_length.isdigit() or len(raw_length) > 8:
                    raise ApiError(411, 'length_required')
                length = int(raw_length)
                if length > MAX_BODY:
                    raise ApiError(413, 'body_too_large')
                raw = env['wsgi.input'].read(length)
                if len(raw) != length:
                    raise ApiError(400, 'incomplete_body')
                try:
                    op = json.loads(raw)
                except (ValueError, UnicodeError, RecursionError):
                    raise ApiError(400, 'invalid_json') from None
                status, body = self.store.execute(op)
            else:
                raise ApiError(404, 'unknown_route')
        except ApiError as exc:
            status, body = exc.status, dict(error=exc.code)
        except sqlite3.OperationalError:
            status, body = 503, dict(error='storage_unavailable')
        payload = encode(body).encode()
        from http import HTTPStatus
        start_response(f'{status} {HTTPStatus(status).phrase}', [
            ('Content-Type', 'application/json; charset=utf-8'),
            ('Content-Length', str(len(payload))), ('Cache-Control', 'no-store'),
            ('X-Content-Type-Options', 'nosniff')])
        return [payload]


def create_app():
    return Application(os.environ.get('STT_DB', '/data/stt.sqlite3'), os.environ.get('STT_TOKEN', ''))


if __name__ == '__main__':
    # Loopback-only developer server. Public deployments use Gunicorn behind Caddy TLS.
    from wsgiref.simple_server import make_server
    with make_server('127.0.0.1', int(os.environ.get('PORT', '8765')), create_app()) as server:
        server.serve_forever()
