FROM ubuntu:22.04 AS base

RUN apt-get update \
    && apt-get upgrade --yes \
    && apt-get install --yes openjdk-11-jdk \
        curl \
        screen \
        nano \
        sudo

RUN groupadd -r -g 1000 airline \
    && useradd -u 1000 -r -g 1000 -d /home/airline airline \
    && usermod -aG sudo airline \
    && echo '%sudo ALL=(ALL) NOPASSWD:ALL' >> /etc/sudoers
RUN mkdir -p /home/airline/bin \
    && chown -vR airline:airline /home/airline \
    && chmod -vR 755 /home/airline
USER airline
ENV PATH="$PATH:/home/airline/bin"
ENV SBT_CONFIG=/home/airline/.config/sbt


ENV COURSIER_VERSION="2.1.25-M23"
# Using a specific version found in search results
RUN mkdir -p /home/airline/.config/sbt && \
    echo "--allow-empty" > /home/airline/.config/sbt/sbtopts && \
    curl -fL "https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/coursier" -o /home/airline/bin/coursier && \
    chmod +x /home/airline/bin/coursier && \
    /home/airline/bin/coursier bootstrap org.scala-sbt:sbt-launch:1.9.7 --apps sbt --default-java-options "-Xmx1024m" && \
    sbt --version --allow-empty


WORKDIR /home/airline

ENTRYPOINT ["tail", "-f", "/dev/null"]