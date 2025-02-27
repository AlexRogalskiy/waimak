package com.coxautodata.waimak.dataflow.spark

import java.io.File

import com.coxautodata.waimak.dataflow.spark.CacheMetadataExtension.CACHE_ONLY_REUSED_LABELS
import com.coxautodata.waimak.dataflow.{ActionResult, _}
import org.apache.commons.io.FileUtils
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{AnalysisException, Dataset, SparkSession}

import scala.util.Try

/**
  * Created by Alexei Perelighin on 22/12/17.
  */
class TestSparkDataFlow extends SparkAndTmpDirSpec {

  override val appName: String = "Simple Spark Data Flow"

  // Need to explicitly use sequential like executor with preference to loaders
  val executor = Waimak.sparkExecutor(1, DFExecutorPriorityStrategies.preferLoaders)

  import TestSparkData._

  describe("csv") {

    it("read one") {
      val spark = sparkSession
      import spark.implicits._

      val flow = Waimak.sparkFlow(spark)
        .openCSV(basePath)("csv_1")
        .show("csv_1")

      val (executedActions, finalState) = executor.execute(flow)

      executedActions.foreach(d => println(d.logLabel))

      //validate executed actions
      executedActions.size should be(2)
      executedActions.map(a => a.description) should be(Seq("Action: read Inputs: [] Outputs: [csv_1]", "Action: show Inputs: [csv_1] Outputs: []"))

      finalState.actions.size should be(0) // no actions to execute
      finalState.inputs.size should be(1)
      finalState.inputs.getOption[Dataset[_]]("csv_1").map(_.as[TPurchase].collect()).get should be(purchases)
    }

    it("read two, without prefix") {
      val spark = sparkSession
      import spark.implicits._

      val flow = Waimak.sparkFlow(spark)
        .openCSV(basePath)("csv_1", "csv_2")
        .show("csv_1")
        .show("csv_2")

      val (executedActions, finalState) = executor.execute(flow)

      //validate executed actions
      executedActions.size should be(4)
      executedActions.map(a => a.description) should be(Seq("Action: read Inputs: [] Outputs: [csv_1]", "Action: read Inputs: [] Outputs: [csv_2]", "Action: show Inputs: [csv_1] Outputs: []", "Action: show Inputs: [csv_2] Outputs: []"))

      finalState.actions.size should be(0) // no actions to execute
      finalState.inputs.size should be(2)
      finalState.inputs.getOption[Dataset[_]]("csv_1").map(_.as[TPurchase].collect()).get should be(purchases)
      finalState.inputs.getOption[Dataset[_]]("csv_2").map(_.as[TPerson].collect()).get should be(persons)
    }

    it("read two, with prefix") {
      val spark = sparkSession
      import spark.implicits._
      val flow = Waimak.sparkFlow(spark)
        .openCSV(basePath, None, Some("pr"))("csv_1", "csv_2")
        .show("pr_csv_1")
        .show("pr_csv_2")

      val (executedActions, finalState) = executor.execute(flow)

      //validate executed actions
      executedActions.size should be(4)
      executedActions.map(a => a.description) should be(Seq("Action: read Inputs: [] Outputs: [pr_csv_1]", "Action: read Inputs: [] Outputs: [pr_csv_2]", "Action: show Inputs: [pr_csv_1] Outputs: []", "Action: show Inputs: [pr_csv_2] Outputs: []"))

      finalState.actions.size should be(0) // no actions to execute
      finalState.inputs.size should be(2)
      finalState.inputs.getOption[Dataset[_]]("pr_csv_1").map(_.as[TPurchase].collect()).get should be(purchases)
      finalState.inputs.getOption[Dataset[_]]("pr_csv_2").map(_.as[TPerson].collect()).get should be(persons)
    }

    it("read path") {
      val spark = sparkSession
      import spark.implicits._
      val flow = Waimak.sparkFlow(spark)
        .openFileCSV(s"$basePath/csv_1", "csv_1")
        .show("csv_1")

      val (executedActions, finalState) = executor.execute(flow)

      //validate executed actions
      executedActions.size should be(2)
      executedActions.map(a => a.description) should be(Seq("Action: read Inputs: [] Outputs: [csv_1]", "Action: show Inputs: [csv_1] Outputs: []"))

      finalState.actions.size should be(0) // no actions to execute
      finalState.inputs.size should be(1)
      finalState.inputs.getOption[Dataset[_]]("csv_1").map(_.as[TPurchase].collect()).get should be(purchases)
    }
  }

