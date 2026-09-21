#!/usr/bin/env bash
# Remove KAP systemd units and optional install prefix.
set -euo pipefail

PREFIX="${PREFIX:-/opt/kap}"
SYSTEMD_DIR="${SYSTEMD_DIR:-/etc/systemd/system}"
NGINX_DROPIN="${NGINX_DROPIN:-/etc/nginx/conf.d/kap-panel.conf}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root (sudo $0)" >&2
  exit 1
fi

for u in kap-panel kap-server; do
  if systemctl list-unit-files 2>/dev/null | grep -q "^${u}.service"; then
    echo "Stopping ${u}.service"
    systemctl disable --now "${u}.service" 2>/dev/null || true
    rm -f "$SYSTEMD_DIR/${u}.service"
  fi
done
systemctl daemon-reload 2>/dev/null || true

if [[ -f "$NGINX_DROPIN" ]]; then
  read -r -p "Remove nginx drop-in $NGINX_DROPIN? [y/N] " ans
  if [[ "${ans:-}" =~ ^[Yy]$ ]]; then
    rm -f "$NGINX_DROPIN"
    nginx -t 2>/dev/null && systemctl reload nginx 2>/dev/null || true
    echo "Removed $NGINX_DROPIN"
  fi
fi

read -r -p "Delete $PREFIX entirely (bin, data, env)? [y/N] " ans
if [[ "${ans:-}" =~ ^[Yy]$ ]]; then
  rm -rf "$PREFIX"
  echo "Removed $PREFIX"
else
  echo "Left $PREFIX in place"
fi

echo "Uninstall finished."
