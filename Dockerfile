FROM discoenv/clojure-base:master

ENV CONF_TEMPLATE=/usr/src/app/infosquito.properties.tmpl
ENV CONF_FILENAME=infosquito.properties
ENV PROGRAM=infosquito

COPY project.clj /usr/src/app/
RUN lein deps

COPY conf/main/logback.xml /usr/src/app/
COPY . /usr/src/app

RUN lein uberjar && \
    cp target/infosquito-standalone.jar .

RUN ln -s "/usr/bin/java" "/bin/infosquito"

ENTRYPOINT ["run-service", "-Dlogback.configurationFile=/etc/iplant/de/logging/infosquito-logging.xml", "-cp", ".:infosquito-standalone.jar", "infosquito.core"]

ARG git_commit=unknown
ARG version=unknown
ARG descriptive_version=unknown

LABEL org.cyverse.git-ref="$git_commit"
LABEL org.cyverse.version="$version"
LABEL org.cyverse.descriptive-version="$descriptive_version"
LABEL org.label-schema.vcs-ref="$git_commit"
LABEL org.label-schema.vcs-url="https://github.com/cyverse-de/infosquito"
LABEL org.label-schema.version="$descriptive_version"
