FROM gradle:7.0-jdk16 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle shadowjar --no-daemon


FROM openjdk:16.0

EXPOSE 7000

RUN mkdir /app
COPY --from=build /home/gradle/src/build/libs/*-all.jar /app/rubie.jar
ENTRYPOINT ["java", "-jar","/app/rubie.jar", "envvar"]