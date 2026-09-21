#!/usr/bin/env bash
# KAP installer — panel+server (all-in-one) or edge server only.
set -euo pipefail

PREFIX="${PREFIX:-/opt/kap}"
SYSTEMD_DIR="${SYSTEMD_DIR:-/etc/systemd/system}"
SERVICE_USER="${SERVICE_USER:-kap}"
# Directory of this script — only for optional local bins / decoy / nginx example.
# GitHub installs write straight to $PREFIX/bin and do not need a project tree.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# GitHub repo for Releases (binaries are release assets, not in the git tree).
# Override if needed: KAP_GITHUB_REPO=other/repo
KAP_GITHUB_REPO="${KAP_GITHUB_REPO:-UnknKriod/kap}"
# Branch for raw.githubusercontent.com static files (decoy pages).
KAP_GITHUB_BRANCH="${KAP_GITHUB_BRANCH:-main}"

RED=$'\033[0;31m'
GRN=$'\033[0;32m'
YLW=$'\033[0;33m'
CYN=$'\033[0;36m'
BLU=$'\033[0;34m'
BLD=$'\033[1m'
DIM=$'\033[2m'
NC=$'\033[0m'

info()  { echo "${GRN}[+]${NC} $*"; }
warn()  { echo "${YLW}[!]${NC} $*"; }
die()   { echo "${RED}[x]${NC} $*" >&2; exit 1; }

need_root() {
  if [[ "$(id -u)" -ne 0 ]]; then
    die "Run as root (sudo $0)"
  fi
}

have_cmd() { command -v "$1" >/dev/null 2>&1; }

http_get() {
  # $1=url  $2=output path (optional — stdout if empty)
  local url="$1" out="${2:-}"
  if have_cmd curl; then
    if [[ -n "$out" ]]; then
      curl -fsSL --connect-timeout 30 --max-time 600 -o "$out" "$url"
    else
      curl -fsSL --connect-timeout 30 --max-time 120 "$url"
    fi
  elif have_cmd wget; then
    if [[ -n "$out" ]]; then
      wget -q -O "$out" "$url"
    else
      wget -q -O - "$url"
    fi
  else
    die "Need curl or wget to download from GitHub"
  fi
}

prompt() {
  # $1=question $2=default
  local q="$1" d="${2:-}" ans
  if [[ -n "$d" ]]; then
    read -r -p "$q [$d]: " ans || true
    echo "${ans:-$d}"
  else
    read -r -p "$q: " ans || true
    echo "$ans"
  fi
}

prompt_secret() {
  local q="$1" ans
  read -r -s -p "$q: " ans || true
  echo
  echo "$ans"
}

choose_mode() {
  echo
  echo "Install mode:"
  echo "  1) all-in-one  — panel + KAP server in one process (recommended for single VPS)"
  echo "  2) server-only — KAP edge node only (no panel; needs shared DB or legacy PSK)"
  echo
  local m
  m="$(prompt "Choose 1 or 2" "1")"
  case "$m" in
    1|all|all-in-one) echo "all" ;;
    2|server|server-only) echo "server" ;;
    *) die "invalid choice: $m" ;;
  esac
}

# Fetch release tags (newest first). Prints one tag per line.
# Uses GitHub API; falls back to empty list on failure.
fetch_release_tags() {
  local repo="$1"
  local url="https://api.github.com/repos/${repo}/releases?per_page=20"
  local json
  if ! json="$(http_get "$url" 2>/dev/null)"; then
    return 1
  fi
  # Prefer jq if available; otherwise parse tag_name with grep/sed.
  if have_cmd jq; then
    printf '%s' "$json" | jq -r '.[].tag_name' 2>/dev/null
  else
    printf '%s' "$json" | grep -oE '"tag_name"[[:space:]]*:[[:space:]]*"[^"]+"' \
      | sed -E 's/.*"tag_name"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/'
  fi
}

