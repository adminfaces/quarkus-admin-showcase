# This Dockerfile is used in order to build a container that runs the Quarkus application in JVM mode
#
# Before building the docker image run:
#
# mvn package
#
# Then, build the image with:
#
# docker build -f docker/Dockerfile -t rmpestano/quarkus-admin-showcase .
#
# Then run the container using:
#
# docker run -i --rm -p 8080:8080 rmpestano/quarkus-admin-showcase
#
FROM fabric8/java-alpine-openjdk11-jre

COPY target/quarkus-app/lib/ /deployments/lib/
COPY target/quarkus-app/*.jar /deployments/
COPY target/quarkus-app/app/ /deployments/app/
COPY target/quarkus-app/quarkus/ /deployments/quarkus/

RUN export JAVA_OPTIONS="$JAVA_OPTIONS -Xmx128m -Xms128m"
ENTRYPOINT [ "/deployments/run-java.sh" ]
