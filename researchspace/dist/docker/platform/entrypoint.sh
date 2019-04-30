#!/bin/sh

set -e

# TODO set blazegraph URL here
[ -f /config/environment.prop ] || echo "appsDirectory=/apps\n" > /config/environment.prop
[ -f /config/global.prop ] || touch /config/global.prop
[ -f /config/ui.prop ] || touch /config/ui.prop

chown -R jetty:jetty /apps /config /data /var/lib/jetty

PLATFORM_OPTS=" -Dconfig.global.homePage=Start -Dconfig.environment.sparqlEndpoint=http://blazegraph:8080/blazegraph/sparql "
# TODO derive this from a DEBUG environment variable
LOG_OPTS="-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.StdErrLog -Dorg.eclipse.jetty.LEVEL=DEBUG"

# TODO wait for availability of the blazegraph service

exec java $JAVA_OPTS $LOG_OPTS -jar -Djava.io.tmpdir=$TMPDIR $JETTY_HOME/start.jar /usr/local/jetty/etc/jetty.xml /usr/local/jetty/etc/jetty-http-forwarded.xml $RUNTIME_OPTS $PLATFORM_OPTS
