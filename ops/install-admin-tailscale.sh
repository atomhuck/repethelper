#!/usr/bin/env bash
set -euo pipefail

# Run as root on the VPS after the application update. It never opens a public port.
# Tailscale login itself is deliberately interactive: the owner approves the VPS in their tailnet.
if [[ ${EUID} -ne 0 ]]; then echo "Run as root" >&2; exit 1; fi
if ! command -v tailscale >/dev/null 2>&1; then
  curl -fsSL https://tailscale.com/install.sh | sh
fi
echo "Now sign in the VPS to the owner's tailnet:" >&2
tailscale up
echo "After login, publish the private control listener only inside Tailscale:" >&2
echo "tailscale serve --https=443 --bg http://127.0.0.1:8081" >&2
echo "Open https://<this-vps>.<your-tailnet>.ts.net/control from an approved Tailscale device." >&2