# Interactive: pick a GitHub release. Prints UI on stderr; result on stdout:
#   latest | tag:<name>
choose_github_release() {
  local repo
  repo="$(resolve_github_repo)"
  echo >&2
  info "Fetching releases from ${CYN}https://github.com/${repo}${NC} …" >&2

  local tags=()
  local t
  while IFS= read -r t; do
    [[ -n "$t" ]] || continue
    tags+=("$t")
  done < <(fetch_release_tags "$repo" || true)

  echo >&2
  echo "${BLD}GitHub release:${NC}" >&2
  echo "  ${GRN}1)${NC} ${BLD}latest${NC}  ${DIM}(always the newest published release)${NC}" >&2

  local i=0
  if ((${#tags[@]})); then
    for i in "${!tags[@]}"; do
      local num=$((i + 2))
      local tag="${tags[$i]}"
      if [[ $i -eq 0 ]]; then
        echo "  ${CYN}${num})${NC} ${CYN}${tag}${NC}  ${DIM}(current top release)${NC}" >&2
      else
        echo "  ${BLU}${num})${NC} ${tag}" >&2
      fi
    done
    echo >&2
    local max=$((${#tags[@]} + 1))
    local c
    c="$(prompt "Choose 1–${max}" "1")"
    if [[ "$c" == "1" || "$c" == "latest" ]]; then
      echo "latest"
      return 0
    fi
    if [[ "$c" =~ ^[0-9]+$ ]] && ((c >= 2 && c <= max)); then
      echo "tag:${tags[$((c - 2))]}"
      return 0
    fi
    # Allow typing a tag name directly
    if [[ "$c" == tag:* ]]; then
      echo "$c"
      return 0
    fi
    if [[ "$c" == v* || "$c" =~ ^[0-9] ]]; then
      echo "tag:$c"
      return 0
    fi
    die "invalid choice: $c"
  else
    warn "Could not list releases (empty repo, rate limit, or network)." >&2
    echo "  ${DIM}You can still type a tag name (e.g. v1.0.0) or press Enter for latest.${NC}" >&2
    echo >&2
    local c
    c="$(prompt "Release tag (empty = latest)" "")"
    if [[ -z "$c" || "$c" == "latest" || "$c" == "1" ]]; then
      echo "latest"
    else
      echo "tag:${c#tag:}"
    fi
  fi
}

# Returns on stdout: local | latest | tag:<name>
# Menu UI goes to stderr so $(choose_binary_source) stays clean.
choose_binary_source() {
  echo >&2
  echo "${BLD}Binary source:${NC}" >&2
  echo "  ${GRN}1)${NC} ${BLD}GitHub${NC}    — download from GitHub into $PREFIX/bin/" >&2
  echo "  ${BLU}2)${NC} Local     — copy from ${SCRIPT_DIR}/bin/ into $PREFIX/bin/" >&2
  echo >&2
  local c
  c="$(prompt "Choose 1 or 2" "1")"
  case "$c" in
    1|github|gh)
      choose_github_release
      ;;
    2|local)
      echo "local"
      ;;
    *)
      die "invalid choice: $c"
      ;;
  esac
}

detect_arch() {
  local m
  m="$(uname -m)"
  case "$m" in
    x86_64|amd64) echo "amd64" ;;
    aarch64|arm64) echo "arm64" ;;
    armv7l|armhf) echo "armv7" ;;
    *) echo "$m" ;;
  esac
}

resolve_github_repo() {
  echo "${KAP_GITHUB_REPO:-UnknKriod/kap}"
}

# Fetch release JSON: $1=repo  $2=latest|tag:NAME → prints JSON on stdout
fetch_release_json() {
  local repo="$1" spec="$2"
  local url
  if [[ "$spec" == "latest" ]]; then
    url="https://api.github.com/repos/${repo}/releases/latest"
  else
    local tag="${spec#tag:}"
    url="https://api.github.com/repos/${repo}/releases/tags/${tag}"
  fi
  info "Fetching release metadata: $url"
  http_get "$url" || die "Failed to fetch release (check repo name, tag, and network)"
}

