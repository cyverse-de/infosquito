FROM clojure:alpine

VOLUME ["/etc/iplant/de"]

ARG git_commit=unknown
ARG version=unknown
LABEL org.iplantc.de.infosquito.git-ref="$git_commit" \
      org.iplantc.de.infosquito.version="$version"

COPY . /usr/src/app
COPY conf/main/logback.xml /usr/src/app/logback.xml

WORKDIR /usr/src/app

RUN apk add --update git && \
    rm -rf /var/cache/apk

RUN lein uberjar && \
    cp target/infosquito-standalone.jar .

RUN ln -s "/usr/bin/java" "/bin/infosquito"

ENTRYPOINT ["infosquito", "-Dlogback.configurationFile=/etc/iplant/de/logging/infosquito-logging.xml", "-cp", ".:infosquito-standalone.jar", "infosquito.core"]
CMD ["--help"]
