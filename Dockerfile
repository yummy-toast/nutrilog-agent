FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /build
COPY backend/ backend/
COPY data/usda/selected-foods.json data/usda/selected-foods.json
RUN ./backend/mvnw -B -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
RUN mkdir .data && chown 10001:10001 .data
COPY --from=build /build/backend/target/nutrilog-agent-1.0.0.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
