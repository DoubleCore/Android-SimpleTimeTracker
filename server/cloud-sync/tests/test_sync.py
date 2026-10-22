import concurrent.futures
import io
import json
from pathlib import Path
import socket
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import uuid
from wsgiref.simple_server import make_server, WSGIRequestHandler

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import Application, ApiError, PAGE_SIZE

TOKEN = 'a' * 64


def uid():
    return str(uuid.uuid4())


def op(kind, **extra):
    return dict(id=uid(), device_id=uid(), kind=kind, occurred_at=int(time.time()*1000), **extra)


class Quiet(WSGIRequestHandler):
    def log_message(self, *args):
        pass


class SyncTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'data.sqlite3'
        self.app = Application(self.path, TOKEN)
        self.server = make_server('127.0.0.1', 0, self.app, handler_class=Quiet)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()
        self.tmp.cleanup()

    def call(self, path, body=None, token=TOKEN):
        headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
        request = urllib.request.Request(self.url + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        try:
            response = urllib.request.urlopen(request, timeout=3)
        except urllib.error.HTTPError as exc:
            response = exc
        with response:
            return response.status, json.load(response)

    def activity(self):
        activity = uid()
        self.assertEqual(200, self.call('/v1/operations', op('activity', activity_id=activity, name='学习'))[0])
        return activity

    def test_phone_watch_real_http_roundtrip_and_restart(self):
        activity = self.activity()
        status, phone = self.call('/v1/sync?after=0')
        self.assertEqual(200, status)
        self.assertEqual('学习', phone['events'][0]['data']['name'])
        start = op('start', activity_id=activity, session_id=uid())
        self.assertEqual(200, self.call('/v1/operations', start)[0])
        watch = self.call('/v1/sync?after=' + str(phone['cursor']))[1]
        self.assertEqual(start['session_id'], watch['events'][0]['data']['id'])
        stop = op('stop', session_id=start['session_id'])
        self.assertEqual(200, self.call('/v1/operations', stop)[0])
        # New application instance on same file represents restart, without in-memory data.
        restarted = Application(self.path, TOKEN)
        state = restarted.store.sync(0)
        self.assertEqual(3, len(state['events']))
        self.assertEqual(stop['occurred_at'], state['events'][-1]['data']['ended_at'])
        self.assertEqual(self.app.store.workspace_id, restarted.store.workspace_id)

    def test_lost_ack_retry_exactly_once_and_payload_collision(self):
        activity = self.activity()
        start = op('start', activity_id=activity, session_id=uid())
        first = self.call('/v1/operations', start)
        retry = self.call('/v1/operations', start)
        self.assertEqual(first, retry)
        self.assertEqual(2, len(self.app.store.sync(0)['events']))
        modified = dict(start, occurred_at=start['occurred_at'] - 1)
        self.assertEqual((409, {'error': 'operation_id_reused'}), self.call('/v1/operations', modified))

    def test_simultaneous_start_only_one_wins_and_rejection_is_durable(self):
        activity = self.activity()
        commands = [op('start', activity_id=activity, session_id=uid()) for _ in range(10)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
            results = list(pool.map(self.app.store.execute, commands))
        self.assertEqual(1, sum(code == 200 for code, _ in results))
        winner = next(cmd for cmd, result in zip(commands, results) if result[0] == 200)
        loser = next(cmd for cmd, result in zip(commands, results) if result[0] == 409)
        self.app.store.execute(op('stop', session_id=winner['session_id']))
        self.assertEqual(409, self.app.store.execute(loser)[0])

    def test_concurrent_duplicate_requests_same_ack(self):
        activity = self.activity()
        command = op('start', activity_id=activity, session_id=uid())
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(self.app.store.execute, [command]*8))
        self.assertTrue(all(result == results[0] for result in results))
        self.assertEqual(2, self.app.store.sync(0)['cursor'])

    def test_stale_stop_never_stops_new_session(self):
        activity = self.activity()
        first = op('start', activity_id=activity, session_id=uid())
        self.app.store.execute(first)
        self.app.store.execute(op('stop', session_id=first['session_id']))
        second = op('start', activity_id=activity, session_id=uid())
        self.app.store.execute(second)
        self.assertEqual(200, self.app.store.execute(op('stop', session_id=first['session_id']))[0])
        with sqlite3.connect(self.path) as db:
            row = db.execute('SELECT id FROM sessions WHERE ended_at IS NULL').fetchone()
        self.assertEqual(second['session_id'], row[0])

    def test_offline_replay_preserves_event_times(self):
        activity = self.activity()
        start = op('start', activity_id=activity, session_id=uid())
        start['occurred_at'] -= 3600000
        stop = op('stop', session_id=start['session_id'])
        stop['occurred_at'] = start['occurred_at'] + 900000
        self.app.store.execute(start)
        self.app.store.execute(stop)
        record = self.app.store.sync(0)['events'][-1]['data']
        self.assertEqual(900000, record['ended_at'] - record['started_at'])

    def test_negative_duration_and_unknown_activity_are_rejected(self):
        self.assertEqual(409, self.app.store.execute(op('start', activity_id=uid(), session_id=uid()))[0])
        activity = self.activity()
        start = op('start', activity_id=activity, session_id=uid())
        self.app.store.execute(start)
        stop = op('stop', session_id=start['session_id'])
        stop['occurred_at'] = start['occurred_at'] - 1
        self.assertEqual(409, self.app.store.execute(stop)[0])
        self.assertIsNone(self.app.store.sync(0)['events'][-1]['data']['ended_at'])

    def test_incremental_pagination_has_no_gaps_and_detects_restore(self):
        for i in range(PAGE_SIZE + 7):
            self.app.store.execute(op('activity', activity_id=uid(), name=str(i)))
        page1 = self.call('/v1/sync?after=0')[1]
        page2 = self.call('/v1/sync?after=' + str(page1['cursor']))[1]
        self.assertTrue(page1['has_more'])
        self.assertFalse(page2['has_more'])
        self.assertEqual(list(range(1, PAGE_SIZE+8)), [e['seq'] for e in page1['events']+page2['events']])
        self.assertEqual(409, self.call('/v1/sync?after=999999')[0])
        self.assertEqual([], self.call('/v1/sync?after=' + str(page2['cursor']))[1]['events'])

    def test_auth_and_validation(self):
        self.assertEqual(401, self.call('/v1/health', token='wrong')[0])
        self.assertEqual(401, self.call('/v1/operations', {}, token='wrong')[0])
        for body in [[], None, {}, op('delete'), op('activity', activity_id='bad', name='x'),
                     op('activity', activity_id=uid(), name=' '), op('activity', activity_id=uid(), name=4)]:
            if body is None:
                continue
            self.assertEqual(400, self.call('/v1/operations', body)[0])
        future = op('activity', activity_id=uid(), name='x')
        future['occurred_at'] += 120000
        self.assertEqual(400, self.call('/v1/operations', future)[0])
        self.assertEqual(400, self.call('/v1/sync?after=-1')[0])
        self.assertEqual(400, self.call('/v1/sync?after=0&after=1')[0])
        self.assertEqual(404, self.call('/unknown')[0])
        self.assertEqual(413, self.call('/v1/operations', {'name': 'x'*9000})[0])
        self.assertEqual(0, self.app.store.sync(0)['cursor'])

    def test_schema_and_storage_failure_do_not_ack(self):
        with sqlite3.connect(self.path) as db:
            db.execute("CREATE TRIGGER fail_write BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT,'test failure'); END")
        command = op('activity', activity_id=uid(), name='do not save')
        with self.assertRaises(sqlite3.IntegrityError):
            self.app.store.execute(command)
        with sqlite3.connect(self.path) as db:
            self.assertEqual(0, db.execute('SELECT COUNT(*) FROM activities').fetchone()[0])
            self.assertEqual(0, db.execute('SELECT COUNT(*) FROM operations').fetchone()[0])


if __name__ == '__main__':
    unittest.main()
