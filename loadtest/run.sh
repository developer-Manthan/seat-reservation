#!/usr/bin/env bash
# What the runner does when it starts. Set WHAT on the service:
#   probe   open REQUESTS connections to the app, hold them, call /healthz on all at once (creates no data)
#   burst   the real burst: REQUESTS reservation requests at one new show
# With WHAT unset it does nothing, so a deploy by accident fires nothing.
#
# It always exits with 0: the result is in the log, and a non-zero exit would make the platform start it again.

sleep 5     # the private network name of the app needs a few seconds after start

case "${WHAT:-}" in
  probe) python3 -u preopen_probe.py "$BASE_URL" "${REQUESTS:-20000}" ;;
  burst) python3 -u burst.py ;;
  *)     echo "Nothing to do. Set WHAT to probe or burst." ;;
esac

echo "Runner finished (the command ended with code $?)."
exit 0
