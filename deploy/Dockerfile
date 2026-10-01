FROM eclipse-temurin:21-jre
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends chromium chromium-driver \
 && rm -rf /var/lib/apt/lists/*
RUN useradd --system --create-home --uid 10001 ocapp
WORKDIR /opt/ocapp
COPY target/ocapp-0.2.0-alpha.1.jar app.jar
USER 10001
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/opt/ocapp/app.jar"]
