organization := "ru.rknrl"

name := "rpc"

version := "1.0"

scalaVersion := "2.11.11"
val akkaVersion = "2.5.1"
val akkaHttpV = "10.0.9"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpV,
  "com.trueaccord.scalapb" %% "scalapb-runtime" % "0.4.8"
)
