#!/bin/sh

set -e

chown -R jetty:jetty /apps /config /data

exec java $JAVA_OPTS -jar -Djava.io.tmpdir=$TMPDIR $JETTY_HOME/start.jar /usr/local/jetty/etc/jetty.xml /usr/local/jetty/etc/jetty-http-forwarded.xml $RUNTIME_OPTS $PLATFORM_OPTS