  describe("readParquet") {

    it("read two parquet folders") {
      val spark = sparkSession
      import spark.implicits._
      val baseDest = testingBaseDir + "/dest"
      val flow = Waimak.sparkFlow(spark)
        .openCSV(basePath)("csv_1", "csv_2")
        .alias("csv_1", "parquet_1")
        .alias("csv_2", "parquet_2")
        .writeParquet(baseDest)("parquet_1", "parquet_2")

      executor.execute(flow)

      val flow2 = Waimak.sparkFlow(spark)
        .openParquet(baseDest)("parquet_1", "parquet_2")

      val (executedActions, finalState) = executor.execute(flow2)
      finalState.inputs.getOption[Dataset[_]]("parquet_1").map(_.as[TPurchase].collect()).get should be(purchases)
      finalState.inputs.getOption[Dataset[_]]("parquet_2").map(_.as[TPerson].collect()).get should be(persons)

    }

    it("read two parquet folders with snapshot directory") {
      val spark = sparkSession
      import spark.implicits._
      val baseDest = testingBaseDir + "/dest"
      val flow = Waimak.sparkFlow(spark, s"$baseDest/tmp")
        .openCSV(basePath)("csv_1", "csv_2")
        .alias("csv_1", "parquet_1")
        .alias("csv_2", "parquet_2")
        .commit("commit")("parquet_1", "parquet_2")
        .push("commit")(ParquetDataCommitter(baseDest).withSnapshotFolder("generated_timestamp=20180509094500"))

      executor.execute(flow)

      val flow2 = Waimak.sparkFlow(spark)
        .openParquet(baseDest, snapshotFolder = Some("generated_timestamp=20180509094500"))("parquet_1", "parquet_2")

      val (_, finalState) = executor.execute(flow2)
      finalState.inputs.getOption[Dataset[_]]("parquet_1").map(_.as[TPurchase].collect()).get should be(purchases)
      finalState.inputs.getOption[Dataset[_]]("parquet_2").map(_.as[TPerson].collect()).get should be(persons)

    }

    it("stage and commit parquet, and force a cache as parquet") {
      import CacheMetadataExtension._
      val spark = sparkSession
      spark.conf.set(CACHE_ONLY_REUSED_LABELS, false)

      import spark.implicits._
      val baseDest = testingBaseDir + "/dest"
      val flow = Waimak.sparkFlow(spark, s"$baseDest/tmp")
        .openCSV(basePath)("csv_1")
        .alias("csv_1", "parquet_1")
        .cacheAsParquet("parquet_1")
        .inPlaceTransform("parquet_1")(df => df)
        .inPlaceTransform("parquet_1")(df => df)
        .commit("commit")("parquet_1")
        .push("commit")(ParquetDataCommitter(baseDest).withSnapshotFolder("generated_timestamp=20180509094500"))
        .prepareForExecution()
        .get

      // Check all post actions made it through
      val interceptorAction = flow.actions.filter(_.outputLabels.contains("parquet_1")).head.asInstanceOf[PostActionInterceptor[Dataset[_]]]
      interceptorAction.postActions.length should be(3)

      // Check they are in the right order
      interceptorAction.postActions.head.isInstanceOf[TransformPostAction[Dataset[_]]] should be(true)
      interceptorAction.postActions.tail.head.isInstanceOf[TransformPostAction[Dataset[_]]] should be(true)
      interceptorAction.postActions.tail.tail.head.isInstanceOf[CachePostAction[Dataset[_]]] should be(true)

      executor.execute(flow)

      val flow2 = Waimak.sparkFlow(spark)
        .openParquet(baseDest, snapshotFolder = Some("generated_timestamp=20180509094500"))("parquet_1")

      val (_, finalState) = executor.execute(flow2)
      finalState.inputs.getOption[Dataset[_]]("parquet_1").map(_.as[TPurchase].collect()).get should be(purchases)
    }

  }

  describe("execute") {
    it("execute a flow inline") {
      val spark = sparkSession
      import spark.implicits._

      val (executedActions, finalState) = Waimak.sparkFlow(spark)
        .openCSV(basePath)("csv_1", "csv_2")
        .show("csv_1")
        .show("csv_2")
        .withExecutor(executor)
        .execute()

      //validate executed actions
      executedActions.size should be(4)
      executedActions.map(a => a.description) should be(Seq("Action: read Inputs: [] Outputs: [csv_1]", "Action: read Inputs: [] Outputs: [csv_2]", "Action: show Inputs: [csv_1] Outputs: []", "Action: show Inputs: [csv_2] Outputs: []"))

      finalState.actions.size should be(0) // no actions to execute
      finalState.inputs.size should be(2)
      finalState.inputs.getOption[Dataset[_]]("csv_1").map(_.as[TPurchase].collect()).get should be(purchases)
      finalState.inputs.getOption[Dataset[_]]("csv_2").map(_.as[TPerson].collect()).get should be(persons)
    }
  }

