FROM ubuntu:xenial AS build-env

RUN apt-get update && apt-get upgrade --yes
RUN DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends apt-transport-https apt-utils ca-certificates curl
RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823 \
 && echo "deb https://dl.bintray.com/sbt/debian /" > /etc/apt/sources.list.d/sbt.list \
 && curl -sSL https://dl.yarnpkg.com/debian/pubkey.gpg | apt-key add - \
 && echo "deb https://dl.yarnpkg.com/debian/ stable main" > /etc/apt/sources.list.d/yarn.list \
 && curl -sSL https://deb.nodesource.com/setup_8.x | bash
RUN DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends nodejs openjdk-8-jdk-headless sbt scala yarn \
 && ln -s /usr/bin/nodejs /usr/local/bin/node

WORKDIR /src
COPY . .

RUN ./build.sh -DbuildEnv=prod package

FROM jetty:9.3.9-jre8-alpine

ENTRYPOINT /entrypoint.sh

VOLUME /apps
VOLUME /config
VOLUME /data

ENV RUNTIME_OPTS "-Dcom.metaphacts.config.baselocation=/config -DruntimeDirectory=/ -Dconfig.environment.appsDirectory=/apps -Dorg.eclipse.jetty.server.Request.maxFormContentSize=104857600"
COPY researchspace/dist/docker/platform/entrypoint.sh /
COPY metaphactory/webapp/etc/* /var/lib/jetty/webapps/etc/

COPY --from=build-env /src/target/platform-2.1-SNAPSHOT.war /var/lib/jetty/webapps/ROOT.war
