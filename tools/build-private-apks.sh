#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ${GITHUB_ACTIONS:-} == true ]]; then
  echo 'Private builds must not run in GitHub Actions.' >&2
  exit 1
fi
umask 077
stt_assets=$(mktemp -d)
trap 'rm -rf "$stt_assets"' EXIT
read -r -p 'Server HTTPS URL: ' STT_PRIVATE_URL
read -r -s -p 'STT_TOKEN (hidden input): ' STT_PRIVATE_TOKEN
echo
export STT_PRIVATE_URL STT_PRIVATE_TOKEN
python3 - "$stt_assets" <<'PY'
import json,os,re,sys
from pathlib import Path
from urllib.parse import urlsplit
url=os.environ['STT_PRIVATE_URL'].strip().rstrip('/')
token=os.environ['STT_PRIVATE_TOKEN'].strip()
u=urlsplit(url)
assert u.scheme=='https' and u.hostname and not u.username and not u.password and not u.query and not u.fragment and u.path in ('','/'), 'Expected an HTTPS base URL'
assert re.fullmatch('[a-zA-Z0-9]{32,256}',token), 'Invalid token format'
Path(sys.argv[1],'stt-private-config.json').write_text(json.dumps(dict(url=url,token=token)))
PY
unset STT_PRIVATE_URL STT_PRIVATE_TOKEN
STT_PRIVATE_ASSETS_DIR="$stt_assets" bash ./gradlew :app:assembleBaseDebug :wear:assembleDebug --no-daemon
mkdir -p private-delivery
cp app/build/outputs/apk/base/debug/*.apk private-delivery/STT-Phone-Private.apk
cp wear/build/outputs/apk/debug/*.apk private-delivery/STT-Watch5-Private.apk
echo 'Private APKs created in private-delivery. Do not publish them.'