  describe("sql") {

    it("group by single") {
      val spark = sparkSession
      import spark.implicits._
      val flow = Waimak.sparkFlow(spark)
        .openCSV(basePath)("csv_1")
        .sql("csv_1")("person_summary", "select id, count(item) as item_cnt, sum(amount) as total from csv_1 group by id")
        .show("person_summary")

      val (executedActions, finalState) = executor.execute(flow)

      //validate executed actions
      executedActions.size should be(3)
      executedActions.map(a => a.description) should be(Seq("Action: read Inputs: [] Outputs: [csv_1]", "Action: sql Inputs: [csv_1] Outputs: [person_summary]", "Action: show Inputs: [person_summary] Outputs: []"))

      finalState.actions.size should be(0) // no actions to execute
      finalState.inputs.size should be(2)
      finalState.inputs.getOption[Dataset[_]]("person_summary").map(_.as[TSummary].collect()).get should be(Seq(
        TSummary(Some(1), Some(3), Some(7))
        , TSummary(Some(3), Some(1), Some(2))
        , TSummary(Some(5), Some(3), Some(3))
        , TSummary(Some(2), Some(1), Some(2))
      ))
    }

    it("group by and join with drop of columns") {
      val spark = sparkSession
      import spark.implicits._
      val flow = Waimak.sparkFlow(spark)
        .openCSV(basePath)("csv_1", "csv_2")
        .sql("csv_1")("person_summary", "select id as id_summary, count(item) as item_cnt, sum(amount) as total from csv_1 group by id") // give unique names so that after joins it is easy to drop
        .sql("person_summary", "csv_2")("report_tmp",
        """
          |select * from csv_2 person
          |left join person_summary on person.id = id_summary""".stripMargin
        , "id_summary")
        .transform("report_tmp")("report") { report_tmp => report_tmp.withColumn("calc_1", lit(2)) }
        .printSchema("report")
        .show("report")

      val (executedActions, finalState) = executor.execute(flow)
      //      executedActions.foreach(a => println(a.logLabel))
      //validate executed actions
      executedActions.size should be(7)
      executedActions.map(a => a.description) should be(Seq(
        "Action: read Inputs: [] Outputs: [csv_1]"
        , "Action: read Inputs: [] Outputs: [csv_2]"
        , "Action: sql Inputs: [csv_1] Outputs: [person_summary]"
        , "Action: sql Inputs: [person_summary,csv_2] Outputs: [report_tmp]"
        , "Action: transform 1 -> 1 Inputs: [report_tmp] Outputs: [report]"
        , "Action: printSchema Inputs: [report] Outputs: []"
        , "Action: show Inputs: [report] Outputs: []"
      ))

      finalState.actions.size should be(0) // no actions to execute
      finalState.inputs.size should be(5)
      finalState.inputs.getOption[Dataset[_]]("report").map(_.as[TReport].collect()).get should be(report)
    }

    it("invalid label name") {
      val spark = sparkSession
      val res = intercept[DataFlowException] {
        Waimak.sparkFlow(spark)
          .openCSV(basePath)("csv_1")
          .alias("csv_1", "bad-name")
          .sql("bad-name")("bad-output", "select * from bad-name")
      }
      res.text should be("The following labels contain invalid characters to be used as Spark SQL view names: [bad-name]. " +
        "You can alias the label to a valid name before calling the sql action.")
    }
  }

  describe("joins") {

    it("2 drop") {
      val spark = sparkSession
      import spark.implicits._
      val flow = Waimak.sparkFlow(spark)
        .openCSV(basePath)("csv_1", "csv_2")
        .sql("csv_1")("person_summary", "select id, count(item) as item_cnt, sum(amount) as total from csv_1 group by id")
        .transform("csv_2", "person_summary")("report") { (l, r) => l.join(r, l("id") === r("id"), "left").drop(r("id")) }
        .printSchema("report")
        .show("report")

      val (executedActions, finalState) = executor.execute(flow)
      //            executedActions.foreach(a => println(a.logLabel))
      //validate executed actions
      executedActions.size should be(6)
      executedActions.map(a => a.description) should be(Seq(
        "Action: read Inputs: [] Outputs: [csv_1]"
        , "Action: read Inputs: [] Outputs: [csv_2]"
        , "Action: sql Inputs: [csv_1] Outputs: [person_summary]"
        , "Action: transform 2 -> 1 Inputs: [csv_2,person_summary] Outputs: [report]"
        , "Action: printSchema Inputs: [report] Outputs: []"
        , "Action: show Inputs: [report] Outputs: []"
      ))

      finalState.actions.size should be(0) // no actions to execute
      finalState.inputs.size should be(4)
      finalState.inputs.getOption[Dataset[_]]("report").map(_.withColumn("calc_1", lit(2)).as[TReport].collect()).get should be(report)
    }

  }

  describe("transform") {

    it("chain one by one") {
      val spark = sparkSession
      import spark.implicits._
      val flow = Waimak.sparkFlow(spark)
        .openCSV(basePath)("csv_1", "csv_2")
        .transform("csv_1")("person_summary") { csv_1 =>
          csv_1.groupBy($"id").agg(count($"id").as("item_cnt"), sum($"amount").as("total"))
        }.transform("person_summary", "csv_2")("report") { (person_summary, csv_2) =>
        csv_2.join(person_summary, csv_2("id") === person_summary("id"), "left")
          .drop(person_summary("id"))
          .withColumn("calc_1", lit(2))
      }.printSchema("report")
        .show("report")

      val (executedActions, finalState) = executor.execute(flow)
      //            executedActions.foreach(a => println(a.logLabel))
      //validate executed actions
      executedActions.size should be(6)
      executedActions.map(a => a.description) should be(Seq(
        "Action: read Inputs: [] Outputs: [csv_1]"
        , "Action: read Inputs: [] Outputs: [csv_2]"
        , "Action: transform 1 -> 1 Inputs: [csv_1] Outputs: [person_summary]"
        , "Action: transform 2 -> 1 Inputs: [person_summary,csv_2] Outputs: [report]"
        , "Action: printSchema Inputs: [report] Outputs: []"
        , "Action: show Inputs: [report] Outputs: []"
      ))

      finalState.actions.size should be(0) // no actions to execute
      finalState.inputs.size should be(4)
      finalState.inputs.getOption[Dataset[_]]("report").map(_.as[TReport].collect()).get should be(report)
    }
  }

