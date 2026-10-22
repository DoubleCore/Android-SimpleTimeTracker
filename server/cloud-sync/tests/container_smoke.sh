#!/usr/bin/env bash
# Run on a Docker host: verify the exact deployment image + durable named volume.
set -euo pipefail
cd "$(dirname "$0")/.."
stt_suffix="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
stt_container="stt-smoke-$stt_suffix"
stt_volume="stt-smoke-data-$stt_suffix"
stt_token="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
cleanup() {
  docker rm -f "$stt_container" >/dev/null 2>&1 || true
  docker volume rm "$stt_volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker build -t stt-cloud-smoke .
docker volume create "$stt_volume" >/dev/null
docker run -d --name "$stt_container" --read-only --tmpfs /tmp \
  -e STT_TOKEN="$stt_token" -v "$stt_volume:/data" -p 127.0.0.1:18765:8765 stt-cloud-smoke >/dev/null
export STT_SMOKE_TOKEN="$stt_token" STT_SMOKE_CONTAINER="$stt_container"
python3 - <<'PY'
import json, os, subprocess, time, urllib.request, uuid
base='http://127.0.0.1:18765'
headers={'Authorization':'Bearer '+os.environ['STT_SMOKE_TOKEN'],'Content-Type':'application/json'}
def request(path, body=None):
    req=urllib.request.Request(base+path, headers=headers, data=None if body is None else json.dumps(body).encode())
    with urllib.request.urlopen(req,timeout=2) as response:
        return json.load(response)
def ready():
    for _ in range(20):
        try: return request('/v1/health')
        except (OSError, ValueError): time.sleep(0.5)
    raise RuntimeError('Container did not become ready')
health=ready()
command={'id':str(uuid.uuid4()),'device_id':str(uuid.uuid4()),'kind':'activity','occurred_at':int(time.time()*1000),'activity_id':str(uuid.uuid4()),'name':'container persistence'}
ack=request('/v1/operations',command)
assert ack['accepted']
subprocess.run(['docker','restart',os.environ['STT_SMOKE_CONTAINER']],check=True,stdout=subprocess.DEVNULL)
assert health==ready()
assert request('/v1/operations',command)==ack
state=request('/v1/sync?after=0')
assert len(state['events'])==1 and state['events'][0]['data']['id']==command['activity_id']
print('PASS: deployment image starts as non-root, commits to volume, survives restart, replays one ACK')
PY
