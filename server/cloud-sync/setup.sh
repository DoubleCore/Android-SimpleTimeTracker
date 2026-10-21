#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -e .env ]]; then
  echo '.env already exists; keeping the existing secret.'
else
  read -r -p 'Public hostname (e.g. time.example.com): ' stt_domain
  [[ "$stt_domain" =~ ^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$ && "$stt_domain" == *.* ]] || { echo 'Invalid hostname'; exit 1; }
  umask 077
  stt_token="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
  printf 'STT_DOMAIN=%s\nSTT_TOKEN=%s\n' "$stt_domain" "$stt_token" > .env
fi
docker compose up -d --build
echo 'Server started. Read .env locally for the address/token to enter on both devices.'
