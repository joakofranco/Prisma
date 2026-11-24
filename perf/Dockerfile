FROM eclipse-temurin:21-jre-alpine

ARG JMETER_VERSION=5.6.3
SHELL ["/bin/ash", "-o", "pipefail", "-c"]

RUN apk add --no-cache bash python3 py3-matplotlib py3-reportlab font-dejavu \
 && cd /tmp \
 && wget -q "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" \
 && wget -q "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz.sha512" \
 && echo "$(cut -d' ' -f1 "apache-jmeter-${JMETER_VERSION}.tgz.sha512")  apache-jmeter-${JMETER_VERSION}.tgz" | sha512sum -c - \
 && mkdir -p /opt \
 && tar -xzf "apache-jmeter-${JMETER_VERSION}.tgz" -C /opt \
 && mv "/opt/apache-jmeter-${JMETER_VERSION}" /opt/jmeter \
 && rm -rf /tmp/apache-jmeter-* /opt/jmeter/docs /opt/jmeter/printable_docs

ENV JMETER_HOME=/opt/jmeter \
    PATH=/opt/jmeter/bin:$PATH \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /perf
ENTRYPOINT []
CMD ["jmeter", "--version"]
