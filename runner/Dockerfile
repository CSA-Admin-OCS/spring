FROM eclipse-temurin:21-jdk

WORKDIR /runner

COPY Runner.java .

# compile as root: /runner is root-owned, so the runner user cannot write Runner.class
# (user code is compiled and run in a temp directory at request time, not here)
RUN javac Runner.java

# create unprivileged user
RUN useradd --create-home --shell /usr/bin/nologin runner

USER runner

EXPOSE 8592

CMD ["java", "Runner"]
