#!/usr/bin/env bash
# The burst test: 20,000 reservation requests at one fresh show with 200 seats, all sent at once.
# It checks the six acceptance points and prints RESULT: PASSED or FAILED (exit code 0 or 1).
#
#   bash burst.sh                                            the local app at http://localhost:8080
#   BASE_URL=https://... ADMIN_TOKEN=... bash burst.sh       a deployed app
#   REQUESTS=8000 bash burst.sh                              a smaller burst
#   bash burst.sh --concurrency 1000                         extra options go to scripts/load_test.py
set -euo pipefail
cd "$(dirname "$0")"

export BASE_URL="${BASE_URL:-http://localhost:8080}"
REQUESTS="${REQUESTS:-20000}"

PYTHON="$(command -v python3 || command -v python || true)"
if [ -z "$PYTHON" ]; then
  echo "Python 3 is needed to run the burst test." >&2
  exit 1
fi

extra=()
case "$BASE_URL" in
  http://localhost*|http://127.0.0.1*)
    # Local: the dev admin token is the default, and the database is checked through docker compose at the end.
    ;;
  *)
    if [ -z "${ADMIN_TOKEN:-}" ]; then
      echo "Set ADMIN_TOKEN to the admin token of $BASE_URL." >&2
      exit 1
    fi
    # The database of a deployed app cannot be reached from here, so that last check is skipped.
    extra+=(--no-reconcile)
    ;;
esac

echo "Burst of $REQUESTS requests against $BASE_URL"
exec "$PYTHON" scripts/load_test.py --only storm --requests "$REQUESTS" ${extra[@]+"${extra[@]}"} "$@"
