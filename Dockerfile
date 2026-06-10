# --- build stage: compile the agent + demo with the Gradle wrapper ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Copy build inputs first for better layer caching.
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY memsentry-agent ./memsentry-agent
COPY memsentry-demo ./memsentry-demo
# auto-detect finds the container's JDK 21 for the toolchain (the host path in
# gradle.properties is simply absent here and ignored).
RUN ./gradlew --no-daemon clean :memsentry-agent:jar :memsentry-demo:jar

# --- runtime stage: just a JRE + the two jars ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/memsentry-agent/build/libs/memsentry-agent-*.jar memsentry-agent.jar
COPY --from=build /src/memsentry-demo/build/libs/memsentry-demo-*.jar memsentry-demo.jar

# Agent options and demo port are overridable at runtime.
ENV MEMSENTRY_OPTS="histogram=15s,port=7077"
ENV PORT=8099
EXPOSE 7077 8099

# Run the demo app under the MemSentry agent.
ENTRYPOINT ["sh", "-c", "exec java -javaagent:memsentry-agent.jar=${MEMSENTRY_OPTS} -Xmx512m -cp memsentry-demo.jar io.memsentry.demo.DemoApp"]
