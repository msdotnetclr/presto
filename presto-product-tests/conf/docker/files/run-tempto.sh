#!/bin/bash

set -euo pipefail

DOCKER_TEMPTO_CONF_DIR="/docker/volumes/conf/tempto"
TEMPTO_CONFIG_FILES="tempto-configuration.yaml" # this comes from classpath
TEMPTO_CONFIG_FILES="${TEMPTO_CONFIG_FILES},${DOCKER_TEMPTO_CONF_DIR}/tempto-configuration-for-docker-default.yaml"

if ! test -z ${TEMPTO_PROFILE_CONFIG_FILE:-}; then
  TEMPTO_CONFIG_FILES="${TEMPTO_CONFIG_FILES},${TEMPTO_PROFILE_CONFIG_FILE}"
fi

if ! test -z ${TEMPTO_EXTRA_CONFIG_FILE:-}; then
  TEMPTO_CONFIG_FILES="${TEMPTO_CONFIG_FILES},${TEMPTO_EXTRA_CONFIG_FILE}"
fi

java \
  "-Djava.util.logging.config.file=${DOCKER_TEMPTO_CONF_DIR}/logging.properties" \
  -Duser.timezone=America/Bahia_Banderas \
  -cp "/docker/volumes/jdbc/driver.jar:/docker/volumes/presto-product-tests/presto-product-tests-executable.jar" \
  com.facebook.presto.tests.TemptoProductTestRunner \
  --report-dir "/docker/volumes/test-reports" \
  --config "${TEMPTO_CONFIG_FILES}" \
  "$@"
