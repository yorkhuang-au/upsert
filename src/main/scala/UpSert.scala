package com.macquarie.rmg.redshift_poc

// import scala.collection.JavaConversions._
import scala.util.{Try,Success,Failure}
import org.apache.spark.sql.{SparkSession, Dataset, Row, Column}
import org.apache.spark.sql.functions._
import scala.language.postfixOps

object UpSert {
  private def getSparkSession(datasetName: String, accesskey:String, secretkey: String, token: String):SparkSession = {
    return SparkSession.builder.config("fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider")
    .config("fs.s3a.access.key", accesskey)
    .config("fs.s3a.secret.key", secretkey)
    .config("fs.s3a.session.token", token).appName(s"Upsert_${datasetName}").getOrCreate()
  }

  def main(args: Array[String]) {
    val Array(runId, datasetName, snapshotPath, scd2Path, businessDateCol, businessDate, keys, unchecked, accesskey, secretkey, token) = args
    val unchecked_cols = unchecked.split(",")
    val keyCols = keys.split(",")
    
    implicit val spark:SparkSession = getSparkSession(datasetName, accesskey, secretkey, token)
    import spark.implicits._

    println(accesskey, secretkey, token)
    spark.read.load(snapshotPath).limit(10).write.mode("overwrite").save(scd2Path)
    spark.stop()
  } // end main
}
