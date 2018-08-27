#### Default Image, overwritten and set in makefile
ARG IMAGE_TARGET=openjdk:8-jre-slim

#### FIRST STAGE QEMU AND ONOS BINARIES ####
FROM chrisioa/myonosbase AS qemu
ARG QEMU_VERSION=v2.12.0
ARG QEMU=x86_64
RUN echo QEMU
ADD https://github.com/multiarch/qemu-user-static/releases/download/${QEMU_VERSION}/qemu-${QEMU}-static /qemu-${QEMU}-static
RUN chmod +x /qemu-${QEMU}-static

#### SECOND STAGE IS THE RUNTIME ENVIRONMENT ####
FROM ${IMAGE_TARGET}

COPY --from=qemu /qemu-${QEMU}-static /usr/bin/qemu-${QEMU}-static

# Change to /root directory
RUN apt-get update && apt-get install -y curl && mkdir -p /root/onos
WORKDIR /root/onos

# Install ONOS
COPY --from=qemu /root/onos/ .

# Configure ONOS to log to stdout
RUN sed -ibak '/log4j.rootLogger=/s/$/, stdout/' $(ls -d apache-karaf-*)/etc/org.ops4j.pax.logging.cfg

LABEL org.label-schema.name="ONOS" \
      org.label-schema.description="SDN Controller" \
      org.label-schema.usage="http://wiki.onosproject.org" \
      org.label-schema.url="http://onosproject.org" \
      org.label-scheme.vendor="Open Networking Foundation" \
      org.label-schema.schema-version="1.0"

# Ports
# 6653 - OpenFlow
# 6640 - OVSDB
# 8181 - GUI
# 8101 - ONOS CLI
# 9876 - ONOS intra-cluster communication
EXPOSE 6653 6640 8181 8101 9876

# Get ready to run command
ENTRYPOINT ["./bin/onos-service"]
CMD ["server"]