  describe("write") {

    it("writeCSV") {
      val spark = sparkSession
      import spark.implicits._
      val baseDest = testingBaseDir + "/dest"

      val flow = SparkDataFlow.empty(sparkSession, tmpDir)
        .openCSV(basePath)("csv_1", "csv_2")
        .alias("csv_1", "items")
        .alias("csv_2", "person")
        .writeCSV(baseDest, Map("header" -> "true"))("person")
        .writePartitionedCSV(baseDest, options = Map("header" -> "true"))("items", "amount")

      val (executedActions, finalState) = executor.execute(flow)
      finalState.inputs.size should be(4)
      new File(s"$baseDest/items").list().toSeq.filter(_.startsWith("amount=")).sorted should be(Seq("amount=1", "amount=2", "amount=4"))
      spark.read.option("inferSchema", "true").option("header", "true").csv(s"$baseDest/items").as[TPurchase].sort("id", "item", "amount").collect() should be(purchases)
      spark.read.option("inferSchema", "true").option("header", "true").csv(s"$baseDest/person").as[TPerson].collect() should be(persons)
    }

    it("writeCSV multiple with overwrite") {
      val spark = sparkSession
      import spark.implicits._
      val baseDest = testingBaseDir + "/dest"
      val dummyPath = new File(s"$baseDest/dummy")
      val itemsPath = new File(s"$baseDest/items")

      FileUtils.forceMkdir(dummyPath)
      dummyPath.exists() should be(true)
      itemsPath.exists() should be(false)

      val flow = SparkDataFlow.empty(sparkSession, tmpDir)
        .openCSV(basePath)("csv_1", "csv_2")
        .alias("csv_1", "items")
        .alias("csv_2", "person")
        .writeCSV(baseDest, Map("header" -> "true"), overwrite = true)("person", "items")

      val (executedActions, finalState) = executor.execute(flow)
      finalState.inputs.size should be(4)
      spark.read.option("inferSchema", "true").option("header", "true").csv(s"$baseDest/items").as[TPurchase].collect() should be(purchases)
      spark.read.option("inferSchema", "true").option("header", "true").csv(s"$baseDest/person").as[TPerson].collect() should be(persons)

      dummyPath.exists() should be(true)
      itemsPath.exists() should be(true)
    }

    it("writeParquet") {
      val spark = sparkSession
      import spark.implicits._
      val baseDest = testingBaseDir + "/dest"

      val flow = SparkDataFlow.empty(sparkSession, tmpDir)
        .openCSV(basePath)("csv_1", "csv_2")
        .alias("csv_1", "items")
        .alias("csv_2", "person")
        .writeParquet(baseDest)("person")
        .writePartitionedParquet(baseDest)("items", "amount")

      val (executedActions, finalState) = executor.execute(flow)
      finalState.inputs.size should be(4)
      new File(s"$baseDest/items").list().toSeq.filter(_.startsWith("amount=")).sorted should be(Seq("amount=1", "amount=2", "amount=4"))
      spark.read.parquet(s"$baseDest/items").as[TPurchase].sort("id", "item", "amount").collect() should be(purchases)
      spark.read.parquet(s"$baseDest/person").as[TPerson].collect() should be(persons)
    }

    it("writeParquet with multiple labels") {
      val spark = sparkSession
      import spark.implicits._
      val baseDest = testingBaseDir + "/dest"

      val flow = SparkDataFlow.empty(sparkSession, tmpDir)
        .openCSV(basePath)("csv_1", "csv_2")
        .alias("csv_1", "items")
        .alias("csv_2", "person")
        .writeParquet(baseDest)("person", "items")

      val (executedActions, finalState) = executor.execute(flow)
      finalState.inputs.size should be(4)
      spark.read.parquet(s"$baseDest/items").as[TPurchase].collect() should be(purchases)
      spark.read.parquet(s"$baseDest/person").as[TPerson].collect() should be(persons)
    }

    it("writeHiveManagedTable") {
      val spark = sparkSession
      import spark.implicits._

      val flow = SparkDataFlow.empty(sparkSession, tmpDir)
        .openCSV(basePath)("csv_1", "csv_2")
        .alias("csv_1", "items")
        .alias("csv_2", "person")
        .writeHiveManagedTable("default")("person", "items")

      executor.execute(flow)

      spark.sql("select * from default.items").as[TPurchase].collect() should be(purchases)
      spark.sql("select * from default.person").as[TPerson].collect() should be(persons)

      val e = intercept[DataFlowException] {
        executor.execute(flow)
      }
      e.getMessage should be(s"Exception performing action: ${flow.actions.drop(4).head.guid}: Action: write Inputs: [person] Outputs: []")
      // In spark 3.1 the trailing ';' seems to have been removed, however everything else seems to be the same
      // In order to make these tests work between spark versions we remove the ';' from the message
      e.cause.getMessage.replace(";", "") should be("Table `default`.`person` already exists.")

      val secondFlow = SparkDataFlow.empty(sparkSession, tmpDir)
        .openCSV(basePath)("csv_1", "csv_2")
        .transform("csv_1")("items")(_.filter(lit(false)))
        .transform("csv_2")("person")(_.filter(lit(false)))
        .writeHiveManagedTable("default", overwrite = true)("person", "items")

      executor.execute(secondFlow)
      spark.sql("select * from default.items").as[TPurchase].collect() should be(List.empty[TPurchase])
      spark.sql("select * from default.person").as[TPerson].collect() should be(List.empty[TPerson])
    }

    describe("tag and tagDependency") {

      it("missing tag") {
        val spark = sparkSession
        val baseDest = testingBaseDir + "/dest"

        val flow = Waimak.sparkFlow(sparkSession, tmpDir.toString)
          .tagDependency("write_person", "write_items") {
            _.openParquet(baseDest)("person_written", "items_written")
          }
          .openCSV(basePath)("csv_1", "csv_2")
          .alias("csv_1", "items")
          .alias("csv_2", "person")
          .writeParquet(baseDest)("person", "items")

        val res = intercept[DataFlowException] {
          executor.execute(flow)
        }
        res.text should be(s"Could not find any actions tagged with label [write_person] when resolving dependent actions for action [${flow.actions.head.guid}]")

      }

      it("no tag dependencies, should be missing file") {
        // This will produce a file missing exception if no tag dependencies introduced
        val spark = sparkSession
        val baseDest = testingBaseDir + "/dest"

        val flow = Waimak.sparkFlow(sparkSession, tmpDir.toString)
          .openFileParquet(s"$baseDest/person", "person_written")
          .openFileParquet(s"$baseDest/items", "items_written")
          .openCSV(basePath)("csv_1", "csv_2")
          .alias("csv_1", "items")
          .alias("csv_2", "person")
          .writeParquet(baseDest)("person", "items")

        val res = intercept[DataFlowException] {
          executor.execute(flow)
        }
        res.cause shouldBe a[AnalysisException]
        res.cause.asInstanceOf[AnalysisException].message should be(s"Path does not exist: file:$baseDest/person")
      }

      it("tag dependency between write and open") {
        // This will fix the missing file error by providing a dependency using tags
        val spark = sparkSession
        import spark.implicits._
        val baseDest = testingBaseDir + "/dest"

        val flow = Waimak.sparkFlow(sparkSession, tmpDir.toString)
          .tagDependency("written") {
            _.openFileParquet(s"$baseDest/person", "person_written")
              .openFileParquet(s"$baseDest/items", "items_written")
          }
          .openCSV(basePath)("csv_1", "csv_2")
          .alias("csv_1", "items")
          .alias("csv_2", "person")
          .tag("written") {
            _.writeParquet(baseDest)("person", "items")
          }

        val (_, finalState) = executor.execute(flow)
        finalState.inputs.size should be(6)

        finalState.inputs.get[Dataset[_]]("items_written").as[TPurchase].collect() should be(purchases)
        finalState.inputs.get[Dataset[_]]("person_written").as[TPerson].collect() should be(persons)

      }

      it("cyclic dependency tags") {

        val spark = sparkSession
        val baseDest = testingBaseDir + "/dest"

        val flow = Waimak.sparkFlow(sparkSession, tmpDir.toString)
          .tagDependency("written") {
            _.tag("read") {
              _.openParquet(baseDest)("person_written")
            }
          }
          .openCSV(basePath)("csv_2")
          .alias("csv_2", "person")
          .tagDependency("read") {
            _.tag("written") {
              _.writeParquet(baseDest)("person")
            }
          }

        val res = intercept[DataFlowException] {
          executor.execute(flow)
        }
        res.text should be(s"Circular reference for action [${flow.actions.find(_.inputLabels.contains("person")).get.guid}] as a result of cyclic tag dependency. " +
          "Action has the following tag dependencies [read] and depends on the following input labels [person]")
      }

      it("tag dependency conflicting with input dependency") {
        val spark = sparkSession
        val baseDest = testingBaseDir + "/dest"

        val flow = Waimak.sparkFlow(sparkSession, tmpDir.toString)
          .tagDependency("written") {
            _.openFileParquet(s"$baseDest/person", "person_written")
              .openFileParquet(s"$baseDest/items", "items_written")
          }
          .openCSV(basePath)("csv_1", "csv_2")
          .tagDependency("written") {
            _.alias("csv_1", "items")
          }
          .alias("csv_2", "person")
          .tag("written") {
            _.writeParquet(baseDest)("person", "items")
          }

        val res = intercept[DataFlowException] {
          executor.execute(flow)
        }
        res.text should be(s"Circular reference for action [${flow.actions.find(_.inputLabels.contains("csv_1")).get.guid}] as a result of cyclic tag dependency. " +
          "Action has the following tag dependencies [written] and depends on the following input labels [csv_1]")

      }

      it("tag dependent action depends on an action that does not run and therefore does not run and errorOnUnexecutedActions is true") {
        val spark = sparkSession
        val baseDest = testingBaseDir + "/dest"

        val flow = Waimak.sparkFlow(sparkSession, tmpDir.toString)
          .addInput("test_1", None)
          .tag("tag1") {
            _.transform("test_1")("test_2")(df => df)
          }
          .tagDependency("tag1") {
            _.openCSV(basePath)("csv_1")
          }

        val res = intercept[DataFlowException] {
          executor.execute(flow)
        }
        res.text should be(
          "There were actions in the flow that did not run. " +
            "If this was intentional you can allow unexecuted actions by " +
            "setting the flag [errorOnUnexecutedActions=false] when calling " +
            "the execute method.\nThe actions that did not run were:\n" +
            s"${flow.actions.find(_.outputLabels.contains("test_2")).get.logLabel}\n" +
            s"${flow.actions.find(_.outputLabels.contains("csv_1")).get.logLabel}"
        )

      }

      it("tag dependent action depends on an action that does not run and therefore does not run and errorOnUnexecutedActions is false") {
        val spark = sparkSession
        val baseDest = testingBaseDir + "/dest"

        val flow = Waimak.sparkFlow(sparkSession, tmpDir.toString)
          .addInput("test_1", None)
          .tag("tag1") {
            _.transform("test_1")("test_2")(df => df)
          }
          .tagDependency("tag1") {
            _.openCSV(basePath)("csv_1")
          }

        val (executed, _) = executor.execute(flow, errorOnUnexecutedActions = false)
        executed.size should be(0)

      }

      it("interceptor on tagged action, should replace action in tag state") {
        val spark = sparkSession
        val baseDest = testingBaseDir + "/dest"

        val flowNoCache = Waimak.sparkFlow(sparkSession, tmpDir.toString)
          .tag("tag_1") {
            _.openCSV(basePath)("csv_1", "csv_2")
              .alias("csv_1", "items")
              .alias("csv_2", "person")
          }.tagDependency("tag_1") {
          _.transform("items")("one_item") {
            _.filter("id = 1")
          }
        }.show("one_item")

        val flowWithCacheNoReuse = flowNoCache.cacheAsParquet("items")

        flowNoCache.actions.size should be(flowWithCacheNoReuse.actions.size)
        flowNoCache.tagState.taggedActions.size should be(flowWithCacheNoReuse.tagState.taggedActions.size)

        val noCacheActionGUIDs = flowNoCache.actions.map(_.guid).toSet
        flowWithCacheNoReuse.prepareForExecution().get.actions.forall(a => noCacheActionGUIDs.contains(a.guid)) should be(true)

        val flowWithCache = flowWithCacheNoReuse.show("items").prepareForExecution().get
        flowWithCache.actions.forall(a => noCacheActionGUIDs.contains(a.guid)) should be(false)
        val cacheAction = flowWithCache.actions.filter(a => !noCacheActionGUIDs.contains(a.guid)).head
        cacheAction.logLabel.contains("Action: PostActionInterceptor Inputs") should be(true)

        flowNoCache.tagState.taggedActions.get(cacheAction.guid).map(_.tags) should be(None)
        flowWithCache.tagState.taggedActions.get(cacheAction.guid).map(_.tags) should be(Some(Set("tag_1")))
      }

      it("tagged interceptor on tagged action") {
        val spark = sparkSession
        val baseDest = testingBaseDir + "/dest"

        spark.conf.set(CACHE_ONLY_REUSED_LABELS, false)

        val flowNoCache = Waimak.sparkFlow(sparkSession, tmpDir.toString)
          .tag("tag_1") {
            _.openCSV(basePath)("csv_1", "csv_2")
              .alias("csv_1", "items")
              .alias("csv_2", "person")
          }.tagDependency("tag_1") {
          _.transform("items")("one_item") {
            _.filter("id = 1")
          }
        }.show("one_item")

        val flowWithCache = flowNoCache
          .tag("cache_tag_1") {
            _.cacheAsParquet("items")
          }
          .prepareForExecution()
          .get

        flowWithCache.actions.foreach(a => println("DEBUG a " + a.logLabel))

        flowNoCache.actions.size should be(flowWithCache.actions.size)
        flowNoCache.tagState.taggedActions.size should be(flowWithCache.tagState.taggedActions.size)

        val noCacheActionGUIDs = flowNoCache.actions.map(_.guid).toSet
        val cacheAction = flowWithCache.actions.filter(a => !noCacheActionGUIDs.contains(a.guid)).head
        cacheAction.logLabel.contains("Action: PostActionInterceptor Inputs") should be(true)

        flowNoCache.tagState.taggedActions.get(cacheAction.guid).map(_.tags) should be(None)
        flowWithCache.tagState.taggedActions.get(cacheAction.guid).map(_.tags) should be(Some(Set("tag_1")))
      }

      it("tagged interceptor on non tagged action") {
        val spark = sparkSession
        val baseDest = testingBaseDir + "/dest"
        spark.conf.set(CACHE_ONLY_REUSED_LABELS, false)

        val flowNoCache = Waimak.sparkFlow(sparkSession, tmpDir.toString)
          .openCSV(basePath)("csv_1", "csv_2")
          .alias("csv_1", "items")
          .alias("csv_2", "person")
          .transform("items")("one_item") {
            _.filter("id = 1")
          }
          .show("one_item")

        val flowWithCache = flowNoCache
          .tag("cache_tag_1") {
            _.cacheAsParquet("items")
          }
          .prepareForExecution()
          .get

        flowNoCache.actions.size should be(flowWithCache.actions.size)
        flowNoCache.tagState.taggedActions.size should be(flowWithCache.tagState.taggedActions.size)

        val noCacheActionGUIDs = flowNoCache.actions.map(_.guid).toSet
        val cacheAction = flowWithCache.actions.filter(a => !noCacheActionGUIDs.contains(a.guid)).head
        cacheAction.logLabel.contains("Action: PostActionInterceptor Inputs") should be(true)

        flowNoCache.tagState.taggedActions.get(cacheAction.guid).map(_.tags) should be(None)
        flowWithCache.tagState.taggedActions.get(cacheAction.guid).map(_.tags) should be(Some(Set()))
      }
    }

  }


