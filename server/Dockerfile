# Production image for the Svenager server (Grails fat jar).
FROM gradle:8-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:17-jre
# Repo sync shells out to git (deploy keys need ssh).
# uid fixed at 1000 so k8s fsGroup can make the data volume writable.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git openssh-client \
    && rm -rf /var/lib/apt/lists/* \
    && (userdel -r ubuntu || true) \
    && useradd -u 1000 -U -m -d /var/lib/svenager svenager
COPY --from=build /app/build/libs/*.jar /opt/svenager/server.jar
USER svenager
# Relative state (repo checkouts, agent-dist) lands in the volume.
WORKDIR /var/lib/svenager
ENV GRAILS_ENV=production
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/svenager/server.jar"]