# List all browser_download_url values from release JSON (one per line).
list_asset_urls() {
  printf '%s' "$1" | grep -oE '"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]+"' \
    | sed -E 's/.*"browser_download_url"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/'
}

# Pick URL of a single binary asset: kap-panel-linux-amd64, kap-server-linux-arm64, …
# $1=json  $2=logical name (kap-panel)  $3=arch (amd64)
pick_binary_asset_url() {
  local json="$1" name="$2" arch="$3"
  local want="${name}-linux-${arch}"
  local url
  url="$(list_asset_urls "$json" | grep -F "/${want}" | head -n1 || true)"
  if [[ -z "$url" ]]; then
    # asset name may appear without leading slash in rare cases
    url="$(list_asset_urls "$json" | grep -E "/${want}([^/]*)$" | head -n1 || true)"
  fi
  if [[ -z "$url" ]]; then
    url="$(list_asset_urls "$json" | grep -E "${want}$" | head -n1 || true)"
  fi
  [[ -n "$url" ]] || return 1
  echo "$url"
}

# Fallback: archive asset for linux+arch
pick_archive_asset_url() {
  local json="$1"
  local arch
  arch="$(detect_arch)"
  local url
  url="$(list_asset_urls "$json" \
    | grep -iE 'linux' \
    | grep -iE "${arch}|x86_64|x64" \
    | grep -iE '\.(tar\.gz|tgz|tar\.xz|zip)$' \
    | head -n1 || true)"
  if [[ -z "$url" ]]; then
    url="$(list_asset_urls "$json" \
      | grep -iE 'linux' \
      | grep -iE '\.(tar\.gz|tgz|tar\.xz|zip)$' \
      | head -n1 || true)"
  fi
  [[ -n "$url" ]] || return 1
  echo "$url"
}