  describe("debugAsTable") {

    it("sql") {
      val spark = sparkSession
      import spark.implicits._
      val flow = Waimak.sparkFlow(spark)
        .openCSV(basePath)("csv_1", "csv_2")
        .debugAsTable("csv_1", "csv_2")

      executor.execute(flow)

      val csv_1_sql = spark.sql("select * from csv_1")
      csv_1_sql.as[TPurchase].collect() should be(purchases)

      val csv_2_sql = spark.sql("select * from csv_2")
      csv_2_sql.as[TPerson].collect() should be(persons)
    }

    it("invalid label name") {
      val spark = sparkSession
      val res = intercept[DataFlowException] {
        Waimak.sparkFlow(spark)
          .openCSV(basePath)("csv_1")
          .alias("csv_1", "bad-name")
          .debugAsTable("bad-name")
      }
      res.text should be("The following labels contain invalid characters to be used as Spark SQL view names: [bad-name]. " +
        "You can alias the label to a valid name before calling the debugAsTable action.")
    }

  }

  describe("map/mapOption") {
    it("map should transform a sparkdataflow when using implicit classes") {

      val emptyFlow: SparkDataFlow = SparkDataFlow.empty(sparkSession, tmpDir)
      implicit class TestSparkImplicit1(dataFlow: SparkDataFlow) {
        def runTest1: SparkDataFlow = dataFlow.addAction(new TestEmptySparkAction(List.empty, List.empty) {
          override val guid: String = "abd22c36-4dd0-4fa5-9298-c494ede7f363"
        })
      }

      implicit class TestSparkImplicit2(dataFlow: SparkDataFlow) {
        def runTest2: SparkDataFlow = dataFlow.addAction(new TestEmptySparkAction(List.empty, List.empty) {
          override val guid: String = "f40ee6fa-157b-4d65-ad7a-17639da403bf"
        })
      }

      emptyFlow.map(f => if (true) f.runTest1 else f).runTest2.actions.map(_.guid) should be(Seq("abd22c36-4dd0-4fa5-9298-c494ede7f363", "f40ee6fa-157b-4d65-ad7a-17639da403bf"))

    }
  }

