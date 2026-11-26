#!/usr/bin/env bash
# Source after scripts/common.sh; keep direct script invocations consistent with make.
export NUTSHELL_CORE=${NUTSHELL_CORE:-ooo}
case "$NUTSHELL_CORE" in
  ooo|inorder) ;;
  *) die "NUTSHELL_CORE must be ooo or inorder" ;;
esac
