#!/bin/sh
set -eu

if [ "$(id -u)" -eq 0 ]; then
    mkdir -p /data
    chown 10001:10001 /data
    exec setpriv --reuid=10001 --regid=10001 --clear-groups "$@"
fi

exec "$@"