  describe("SequentialDataFlowExecutor") {
    it("any files in staging dir should be cleaned up before any actions are executed") {
      sparkSession.conf.set(SparkDataFlow.REMOVE_TEMP_AFTER_EXECUTION, false)

      val testingDir = new File(tmpDir.toUri.getPath + "/test1")
      FileUtils.forceMkdir(testingDir)
      testingDir.getParentFile.list().toSeq should be(Seq("test1"))

      val emptyFlow: SparkDataFlow = SparkDataFlow.empty(sparkSession, tmpDir)
      executor.execute(emptyFlow)
      testingDir.getParentFile.list().toSeq should be(Seq())

    }

    it("any empty staging folder should be created when a flow is executed") {
      sparkSession.conf.set(SparkDataFlow.REMOVE_TEMP_AFTER_EXECUTION, false)

      val tmpDirFile = new File(tmpDir.toUri.getPath)
      tmpDirFile.getParentFile.list().toSeq should be(Seq())

      val emptyFlow: SparkDataFlow = SparkDataFlow.empty(sparkSession, tmpDir)
      executor.execute(emptyFlow)
      tmpDirFile.list().toSeq should be(Seq())

    }
  }

  describe("prepareForExecution") {
    it("Should create a filesystem object different to the one specified in the defaultFS and create a temp folder on the overridden fs") {

      // Spark session with defaultFS set to something other than file
      val spark = SparkSession
        .builder()
        .appName(appName)
        .master(master)
        .config("spark.executor.memory", "2g")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
      spark.sparkContext.hadoopConfiguration.set("fs.defaultFS", "hdfs://localhost/")

      // Set the waimak defaultFS for the Spark Flow Context
      spark.conf.set("spark.waimak.fs.defaultFS", "file:///")
      val emptyFlow = Waimak.sparkFlow(spark, new Path("file://" + testingBaseDir.toAbsolutePath.toString + "/tmp").toString)
      emptyFlow.prepareForExecution()

      // Test the flow filesystem differs from what the default one would be
      emptyFlow.flowContext.fileSystem.getUri.toString should be("file:///")
      emptyFlow.flowContext.uriUsed.toString should be("file:///")
      FileSystem.get(spark.sparkContext.hadoopConfiguration).getUri.toString should be("hdfs://localhost")
    }
  }

