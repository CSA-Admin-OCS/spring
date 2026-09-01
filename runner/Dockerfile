# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY Runner.java .
RUN javac Runner.java

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache python3 && addgroup -S runner && adduser -S runner -G runner
WORKDIR /app
COPY --from=build /build/Runner*.class ./
USER runner
EXPOSE 8591
CMD ["java", "Runner"]
