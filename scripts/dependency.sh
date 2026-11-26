#!/usr/bin/env bash
# Source after common.sh. Callers serialize access to their checkout with flock.
prepare_source() {
  local name=$1 url=$2 revision=$3 directory=$4
  shift 4
  require_tool git
  if [[ ! -e $directory ]]; then
    git clone --filter=blob:none "$url" "$directory"
    git -C "$directory" checkout --detach "$revision"
  fi
  [[ -d $directory/.git ]] || die "$directory is not a Git checkout"
  [[ $(git -C "$directory" remote get-url origin) == "$url" ]] ||
    die "$name origin must be $url"
  [[ $(git -C "$directory" rev-parse HEAD) == "$revision" ]] ||
    die "$name must be at $revision; existing checkout was preserved: $directory"
  if (($# == 0)); then
    git -C "$directory" diff --quiet HEAD -- || die "$name sources have local modifications"
    return
  fi
  # Construct the expected patched tree in a temporary index. Never reset or
  # clean an existing checkout: mismatching local source must remain recoverable.
  local index tree patch matched=-1 count=0
  local -a patches=("$@")
  index=$(mktemp "$directory/.git/mini-index.XXXXXX")
  rm -f "$index"
  GIT_INDEX_FILE="$index" git -C "$directory" read-tree HEAD
  # Recognize a checkout prepared with an earlier prefix of these patches, so
  # adding a new adaptation can upgrade managed sources without resetting them.
  for patch in "${patches[@]}"; do
    tree=$(GIT_INDEX_FILE="$index" git -C "$directory" write-tree)
    if git -C "$directory" diff --quiet "$tree" --; then
      matched=$count
    fi
    if ! GIT_INDEX_FILE="$index" git -C "$directory" apply --cached "$patch"; then
      rm -f "$index"
      die "cannot apply $name patches to the pinned revision"
    fi
    count=$((count + 1))
  done
  tree=$(GIT_INDEX_FILE="$index" git -C "$directory" write-tree)
  rm -f "$index"
  if git -C "$directory" diff --quiet "$tree" --; then
    echo "Reusing pinned $name sources: $directory"
  elif ((matched >= 0)); then
    git -C "$directory" apply "${patches[@]:matched}"
  else
    die "$name checkout differs from the expected patches; existing files were preserved: $directory"
  fi
}
