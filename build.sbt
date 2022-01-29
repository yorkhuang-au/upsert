ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.11.8"

lazy val root = (project in file("."))
  .settings(
    name := "upsert",
    idePackagePrefix := Some("com.macquarie.rmg.redshift_poc")
  )


libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "2.4.7" % "provided",
  "org.apache.spark" %% "spark-sql" % "2.4.7" % "provided",
  "com.google.guava" % "guava" % "30.0-jre"
)

//libraryDependencies += "com.amazon.redshift" % "redshift-jdbc42" % "2.1.0.1"

// https://mvnrepository.com/artifact/com.google.guava/guava
//libraryDependencies += "com.google.guava" % "guava" % "30.0-jre"