  describe("finaliseExecution") {

    it("should delete a temporary folder after execution by default") {
      val emptyFlow = Waimak.sparkFlow(sparkSession, tmpDir.toString)

      Waimak.sparkExecutor().execute(emptyFlow)
      emptyFlow.flowContext.fileSystem.exists(tmpDir) should be(false)

    }

    it("should not delete a temporary folder after execution if configuration is set") {

      sparkSession.conf.set("spark.waimak.dataflow.removeTempAfterExecution", false)
      val emptyFlow = Waimak.sparkFlow(sparkSession, tmpDir.toString)

      Waimak.sparkExecutor().execute(emptyFlow)
      emptyFlow.flowContext.fileSystem.exists(tmpDir) should be(true)

    }

    it("should not delete a temporary folder after execution the flow fails") {

      val badFlow = Waimak.sparkFlow(sparkSession, tmpDir.toString).open("bad", _ => throw new RuntimeException("bad action"))

      badFlow.flowContext.getBoolean("spark.waimak.dataflow.removeTempAfterExecution", SparkDataFlow.REMOVE_TEMP_AFTER_EXECUTION_DEFAULT) should be(true)

      intercept[RuntimeException] {
        Waimak.sparkExecutor().execute(badFlow)
      }.getCause.getMessage should be("bad action")

      badFlow.flowContext.fileSystem.exists(tmpDir) should be(true)

    }

  }

