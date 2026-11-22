#!/bin/sh
# The integrated Gradle project is generated from the locked Kazumi source.
exec python3 "$(dirname "$0")/integration/kazumi/build_android.py" --gradle "$@"
