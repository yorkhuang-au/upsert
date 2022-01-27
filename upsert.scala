cpackage com.macquarie.rmg.redshift_poc

// import scala.collection.JavaConversions._
import scala.util.{Try,Success,Failure}
import org.apache.spark.sql.{SparkSession, Dataset, Row, Column}
import org.apache.spark.sql.functions._
import scala.language.postfixOps

object UpSert {
  private def getSparkSession(datasetName: String):SparkSession = {
    return SparkSession.builder.appName(s"Upsert_${datasetName}").getOrCreate()
  }

  private def loadSnapshotData(path: String, businessDateCol: String, businessDate: String)(implicit spark:SparkSession):Dataset[Row] = {
    return spark.read.load(path).filter(col(businessDateCol) === businessDate)
  }

  private def loadSCD2Data(path: String)(implicit spark:SparkSession):Dataset[Row] = {
    return spark.read.load(path)
  }

  private def writeSCD2Data(ds: Dataset[Row], path: String)(implicit spark:SparkSession) {
    ds.write.mode("overwrite").save(s"${path}_tmp")
    spark.read.load(s"${path}_tmp").write.mode("overwrite").save(path)
  }

  private def add_hash_value(unchecked_cols: Array[String])(ds: Dataset[Row]): Dataset[Row] = {
    val hash_col = md5(ds.columns.filter(!unchecked_cols.contains(_)).sorted.map(
      col _ andThen 
      (x=>coalesce(x.cast("string"),lit("") )) 
      andThen md5 _).reduce((x,y)=>concat(x,y)))
    
    return ds.withColumn("hash_value", hash_col)
  }

  private def add_row_uuid(ds: Dataset[Row]): Dataset[Row] = {
    val uuid = udf(()=>java.util.UUID.randomUUID.toString)
    return ds.withColumn("row_uuid", uuid())
  }

  private def add_date(date_col: String)(ds: Dataset[Row]): Dataset[Row] = {
    return ds.withColumn("effective_dt", to_date(col(date_col).cast("string"), "yyyyMMdd"))
      // .withColumn("expiry_dt", lit("9999-12-31").cast("date"))
      // .withColumn("effective_process_id", lit(runId))  
  }

  private def comparePK(enriched: Dataset[Row], stage1: Dataset[Row], keyCols: Array[String]): Column = {
    return keyCols.map(c => enriched(c) === stage1(c)).reduce((x,y)=> x && y)
  }

  private def isColsNotNull(ds: Dataset[Row], cols: Array[String]): Column = {
    return cols.map(c => ds(c).isNotNull).reduce((x,y)=> x || y)
  }

  private def upsert(enriched: Dataset[Row], stage1: Dataset[Row], keyCols: Array[String], runId:String): Dataset[Row] = {
    stage1.cache()
    val expired = stage1.filter(col("expiry_process_id").isNotNull)
    val current = stage1.filter(col("expiry_process_id").isNull).alias("current")

    val currentUpd = current.join(enriched.select( "effective_dt", ("hash_value" :: keyCols.toList): _*).alias("enriched"), 
      comparePK(current, enriched, keyCols),
        "left")
      .withColumn("__upd__expiry_dt", 
        when(isColsNotNull(enriched, keyCols) && (enriched("hash_value") =!= current("hash_value")) && (enriched("effective_dt") > current("effective_dt")), 
          date_add(enriched("effective_dt"), -1))
        .otherwise(current("expiry_dt")))
      .withColumn("__upd__expiry_process_id", 
        when(isColsNotNull(enriched, keyCols) && enriched("hash_value") =!= current("hash_value") && enriched("effective_dt") > current("effective_dt"), 
          lit(runId))
        .otherwise(current("expiry_process_id")))
      .select(current("*"), col("__upd__expiry_dt"), col("__upd__expiry_process_id"))
      .drop("expiry_dt", "expiry_process_id")
      .withColumnRenamed("__upd__expiry_dt", "expiry_dt")
      .withColumnRenamed("__upd__expiry_process_id", "expiry_process_id")
    
    val newEnriched = add_row_uuid(enriched.join(current,
        comparePK(enriched, current, keyCols)
        && (
          (enriched("hash_value") === current("hash_value")) 
          || (enriched("effective_dt") <= current("effective_dt"))
          ), "leftanti")
        .withColumn("expiry_process_id", lit(null).cast("string"))
        .withColumn("expiry_dt", lit("9999-12-31").cast("date")) 
        .withColumn("effective_process_id", lit(runId)))
    
    val newStage1 = expired.unionByName(currentUpd).unionByName(newEnriched)
    expired.printSchema
    currentUpd.printSchema
    newEnriched.printSchema

    return newStage1

  }

  private def formatColumns(ds:Dataset[Row], cols: List[String]): Dataset[Row] = {
    val allCols = cols ++ List("hash_value", "effective_dt", "effective_process_id", "expiry_dt", "expiry_process_id")
    return ds.select("row_uuid", allCols: _*)
  }
  def main1(args: Array[String]) {
    val Array(runId, datasetName, snapshotPath, scd2Path, businessDateCol, businessDate, keys, unchecked) = args
    val unchecked_cols = unchecked.split(",")
    val keyCols = keys.split(",")
    
    implicit val spark:SparkSession = getSparkSession(datasetName)
    import spark.implicits._

    Try(loadSnapshotData(snapshotPath, businessDateCol, businessDate)) match {
      case Success(snapshot) => {
        val newStage1 = Try(loadSCD2Data(scd2Path)) match {
          case Success(stage1) => {
            println("========having both snapshot and stage1===========")
            val enriched = (add_hash_value(unchecked_cols) _ 
              andThen add_date(businessDateCol))(snapshot)
            val stage1 = loadSCD2Data(scd2Path)
            val tmpStage1 = upsert(enriched, stage1, keyCols, runId)
            tmpStage1
          }
          case Failure(f) => {
            // stage1 is empty, simply overwrite with new columns
            println("========empty stage1===========")
            val tmpStage1 = (add_hash_value(unchecked_cols) _ 
              andThen add_row_uuid _
              andThen add_date(businessDateCol) _)(snapshot)
              .withColumn("effective_process_id", lit(runId))  
              .withColumn("expiry_dt", lit("9999-12-31").cast("date"))
              .withColumn("expiry_process_id", lit(null).cast("string"))
            tmpStage1
          }
        }
        // newStage1.show(5, false)
        writeSCD2Data(formatColumns(newStage1, snapshot.columns.toList), scd2Path)
      }
      case Failure(f) => {
        println(s"error in loading snapshot ${datasetName}")
        sys.exit(1)
      }
    }

    spark.stop()
  } // end main1
  
  def main(args: Array[String]) {
    // val Array(runId, datasetName, snapshotPath, scd2Path, businessDateCol, businessDate, keys, unchecked) = args
    // val unchecked_cols = unchecked.split(",")
    // val keyCols = keys.split(",")
    
    // implicit val spark:SparkSession = getSparkSession(datasetName)
    implicit val spark:SparkSession = getSparkSession("read redshift")
    import spark.implicits._
    Class.forName("com.amazon.redshift.jdbc.Driver")
    val df = spark.read
      .format("jdbc")
      .option("url", "jdbc:redshift://redshift.awsredshiftpoc.dwh.fordata.syd.non.c1.macquarie.com:5439/redshiftdbtest")
      .option("dbtable", "public.customer")
      .option("user", "redshift")
      .option("password", "Abcx128_gsPf04x")
      .load()
    df.show()
    spark.stop()
  } // end main
}
