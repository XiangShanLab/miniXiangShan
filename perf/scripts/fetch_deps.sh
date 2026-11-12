#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PERF_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
ROOT=$(cd -- "$PERF_DIR/.." && pwd)
DEPS_DIR=${DEPS_DIR:-"$ROOT/.deps"}

# shellcheck source=../deps.lock
source "$PERF_DIR/deps.lock"

export GIT_TERMINAL_PROMPT=0

COREMARK_ONLY=0
if [[ ${1:-} == --coremark-only && $# == 1 ]]; then
  COREMARK_ONLY=1
elif [[ $# != 0 ]]; then
  echo "Usage: $0 [--coremark-only]" >&2
  exit 2
fi

cache_key=$(
  cat "$PERF_DIR/deps.lock" \
      "$PERF_DIR/deps-checksums.sha256" \
      "$PERF_DIR"/patches/nutshell/*.patch \
      "$PERF_DIR"/patches/coremark/*.patch \
      "$PERF_DIR/platform/nutshell/NutShellPerfTop.scala" |
    sha256sum | awk '{print $1}'
)
stamp="$DEPS_DIR/.prepared"

cache_valid() {
  [[ ${FORCE_DEPS:-0} != 1 ]] &&
    [[ -f "$stamp" && $(<"$stamp") == "$cache_key" ]] &&
    [[ -f "$DEPS_DIR/NutShell/src/main/scala/sim/NutShellPerfTop.scala" ]] &&
    [[ -e "$DEPS_DIR/NutShell/difftest/.git" ]] &&
    grep -q 'coremark_report' "$DEPS_DIR/coremark/core_main.c"
}

write_versions() {
  mkdir -p "$DEPS_DIR" "$PERF_DIR/.build"
  {
    echo "NutShell repository: $NUTSHELL_REPO"
    echo "NutShell revision:   $(git -C "$DEPS_DIR/NutShell" rev-parse HEAD 2>/dev/null || echo "$NUTSHELL_REF")"
    echo "CoreMark repository: $COREMARK_REPO"
    echo "CoreMark revision:   $(git -C "$DEPS_DIR/coremark" rev-parse HEAD 2>/dev/null || echo "$COREMARK_REF")"
    echo "CoreMark ref:        $COREMARK_REF"
    if [[ -d "$DEPS_DIR/NutShell/difftest/.git" || -f "$DEPS_DIR/NutShell/difftest/.git" ]]; then
      echo "difftest revision:   $(git -C "$DEPS_DIR/NutShell/difftest" rev-parse HEAD)"
    fi
  } > "$DEPS_DIR/versions.txt"
  cp "$DEPS_DIR/versions.txt" "$PERF_DIR/.build/dependencies.txt"
}

if [[ "$COREMARK_ONLY" == 0 ]] && cache_valid; then
  echo "Using cached external sources in $DEPS_DIR"
  write_versions
  exit 0
fi

for tool in git sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "ERROR: required tool not found: $tool" >&2
    exit 1
  }
done

checkout_ref() {
  local name=$1 repo=$2 ref=$3 dir=$4
  echo "Preparing $name from $repo"
  if [[ -d "$dir" && ! -d "$dir/.git" ]]; then
    echo "ERROR: $dir exists but is not a Git checkout; move it away or set DEPS_DIR" >&2
    exit 1
  fi
  if [[ ! -d "$dir/.git" ]]; then
    mkdir -p "$(dirname -- "$dir")"
    git clone --no-checkout --filter=blob:none "$repo" "$dir"
  fi
  git -C "$dir" remote set-url origin "$repo"

  local commit
  if [[ "$ref" =~ ^[0-9a-fA-F]{40}$ ]] &&
     git -C "$dir" rev-parse --verify --quiet "$ref^{commit}" >/dev/null; then
    commit=$(git -C "$dir" rev-parse "$ref^{commit}")
  else
    git -C "$dir" fetch --depth 1 --force origin "$ref"
    commit=$(git -C "$dir" rev-parse FETCH_HEAD)
  fi
  git -C "$dir" checkout --force --detach "$commit"
  git -C "$dir" reset --hard --quiet "$commit"
  git -C "$dir" clean -ffdx --quiet
}

apply_patches() {
  local dir=$1
  shift
  local patch
  for patch in "$@"; do
    echo "Applying $(basename -- "$patch")"
    git -C "$dir" apply --check "$patch"
    git -C "$dir" apply "$patch"
  done
}

mkdir -p "$DEPS_DIR"
if [[ "$COREMARK_ONLY" == 1 ]]; then
  core_dir=${COREMARK_DIR:-"$DEPS_DIR/coremark"}
  if [[ -e "$core_dir" ]]; then
    [[ $(git -C "$core_dir" rev-parse HEAD) == "$COREMARK_REF" ]] &&
      git -C "$core_dir" apply --reverse --check \
        "$PERF_DIR/patches/coremark/0001-report-results.patch" || {
      echo "ERROR: existing $core_dir is not the prepared CoreMark revision." >&2
      echo "Set COREMARK_DIR to a new cache directory; existing files were preserved." >&2
      exit 1
    }
    echo "Using prepared CoreMark sources in $core_dir"
  else
    checkout_ref "CoreMark" "$COREMARK_REPO" "$COREMARK_REF" "$core_dir"
    (cd "$core_dir" && sha256sum --check "$PERF_DIR/deps-checksums.sha256")
    apply_patches "$core_dir" "$PERF_DIR"/patches/coremark/*.patch
  fi
  exit 0
fi

checkout_ref "NutShell" "$NUTSHELL_REPO" "$NUTSHELL_REF" "$DEPS_DIR/NutShell"
git -C "$DEPS_DIR/NutShell" submodule update --init --recursive --depth 1

checkout_ref "CoreMark" "$COREMARK_REPO" "$COREMARK_REF" "$DEPS_DIR/coremark"
(cd "$DEPS_DIR/coremark" && sha256sum --check "$PERF_DIR/deps-checksums.sha256")

apply_patches "$DEPS_DIR/NutShell" "$PERF_DIR"/patches/nutshell/*.patch
cp "$PERF_DIR/platform/nutshell/NutShellPerfTop.scala" \
  "$DEPS_DIR/NutShell/src/main/scala/sim/NutShellPerfTop.scala"

apply_patches "$DEPS_DIR/coremark" "$PERF_DIR"/patches/coremark/*.patch

printf '%s\n' "$cache_key" > "$stamp"
write_versions
echo "External sources are ready."
