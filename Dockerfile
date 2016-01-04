FROM discoenv/javabase

USER root
VOLUME ["/etc/iplant/de"]

COPY conf/main/logback.xml /
COPY target/infosquito-standalone.jar /

ARG git_commit=unknown
ARG buildenv_git_commit=unknown
ARG version=unknown
LABEL org.iplantc.de.infosquito.git-ref="$git_commit" \
      org.iplantc.de.infosquito.version="$version" \
      org.iplantc.de.buildenv.git-ref="$buildenv_git_commit"

RUN ln -s "/opt/jdk/bin/java" "/bin/infosquito"
ENTRYPOINT ["infosquito", "-Dlogback.configurationFile=/etc/iplant/de/logging/infosquito-logging.xml", "-cp", ".:infosquito-standalone.jar", "infosquito.core"]
CMD ["--help"]

