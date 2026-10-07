# The burst runner: a small image that fires the burst at the app from inside the same private network.
# It is a test tool, not the app. The app keeps its one Dockerfile in backend/.
#
# Build from the repository root:   docker build -f loadtest/Dockerfile -t seat-burst .
FROM python:3.12-slim

WORKDIR /burst
COPY loadtest/burst.py loadtest/run.sh scripts/preopen_probe.py ./

RUN useradd --system runner
USER runner

CMD ["bash", "run.sh"]