  describe("mixing multiple types in flow") {

    it("should handle multiple types in the flow") {

      val emptyFlow = SparkDataFlow.empty(sparkSession, tmpDir)
      sparkSession.conf.set(CACHE_ONLY_REUSED_LABELS, false)

      val flow = emptyFlow.addInput("integer_1", Some(1))
        .addInput("dataset_1", Some(sparkSession.emptyDataFrame))
        .addAction(new TestOutputMultipleTypesAction(List("integer_1", "dataset_1"), List("integer_2", "dataset_2"),
          (i, ds) => (i + 1, ds)))

      val res = executor.execute(flow)
      res._2.inputs.get[Int]("integer_2") should be(2)
      res._2.inputs.get[Dataset[_]]("dataset_2") should be(sparkSession.emptyDataFrame)

      val ex1 = intercept[DataFlowException] {
        executor.execute(flow.inPlaceTransform("integer_2")(identity))
      }
      ex1.cause.getMessage should be("Can only call inPlaceTransform on a Dataset. Label integer_2 is a java.lang.Integer")
      val ex2 = intercept[DataFlowException] {
        executor.execute(flow.cacheAsParquet("integer_2"))
      }
      ex2.cause.getMessage should be("Can only call cacheAsParquet on a Dataset. Label integer_2 is a java.lang.Integer")
    }
  }

  describe("fully parallel") {

    it("smoke test") {
      val parallelExecutor = Waimak.sparkExecutor(10, DFExecutorPriorityStrategies.raceToOutputs)
      val spark = sparkSession

      val baseDest = testingBaseDir + "/dest"
      val flow = Waimak.sparkFlow(spark)
        .openCSV(basePath)("csv_1", "csv_2")
        .sql("csv_1")("person_summary", "select id, count(item) as item_cnt, sum(amount) as total from csv_1 group by id")
        .transform("csv_2", "person_summary")("report") { (l, r) => l.join(r, l("id") === r("id"), "left").drop(r("id")) }
        .alias("csv_1", "person")
        .alias("csv_2", "purchase")
        .show("report")
        .show("csv_1")
        .show("csv_2")
        .show("person_summary")
        .writeParquet(baseDest, true)("person", "purchase", "report", "person_summary")

      val (executedActions, finalState) = parallelExecutor.execute(flow)

      executedActions.size should be(14) //all actions
    }
  }
}

class TestEmptySparkAction(val inputLabels: List[String], val outputLabels: List[String]) extends SparkDataFlowAction {

  override def performAction(inputs: DataFlowEntities, flowContext: SparkFlowContext): Try[ActionResult] = Try(List.empty)

}

class TestTwoInputsAndOutputsAction(override val inputLabels: List[String], override val outputLabels: List[String], run: (Dataset[_], Dataset[_]) => (Dataset[_], Dataset[_])) extends SparkDataFlowAction {

  override def performAction(inputs: DataFlowEntities, flowContext: SparkFlowContext): Try[ActionResult] = Try {
    if (inputLabels.length != 2 && outputLabels.length != 2) throw new IllegalArgumentException("Number of input label and output labels must be 2")
    val res: (Dataset[_], Dataset[_]) = run(inputs.get[Dataset[_]](inputLabels(0)), inputs.get[Dataset[_]](inputLabels(1)))
    Seq(Some(res._1), Some(res._2))
  }
}


class TestOutputMultipleTypesAction(override val inputLabels: List[String]
                                    , override val outputLabels: List[String]
                                    , run: (Int, Dataset[_]) => (Int, Dataset[_])) extends SparkDataFlowAction {

  override def performAction(inputs: DataFlowEntities, flowContext: SparkFlowContext): Try[ActionResult] = Try {
    val res: (Int, Dataset[_]) = run(inputs.get[Int](inputLabels(0)), inputs.get[Dataset[_]](inputLabels(1)))
    Seq(Some(res._1), Some(res._2))
  }
}
