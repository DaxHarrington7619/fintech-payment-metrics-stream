#!/bin/sh
set -eu
rm -rf out
mkdir -p out
javac -d out $(find src/main/java -name '*.java')
exec java -cp out fintech.dashboard.PaymentMetricsServer

