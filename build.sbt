ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.11.8"

lazy val root = (project in file("."))
  .settings(
    name := "upsert",
    idePackagePrefix := Some("com.macquarie.redshift_poc")
  )
