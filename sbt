#!/bin/sh

LATEST=http://typesafe.artifactoryonline.com/typesafe/ivy-releases/org.scala-tools.sbt/sbt-launch/0.11.2/sbt-launch.jar

if [ ! -d .sbtlib ]; then
  mkdir .sbtlib
fi

if [ ! -f .sbtlib/sbt-launcher.jar ]; then
  echo "Fetching sbt"
  curl --progress-bar $LATEST > .sbtlib/sbt-launcher.jar
fi

java \
-Duser.timezone=UTC \
-Djava.awt.headless=true \
-Dfile.encoding=UTF-8 \
-XX:MaxPermSize=256m \
-Xmx1g \
-noverify \
-jar .sbtlib/sbt-launcher.jar \
"$@"