# Download from GitHub Releases straight into $PREFIX/bin/.
# Prefers individual assets named kap-<component>-linux-<arch>
# (e.g. kap-panel-linux-amd64). Falls back to a linux archive.
# Clients are never installed on the server.
# $1 = spec (latest | tag:vX.Y.Z)
# $2+ = required binary names (e.g. kap-panel kap-server)
download_github_release() {
  local spec="$1"
  shift
  local required=("$@")
  ((${#required[@]})) || required=(kap-server)

  local repo arch dest_dir
  repo="$(resolve_github_repo)"
  arch="$(detect_arch)"
  dest_dir="$PREFIX/bin"
  info "GitHub repo: https://github.com/${repo} (arch=${arch})"
  info "Install target: $dest_dir"

  local json
  json="$(fetch_release_json "$repo" "$spec")"
  local tag_name
  tag_name="$(printf '%s' "$json" | grep -oE '"tag_name"[[:space:]]*:[[:space:]]*"[^"]+"' | head -n1 \
    | sed -E 's/.*"tag_name"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/' || true)"
  [[ -n "$tag_name" ]] && info "Release: $tag_name"

  mkdir -p "$dest_dir"
  local b copied=0 url dest

  # 1) Prefer per-binary assets: kap-panel-linux-amd64, …
  for b in "${required[@]}"; do
    if url="$(pick_binary_asset_url "$json" "$b" "$arch")"; then
      dest="$dest_dir/$b"
      info "Downloading $b ← $url"
      http_get "$url" "$dest" || die "Download failed: $url"
      chmod +x "$dest"
      info "Installed $b → $dest"
      copied=1
    else
      warn "No asset ${b}-linux-${arch} in release"
    fi
  done

  # 2) Fallback: one archive for anything still missing
  local still=()
  for b in "${required[@]}"; do
    [[ -f "$dest_dir/$b" ]] || still+=("$b")
  done

  if ((${#still[@]})); then
    warn "Missing after direct assets: ${still[*]} — trying archive fallback"
    have_cmd tar || die "tar is required to extract a release archive"
    if ! url="$(pick_archive_asset_url "$json")"; then
      die "No suitable binaries or linux archive for arch ${arch}.
Attach files like kap-panel-linux-${arch} / kap-server-linux-${arch} to
https://github.com/${repo}/releases, or install from local binaries."
    fi
    info "Downloading archive: $url"
    local tmp archive extract
    tmp="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$tmp'" RETURN
    archive="$tmp/release.archive"
    http_get "$url" "$archive" || die "Download failed"
    extract="$tmp/extract"
    mkdir -p "$extract"
    case "$url" in
      *.zip)
        have_cmd unzip || die "unzip required for .zip releases"
        unzip -q "$archive" -d "$extract"
        ;;
      *.tar.xz) tar -xJf "$archive" -C "$extract" ;;
      *) tar -xzf "$archive" -C "$extract" ;;
    esac

    local bin_src sub
    if [[ -d "$extract/bin" ]]; then
      bin_src="$extract/bin"
    else
      bin_src="$(find "$extract" -type d -name bin 2>/dev/null | head -n1 || true)"
    fi
    if [[ -z "$bin_src" || ! -d "$bin_src" ]]; then
      bin_src="$extract"
      sub="$(find "$extract" -maxdepth 3 -type f \( -name 'kap-server*' -o -name 'kap-panel*' \) 2>/dev/null | head -n1 || true)"
      [[ -n "$sub" ]] && bin_src="$(dirname "$sub")"
    fi

    for b in "${still[@]}"; do
      if [[ -f "$bin_src/$b" ]]; then
        install -m 755 "$bin_src/$b" "$dest_dir/$b"
        info "Installed $b → $dest_dir/$b (from archive)"
        copied=1
      elif [[ -f "$bin_src/${b}-linux-${arch}" ]]; then
        install -m 755 "$bin_src/${b}-linux-${arch}" "$dest_dir/$b"
        info "Installed $b → $dest_dir/$b (from archive ${b}-linux-${arch})"
        copied=1
      else
        warn "Required binary not in archive: $b"
      fi
    done

    local decoy_src
    decoy_src="$(find "$extract" -type d -name decoy 2>/dev/null | head -n1 || true)"
    if [[ -n "$decoy_src" && -d "$decoy_src" ]]; then
      mkdir -p "$PREFIX/decoy"
      cp -a "$decoy_src/." "$PREFIX/decoy/"
      info "Updated decoy/ → $PREFIX/decoy"
    fi
  fi

  ((copied)) || die "Could not obtain required binaries: ${required[*]}"
  info "Binaries ready in $dest_dir"
  ls -la "$dest_dir" 2>/dev/null || true
}

# Copy local release binaries into $PREFIX/bin.
# Looks in $SCRIPT_DIR/bin, then $SCRIPT_DIR (flat names or *-linux-<arch>).
install_local_binaries() {
  local required=("$@")
  local src_dir="" b src arch
  arch="$(detect_arch)"
  if [[ -d "$SCRIPT_DIR/bin" ]]; then
    src_dir="$SCRIPT_DIR/bin"
  elif [[ -f "$SCRIPT_DIR/kap-server" || -f "$SCRIPT_DIR/kap-panel" \
      || -f "$SCRIPT_DIR/kap-server-linux-${arch}" || -f "$SCRIPT_DIR/kap-panel-linux-${arch}" ]]; then
    src_dir="$SCRIPT_DIR"
  else
    die "Local binaries not found under $SCRIPT_DIR.
Place kap-panel / kap-server (or *-linux-${arch}) next to the installer or in bin/, 
or choose GitHub download."
  fi
  mkdir -p "$PREFIX/bin"
  for b in "${required[@]}"; do
    src=""
    if [[ -f "$src_dir/$b" ]]; then
      src="$src_dir/$b"
    elif [[ -f "$src_dir/${b}-linux-${arch}" ]]; then
      src="$src_dir/${b}-linux-${arch}"
    fi
    [[ -n "$src" ]] || die "Missing local binary: $b (looked in $src_dir)"
    install -m 755 "$src" "$PREFIX/bin/$b"
    info "Copied $b → $PREFIX/bin/$b"
  done
}

list_existing_bins() {
  local missing=()
  for b in "$@"; do
    if [[ ! -f "$PREFIX/bin/$b" ]]; then
      missing+=("$b")
    fi
  done
  if ((${#missing[@]})); then
    echo "${missing[*]}"
    return 1
  fi
  return 0
}

# Fetch or copy required binaries directly into $PREFIX/bin.
# $1 = mode (all|server)
prepare_binaries() {
  local mode="$1"
  local required=(kap-server)
  if [[ "$mode" == "all" ]]; then
    required=(kap-panel kap-server)
  fi

  mkdir -p "$PREFIX"/{bin,data,decoy,etc}

  local source
  source="$(choose_binary_source)"

  case "$source" in
    local)
      install_local_binaries "${required[@]}"
      ;;
    latest|tag:*)
      download_github_release "$source" "${required[@]}"
      ;;
    *) die "unknown binary source: $source" ;;
  esac

  local miss
  if ! miss="$(list_existing_bins "${required[@]}")"; then
    die "Missing binaries in $PREFIX/bin/: $miss

Options:
  • Local: put kap-panel / kap-server next to the installer (or in bin/) and choose source 2
  • GitHub: choose source 1 — assets kap-*-linux-<arch> on
    https://github.com/UnknKriod/kap/releases"
  fi
  chmod +x "$PREFIX/bin/"* 2>/dev/null || true
  info "Using binaries in $PREFIX/bin/ (${required[*]})"
  ls -la "$PREFIX/bin/" 2>/dev/null || true
}

ensure_user() {
  if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
    info "Creating system user '$SERVICE_USER'"
    useradd --system --home "$PREFIX" --shell /usr/sbin/nologin "$SERVICE_USER" || true
  fi
}

# Download decoy HTML pages as individual files from raw.githubusercontent.com.
# Does not use archives. Skips files that already exist under $PREFIX/decoy
# unless FORCE_DECOY=1.
download_decoy_pages() {
  local repo branch base dest rel path url
  repo="$(resolve_github_repo)"
  branch="${KAP_GITHUB_BRANCH:-main}"
  base="https://raw.githubusercontent.com/${repo}/${branch}/decoy"
  dest="$PREFIX/decoy"

  # Relative paths under decoy/
  local files=(
    default/index.html
    cdn-status/index.html
  )

  mkdir -p "$dest"
  info "Fetching decoy pages from ${CYN}${base}/${NC} …"

  local ok=0 fail=0
  for rel in "${files[@]}"; do
    path="$dest/$rel"
    if [[ -f "$path" && "${FORCE_DECOY:-0}" != "1" ]]; then
      info "decoy/$rel already present — skip"
      ok=$((ok + 1))
      continue
    fi
    mkdir -p "$(dirname "$path")"
    url="${base}/${rel}"
    if http_get "$url" "$path"; then
      # Reject obvious GitHub 404 HTML bodies saved as "success" without -f on some tools
      if [[ -s "$path" ]] && ! grep -q '404: Not Found' "$path" 2>/dev/null; then
        chmod 644 "$path" 2>/dev/null || true
        info "decoy/$rel OK"
        ok=$((ok + 1))
      else
        rm -f "$path"
        warn "decoy/$rel empty or not found at $url"
        fail=$((fail + 1))
      fi
    else
      warn "decoy/$rel download failed: $url"
      fail=$((fail + 1))
    fi
  done

  if ((ok == 0)); then
    warn "No decoy pages installed — server will use minimal stubs (check branch ${branch} and that decoy/ is in the repo)"
  else
    info "Decoy ready in $dest ($ok file(s)${fail:+, $fail failed})"
  fi
}

# Download deploy/nginx.conf.example from raw.githubusercontent.com into $PREFIX/share/.
download_nginx_example() {
  local repo branch url dest
  repo="$(resolve_github_repo)"
  branch="${KAP_GITHUB_BRANCH:-main}"
  dest="$PREFIX/share/nginx.conf.example"
  mkdir -p "$PREFIX/share"

  # Prefer local copy next to the installer
  if [[ -f "$SCRIPT_DIR/nginx.conf.example" ]]; then
    install -m 644 "$SCRIPT_DIR/nginx.conf.example" "$dest"
    info "nginx.conf.example ← local"
    return 0
  fi
  if [[ -f "$SCRIPT_DIR/deploy/nginx.conf.example" ]]; then
    install -m 644 "$SCRIPT_DIR/deploy/nginx.conf.example" "$dest"
    info "nginx.conf.example ← local deploy/"
    return 0
  fi

  if [[ -f "$dest" && "${FORCE_NGINX_EXAMPLE:-0}" != "1" ]]; then
    info "nginx.conf.example already present — skip"
    return 0
  fi

  url="https://raw.githubusercontent.com/${repo}/${branch}/deploy/nginx.conf.example"
  info "Fetching nginx.conf.example from ${CYN}${url}${NC} …"
  if http_get "$url" "$dest" && [[ -s "$dest" ]] && ! grep -q '404: Not Found' "$dest" 2>/dev/null; then
    chmod 644 "$dest" 2>/dev/null || true
    info "nginx.conf.example → $dest"
  else
    rm -f "$dest"
    warn "nginx.conf.example download failed (optional reference file)"
  fi
}

# Shared assets (decoy, nginx example) + ownership. Binaries already in $PREFIX/bin.
install_files() {
  local mode="$1"
  info "Finalizing install under $PREFIX"
  mkdir -p "$PREFIX"/{bin,data,decoy,etc,share}

  # 1) Local decoy next to the installer (optional)
  if [[ -d "$SCRIPT_DIR/decoy" ]]; then
    cp -a "$SCRIPT_DIR/decoy/." "$PREFIX/decoy/"
    info "Copied local decoy/ → $PREFIX/decoy"
  fi

  # 2) Fill/refresh from GitHub raw (individual files, not an archive)
  download_decoy_pages
  download_nginx_example

  chown -R "$SERVICE_USER:$SERVICE_USER" "$PREFIX"
}

write_env_all() {
  local panel_listen="$1" kap_listen="$2" server_url="$3" admin_pass="$4" jwt_secret="$5"
  cat > "$PREFIX/etc/kap-panel.env" <<EOF
# Generated by install.sh — mode: all-in-one
KAP_DB=$PREFIX/data/kap.db
KAP_DECOY=$PREFIX/decoy
KAP_PANEL_LISTEN=$panel_listen
KAP_LISTEN=$kap_listen
KAP_SERVER_URL=$server_url
KAP_ADMIN_USER=admin
KAP_ADMIN_PASS=$admin_pass
KAP_JWT_SECRET=$jwt_secret
EOF
  chmod 640 "$PREFIX/etc/kap-panel.env"
  chown root:"$SERVICE_USER" "$PREFIX/etc/kap-panel.env"
}

write_env_server() {
  local kap_listen="$1" db_or_psk_mode="$2" value="$3"
  if [[ "$db_or_psk_mode" == "db" ]]; then
    cat > "$PREFIX/etc/kap-server.env" <<EOF
# Generated by install.sh — mode: server-only (shared DB)
KAP_DB=$value
KAP_LISTEN=$kap_listen
EOF
  else
    cat > "$PREFIX/etc/kap-server.env" <<EOF
# Generated by install.sh — mode: server-only (legacy global PSK)
KAP_PSK=$value
KAP_LISTEN=$kap_listen
EOF
  fi
  chmod 640 "$PREFIX/etc/kap-server.env"
  chown root:"$SERVICE_USER" "$PREFIX/etc/kap-server.env"
}

install_unit_all() {
  cat > "$SYSTEMD_DIR/kap-panel.service" <<EOF
[Unit]
Description=KAP Panel + embedded KAP server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
EnvironmentFile=$PREFIX/etc/kap-panel.env
WorkingDirectory=$PREFIX
ExecStart=$PREFIX/bin/kap-panel \\
  -listen \${KAP_PANEL_LISTEN} \\
  -kap-listen \${KAP_LISTEN} \\
  -db \${KAP_DB} \\
  -decoy \${KAP_DECOY} \\
  -server-url \${KAP_SERVER_URL} \\
  -admin-user \${KAP_ADMIN_USER} \\
  -admin-pass \${KAP_ADMIN_PASS} \\
  -jwt-secret \${KAP_JWT_SECRET} \\
  -with-server
Restart=on-failure
RestartSec=3
LimitNOFILE=65535
# Hardening
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$PREFIX/data $PREFIX/decoy
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable kap-panel.service
  systemctl restart kap-panel.service
  info "systemd: kap-panel.service enabled and started"
}

install_unit_server() {
  cat > "$SYSTEMD_DIR/kap-server.service" <<EOF
[Unit]
Description=KAP edge server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
EnvironmentFile=$PREFIX/etc/kap-server.env
WorkingDirectory=$PREFIX
ExecStart=/bin/sh -c 'if [ -n "\$KAP_DB" ]; then exec $PREFIX/bin/kap-server -listen "\$KAP_LISTEN" -db "\$KAP_DB"; else exec $PREFIX/bin/kap-server -listen "\$KAP_LISTEN" -psk "\$KAP_PSK"; fi'
Restart=on-failure
RestartSec=3
LimitNOFILE=65535
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$PREFIX/data
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable kap-server.service
  systemctl restart kap-server.service
  info "systemd: kap-server.service enabled and started"
}

rand_hex() {
  if have_cmd openssl; then
    openssl rand -hex "$1"
  else
    head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n' | head -c "$(($1 * 2))"
  fi
}

print_summary_all() {
  local panel_listen="$1" kap_listen="$2" server_url="$3"
  cat <<EOF

${GRN}=== KAP all-in-one installed ===${NC}
  Prefix:     $PREFIX
  Panel:      http://${panel_listen}/
  KAP bind:   ${kap_listen}
  Public URL: ${server_url:-"(set KAP_SERVER_URL / -server-url)"}
  DB:         $PREFIX/data/kap.db
  Decoy:      $PREFIX/decoy
  Env:        $PREFIX/etc/kap-panel.env
  Unit:       systemctl status kap-panel

Login with the admin password you set.
Create a user in the panel and copy the client command shown there
(run kap-client on the *client* machine — it is not installed on the server).

Nginx example: $PREFIX/share/nginx.conf.example

Uninstall: sudo bash deploy/uninstall.sh  (or $PREFIX/../ if copied)
EOF
}

print_summary_server() {
  local kap_listen="$1"
  cat <<EOF

${GRN}=== KAP server-only installed ===${NC}
  Prefix:   $PREFIX
  KAP bind: ${kap_listen}
  Env:      $PREFIX/etc/kap-server.env
  Unit:     systemctl status kap-server

Point nginx at the KAP listen address for requests with sid= query.
EOF
}

# Inject "include <kap nginx conf>;" into nginx http { } block if nginx is present.
wire_nginx_include() {
  local conf_path="$1"
  local nginx_conf="${NGINX_CONF:-/etc/nginx/nginx.conf}"
  if ! have_cmd nginx; then
    warn "nginx not found — skip auto-include (install nginx later and re-run or add include manually)"
    return 0
  fi
  if [[ ! -f "$nginx_conf" ]]; then
    warn "nginx.conf not found at $nginx_conf — skip auto-include"
    return 0
  fi
  # Already included?
  if grep -qF "$conf_path" "$nginx_conf" 2>/dev/null; then
    info "nginx already includes $conf_path"
    return 0
  fi
  # Prefer conf.d drop-in if directory exists
  local conf_d="/etc/nginx/conf.d"
  if [[ -d "$conf_d" ]]; then
    local drop="$conf_d/kap-panel.conf"
    echo "include $conf_path;" > "$drop"
    info "Wrote $drop → include $conf_path"
  else
    # Insert before the last closing brace of http { } — simple approach:
    # append include on the line after "http {"
    if grep -qE '^\s*http\s*\{' "$nginx_conf"; then
      local bak="${nginx_conf}.bak.kap"
      cp -a "$nginx_conf" "$bak"
      # Insert include right after the first http {
      awk -v inc="    include $conf_path;" '
        BEGIN{done=0}
        {
          print
          if (!done && $0 ~ /^\s*http\s*\{/) {
            print inc
            done=1
          }
        }
      ' "$bak" > "$nginx_conf"
      info "Inserted include into $nginx_conf (backup: $bak)"
    else
      warn "Could not find http { in $nginx_conf — add manually: include $conf_path;"
      return 0
    fi
  fi
  if nginx -t 2>/dev/null; then
    systemctl reload nginx 2>/dev/null || nginx -s reload 2>/dev/null || warn "nginx reload failed — start nginx when ready"
    info "nginx config test OK"
  else
    warn "nginx -t failed after include — check $nginx_conf / $conf_path"
    nginx -t 2>&1 || true
  fi
}

main() {
  need_root

  local mode
  mode="$(choose_mode)"

  local panel_listen kap_listen server_url admin_pass jwt_secret
  kap_listen="$(prompt "KAP listen address" "0.0.0.0:8443")"

  if [[ "$mode" == "all" ]]; then
    panel_listen="$(prompt "Panel listen address" "0.0.0.0:8080")"
    server_url="$(prompt "Public KAP URL for client configs (e.g. https://cdn.example.com)" "")"
    admin_pass="$(prompt_secret "Admin password (first login)")"
    [[ -n "$admin_pass" ]] || die "admin password required"
    jwt_secret="$(rand_hex 32)"
    prepare_binaries all
    ensure_user
    install_files all
    write_env_all "$panel_listen" "$kap_listen" "$server_url" "$admin_pass" "$jwt_secret"
    install_unit_all
    # Generate a starter nginx include path (panel will overwrite on Apply)
    mkdir -p "$PREFIX/data"
    if [[ ! -f "$PREFIX/data/nginx-kap.conf" ]]; then
      cat > "$PREFIX/data/nginx-kap.conf" <<NGX
# Placeholder — open panel SSL/Nginx and press Apply to regenerate
NGX
    fi
    wire_nginx_include "$PREFIX/data/nginx-kap.conf"
    print_summary_all "$panel_listen" "$kap_listen" "$server_url"
  else
    echo
    echo "Auth mode for edge server:"
    echo "  1) Shared SQLite DB path (same file as panel host, e.g. NFS)"
    echo "  2) Legacy single global PSK"
    local am
    am="$(prompt "Choose 1 or 2" "1")"
    local value
    if [[ "$am" == "1" ]]; then
      value="$(prompt "Path to shared kap.db" "$PREFIX/data/kap.db")"
      prepare_binaries server
      ensure_user
      install_files server
      write_env_server "$kap_listen" db "$value"
    else
      value="$(prompt_secret "Global PSK (hex or string)")"
      [[ -n "$value" ]] || die "PSK required"
      prepare_binaries server
      ensure_user
      install_files server
      write_env_server "$kap_listen" psk "$value"
    fi
    install_unit_server
    print_summary_server "$kap_listen"
  fi
}

main "$@"
