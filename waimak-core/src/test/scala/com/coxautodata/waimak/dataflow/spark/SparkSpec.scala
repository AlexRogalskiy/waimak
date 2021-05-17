package com.coxautodata.waimak.dataflow.spark

import java.nio.file.Files
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.SystemUtils
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/**
 * Created by Vicky Avison on 24/10/17.
 */
trait SparkSpec extends AnyFunSpec with Matchers with BeforeAndAfterEach {

  var sparkSession: SparkSession = _
  val master = "local[2]"
  val appName: String

  /**
   * In spark 3  */
  def parquetLegacyModeForSpark2To3(enabled: Boolean = false): String =
    if (enabled) "LEGACY" else "CORRECTED"

  /**
   * Allows configuring spark session options. Override this in your own tests to set any
   * config options you might wish to add. This default implementation also detects if you are
   * running the tests on windows, and sets the bindAddress option to make the tests work.
   *
   * @see com.coxautodata.waimak.metastore.TestHiveDBConnector for an example
   * @return a spark session
   */
  def builderOptions: SparkSession.Builder => SparkSession.Builder = { sparkSession =>
    if (SystemUtils.IS_OS_WINDOWS) sparkSession.config("spark.driver.bindAddress", "localhost")
    else sparkSession
  }

  override def beforeEach(): Unit = {
    val preBuilder = SparkSession
      .builder()
      .appName(appName)
      .master(master)
      .config("spark.executor.memory", "2g")
      .config("spark.ui.enabled", "false")

    sparkSession = builderOptions(preBuilder).getOrCreate()

    getSparkVersion(sparkSession) match {
      case SparkVersion(2, _, _) => ()
      case SparkVersion(3, _, _) => configureParquetReaderWriterOptionsForSpark3(sparkSession)
      case _ => ()
    }
  }

  override def afterEach(): Unit = {
    sparkSession.stop()
  }

  def getSparkVersion(spark: SparkSession): SparkVersion =
    spark.version.split(".").toList.map(_.toInt) match {
      case maj :: min :: p :: Nil => SparkVersion(maj, min, Some(p))
      case maj :: min :: Nil => SparkVersion(maj, min, None)
      case _@v => throw new IllegalStateException(s"The spark version of ${v.mkString} cannot be parsed")
    }

  def configureParquetReaderWriterOptionsForSpark3(sparkSession: SparkSession): Unit = {
    val setting = parquetLegacyModeForSpark2To3()
    sparkSession.conf.set("spark.sql.legacy.parquet.int96RebaseModeInWrite", setting)
    sparkSession.conf.set("spark.sql.legacy.parquet.int96RebaseModeInRead", setting)
    sparkSession.conf.set("spark.sql.legacy.parquet.datetimeRebaseModeInWrite", setting)
    sparkSession.conf.set("spark.sql.legacy.parquet.datetimeRebaseModeInRead", setting)
  }
}


trait SparkAndTmpDirSpec extends SparkSpec {

  var testingBaseDir: java.nio.file.Path = _
  var testingBaseDirName: String = _
  var tmpDir: Path = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    testingBaseDir = Files.createTempDirectory("test_output")
    testingBaseDirName = testingBaseDir.toString
    tmpDir = new Path(testingBaseDir.toAbsolutePath.toString + "/tmp")
  }

  override def afterEach(): Unit = {
    super.afterEach()
    FileUtils.deleteDirectory(testingBaseDir.toFile)
  }
}

case class SparkVersion(major: Int, minor: Int, point: Option[Int])
