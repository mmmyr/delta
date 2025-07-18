/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

import java.sql.Timestamp

import scala.collection.JavaConverters._

import com.databricks.spark.util.Log4jUsageLogger
import org.apache.spark.sql.delta.DeltaOperations.ManualUpdate
import org.apache.spark.sql.delta.DeltaTestUtils.createTestAddFile
import org.apache.spark.sql.delta.actions.{Action, CommitInfo}
import org.apache.spark.sql.delta.coordinatedcommits.{CatalogOwnedCommitCoordinatorProvider, CatalogOwnedTableUtils, CatalogOwnedTestBaseSuite, CommitCoordinatorProvider, CommitCoordinatorUtilBase, TrackingInMemoryCommitCoordinatorBuilder}
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.test.{DeltaSQLCommandTest, DeltaSQLTestUtils}
import org.apache.spark.sql.delta.test.DeltaTestImplicits._
import org.apache.spark.sql.delta.util.{DeltaCommitFileProvider, FileNames, JsonUtils}
import org.apache.hadoop.fs.Path

import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.streaming.StreamTest
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.util.ManualClock

class InCommitTimestampSuite
  extends QueryTest
    with SharedSparkSession
    with DeltaSQLCommandTest
    with DeltaSQLTestUtils
    with DeltaTestUtilsBase
    with CatalogOwnedTestBaseSuite
    with StreamTest {
  import InCommitTimestampTestUtils._

  override def beforeAll(): Unit = {
    super.beforeAll()
    spark.conf.set(DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.defaultTablePropertyKey, "true")
    spark.conf.set(DeltaSQLConf.DELTA_WRITE_CHECKSUM_ENABLED.key, "true")
    spark.conf.set(DeltaSQLConf.INCREMENTAL_COMMIT_ENABLED.key, "true")
  }

  /**
   * Create a delta table with 10 rows and a single commit with an AddFile.
   * This is used to create the initial state of the delta table for testing.
   *
   * @param tableName The name of the delta table.
   * @return The DeltaLog for the created table.
   */
  private def createInitialTable(tableName: String): DeltaLog = {
    spark.range(10).write.format("delta").saveAsTable(tableName)
    val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
    deltaLog.startTransaction().commit(Seq(createTestAddFile("1")), ManualUpdate)
    deltaLog
  }

  /**
   * Construct a delta log w/ a specific manual clock.
   *
   * @param tableName The name of the Delta table.
   * @param clock The manual clock to use for the DeltaLog.
   */
  private def getDeltaLogWithClock(tableName: String, clock: ManualClock): DeltaLog = {
    // Construct a delta log by name first.
    val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
    // Get the data path.
    val dataPath = deltaLog.dataPath
    // Clear the cache to ensure that a fresh DeltaLog is created with the provided clock.
    DeltaLog.clearCache()
    // Create a new DeltaLog with the clock and the log path.
    DeltaLog.forTable(spark, dataPath, clock)
  }

  test("Enable ICT on commit 0") {
    withTempTable(createTable = false) { tableName =>
      spark.range(10).write.format("delta").saveAsTable(tableName)
      val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
      val ver0Snapshot = deltaLog.snapshot
      assert(DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.fromMetaData(ver0Snapshot.metadata))
      assert(ver0Snapshot.timestamp == getInCommitTimestamp(deltaLog, 0))
    }
  }

  // Catalog Owned will also automatically enable ICT.
  testWithDefaultCommitCoordinatorUnset(
    "Create a non-inCommitTimestamp table and then enable timestamp") {
    withSQLConf(
      DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.defaultTablePropertyKey -> false.toString
    ) {
      withTempTable(createTable = false) { tableName =>
        spark.range(10).write.format("delta").saveAsTable(tableName)
        spark.sql(s"INSERT INTO $tableName VALUES 10")
        val ver1Snapshot = DeltaLog.forTable(spark, TableIdentifier(tableName)).snapshot
        // File timestamp should be the same as snapshot.getTimestamp when inCommitTimestamp is not
        // enabled
        assert(
          ver1Snapshot.logSegment.lastCommitFileModificationTimestamp == ver1Snapshot.timestamp)

        spark.sql(s"ALTER TABLE $tableName " +
          s"SET TBLPROPERTIES ('${DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.key}' = 'true')")

        val ver2Snapshot = DeltaLog.forTable(spark, TableIdentifier(tableName)).snapshot
        // File timestamp should be different from snapshot.getTimestamp when inCommitTimestamp is
        // enabled
        assert(ver2Snapshot.timestamp == getInCommitTimestamp(ver2Snapshot.deltaLog, version = 2))

        assert(ver2Snapshot.timestamp > ver1Snapshot.timestamp)

        spark.sql(s"INSERT INTO $tableName VALUES 11")

        val ver3Snapshot = DeltaLog.forTable(spark, TableIdentifier(tableName)).snapshot

        assert(ver3Snapshot.timestamp > ver2Snapshot.timestamp)
      }
    }
  }

  test("InCommitTimestamps are monotonic even when the clock is skewed") {
    withTempTable(createTable = false) { tableName =>
      spark.range(10).write.format("delta").saveAsTable(tableName)
      val startTime = System.currentTimeMillis()
      val clock = new ManualClock(startTime)
      val deltaLog = getDeltaLogWithClock(tableName, clock)
      // Move backwards in time.
      deltaLog.startTransaction().commit(Seq(createTestAddFile("1")), ManualUpdate)
      val ver1Timestamp = deltaLog.snapshot.timestamp
      clock.setTime(startTime - 10000)
      deltaLog.startTransaction().commit(Seq(createTestAddFile("2")), ManualUpdate)
      val ver2Timestamp = deltaLog.snapshot.timestamp
      assert(ver2Timestamp > ver1Timestamp)
    }
  }

  test("Conflict resolution of timestamps") {
    withTempTable(createTable = false) { tableName =>
      spark.range(10).write.format("delta").saveAsTable(tableName)
      val startTime = System.currentTimeMillis()
      val clock = new ManualClock(startTime)
      // Clear the cache to ensure that a new DeltaLog is created with the new clock.
      val deltaLog = getDeltaLogWithClock(tableName, clock)
      val txn1 = deltaLog.startTransaction()
      clock.setTime(startTime)
      deltaLog.startTransaction().commit(Seq(createTestAddFile("1")), ManualUpdate)
      // Move time backwards for the conflicting commit.
      clock.setTime(startTime - 10000)
      val usageRecords = Log4jUsageLogger.track {
        txn1.commit(Seq(createTestAddFile("2")), ManualUpdate)
      }
      // Make sure that this transaction resulted in a conflict.
      assert(filterUsageRecords(usageRecords, "delta.commit.retry").length == 1)
      assert(getInCommitTimestamp(deltaLog, 2) > getInCommitTimestamp(deltaLog, 1))
    }
  }

  for (useCommitLarge <- BOOLEAN_DOMAIN)
  test("txn.commit should use clock.currentTimeMillis() for ICT" +
    s" [useCommitLarge: $useCommitLarge]") {
    withTempTable(createTable = false) { tableName =>
      spark.range(2).write.format("delta").saveAsTable(tableName)
      val expectedCommit1Time = System.currentTimeMillis()
      val clock = new ManualClock(expectedCommit1Time)
      val deltaLog = getDeltaLogWithClock(tableName, clock)
      val ver0Snapshot = deltaLog.snapshot
      assert(DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.fromMetaData(ver0Snapshot.metadata))
      val usageRecords = Log4jUsageLogger.track {
        if (useCommitLarge) {
          deltaLog.startTransaction().commitLarge(
            spark,
            Seq(createTestAddFile("1")).toIterator,
            newProtocolOpt = None,
            DeltaOperations.ManualUpdate,
            context = Map.empty,
            metrics = Map.empty)
        } else {
          deltaLog.startTransaction().commit(
            Seq(createTestAddFile("1")),
            DeltaOperations.ManualUpdate,
            tags = Map.empty
          )
        }
      }
      val ver1Snapshot = deltaLog.snapshot
      val retrievedTimestamp = getInCommitTimestamp(deltaLog, version = 1)
      assert(ver1Snapshot.timestamp == retrievedTimestamp)
      assert(ver1Snapshot.timestamp == expectedCommit1Time)
      val expectedOpType = if (useCommitLarge) "delta.commit.large" else "delta.commit"
      assert(filterUsageRecords(usageRecords, expectedOpType).length == 1)
    }
  }

  test("Missing CommitInfo should result in a DELTA_MISSING_COMMIT_INFO exception") {
    // Make sure that we don't retrieve the time from the CRC.
    withSQLConf(DeltaSQLConf.DELTA_WRITE_CHECKSUM_ENABLED.key -> "false") {
      withTempTable(createTable = false) { tableName =>
        val deltaLog = createInitialTable(tableName)
        // Remove CommitInfo from the commit.
        val commit1Path = DeltaCommitFileProvider(deltaLog.unsafeVolatileSnapshot).deltaFile(1)
        val actions = deltaLog.store.readAsIterator(commit1Path, deltaLog.newDeltaHadoopConf())
        val actionsWithoutCommitInfo =
          actions.filterNot(Action.fromJson(_).isInstanceOf[CommitInfo])
        deltaLog.store.write(
          commit1Path,
          actionsWithoutCommitInfo,
          overwrite = true,
          deltaLog.newDeltaHadoopConf())

        DeltaLog.clearCache()
        val latestSnapshot = DeltaLog.forTable(spark, TableIdentifier(tableName)).snapshot
        val e = intercept[DeltaIllegalStateException] {
          latestSnapshot.timestamp
        }
        checkError(
          e,
          "DELTA_MISSING_COMMIT_INFO",
          parameters = Map(
            "featureName" -> InCommitTimestampTableFeature.name,
            "version" -> "1"))
      }
    }
  }

  test("Missing CommitInfo.commitTimestamp should result in a " +
    "DELTA_MISSING_COMMIT_TIMESTAMP exception") {
    // Make sure that we don't retrieve the time from the CRC.
    withSQLConf(DeltaSQLConf.DELTA_WRITE_CHECKSUM_ENABLED.key -> "false") {
      withTempTable(createTable = false) { tableName =>
        val deltaLog = createInitialTable(tableName)
        // Remove CommitInfo.commitTimestamp from the commit.
        val commit1Path = DeltaCommitFileProvider(deltaLog.unsafeVolatileSnapshot).deltaFile(1)
        val actions = deltaLog.store.readAsIterator(
          commit1Path,
          deltaLog.newDeltaHadoopConf()).toList
        val actionsWithoutCommitInfoCommitTimestamp =
          actions.map(Action.fromJson).map {
            case ci: CommitInfo =>
              ci.copy(inCommitTimestamp = None).json
            case other =>
              other.json
          }.toIterator
        deltaLog.store.write(
          commit1Path,
          actionsWithoutCommitInfoCommitTimestamp,
          overwrite = true,
          deltaLog.newDeltaHadoopConf())

        DeltaLog.clearCache()
        val latestSnapshot = DeltaLog.forTable(spark, TableIdentifier(tableName)).snapshot
        val e = intercept[DeltaIllegalStateException] {
          latestSnapshot.timestamp
        }
        checkError(
          e,
          "DELTA_MISSING_COMMIT_TIMESTAMP",
          parameters = Map("featureName" -> InCommitTimestampTableFeature.name, "version" -> "1"))
      }
    }
  }

  test("InCommitTimestamp is equal to snapshot.timestamp") {
    withTempTable(createTable = false) { tableName =>
      spark.range(10).write.format("delta").saveAsTable(tableName)
      val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
      val ver0Snapshot = deltaLog.snapshot

      assert(ver0Snapshot.timestamp == getInCommitTimestamp(deltaLog, 0))
    }
  }

  test("CREATE OR REPLACE should not disable ICT") {
    withoutDefaultCCTableFeature {
      withSQLConf(
        DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.defaultTablePropertyKey -> false.toString
      ) {
        withTempDir { tempDir =>
          spark.range(10).write.format("delta").save(tempDir.getAbsolutePath)
          spark.sql(
            s"ALTER TABLE delta.`${tempDir.getAbsolutePath}` " +
              s"SET TBLPROPERTIES ('${DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.key}' = 'true')")

          spark.sql(
            s"CREATE OR REPLACE TABLE delta.`${tempDir.getAbsolutePath}` (id long) USING delta")

          val deltaLogAfterCreateOrReplace =
            DeltaLog.forTable(spark, new Path(tempDir.getCanonicalPath))
          val snapshot = deltaLogAfterCreateOrReplace.snapshot
          assert(DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.fromMetaData(snapshot.metadata))
          assert(snapshot.timestamp ==
            getInCommitTimestamp(deltaLogAfterCreateOrReplace, snapshot.version))
        }
      }
    }
  }

  test("Enablement tracking properties should not be added if ICT is enabled on commit 0") {
    withTempTable(createTable = false) { tableName =>
      spark.range(10).write.format("delta").saveAsTable(tableName)
      val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
      val ver0Snapshot = deltaLog.snapshot

      val observedEnablementTimestamp =
        DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_TIMESTAMP.fromMetaData(ver0Snapshot.metadata)
      val observedEnablementVersion =
        DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_VERSION.fromMetaData(ver0Snapshot.metadata)
      assert(observedEnablementTimestamp.isEmpty)
      assert(observedEnablementVersion.isEmpty)
    }
  }

  // Catalog Owned will also automatically enable ICT.
  testWithDefaultCommitCoordinatorUnset(
    "Enablement tracking works when ICT is enabled post commit 0") {
    withSQLConf(
      DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.defaultTablePropertyKey -> false.toString
    ) {
      withTempTable(createTable = false) { tableName =>
        spark.range(10).write.format("delta").saveAsTable(tableName)
        spark.sql(s"INSERT INTO $tableName VALUES 10")

        spark.sql(
          s"ALTER TABLE $tableName " +
            s"SET TBLPROPERTIES ('${DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.key}' = 'true')")

        val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
        val ver2Snapshot = deltaLog.snapshot
        val observedEnablementTimestamp =
          DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_TIMESTAMP.fromMetaData(ver2Snapshot.metadata)
        val observedEnablementVersion =
          DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_VERSION.fromMetaData(ver2Snapshot.metadata)
        assert(observedEnablementTimestamp.isDefined)
        assert(observedEnablementVersion.isDefined)
        assert(observedEnablementTimestamp.get == getInCommitTimestamp(deltaLog, version = 2))
        assert(observedEnablementVersion.get == 2)
      }
    }
  }

  // Catalog Owned will also automatically enable ICT.
  testWithDefaultCommitCoordinatorUnset("Conflict resolution of enablement version") {
    withSQLConf(
      DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.defaultTablePropertyKey -> false.toString
    ) {
      withTempTable(createTable = false) { tableName =>
        spark.range(10).write.format("delta").saveAsTable(tableName)
        spark.sql(s"INSERT INTO $tableName VALUES 10")
        val startTime = System.currentTimeMillis()
        val clock = new ManualClock(startTime)
        val deltaLog = getDeltaLogWithClock(tableName, clock)
        val snapshot = deltaLog.snapshot
        val txn1 = deltaLog.startTransaction()
        clock.setTime(startTime)
        deltaLog.startTransaction().commit(Seq(createTestAddFile("1")), ManualUpdate)
        val ictEnablementMetadataConfig = snapshot.metadata.configuration ++ Map(
          DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.key -> "true")
        val ictEnablementMetadata =
          snapshot.metadata.copy(configuration = ictEnablementMetadataConfig)
        val usageRecords = Log4jUsageLogger.track {
          txn1.commit(Seq(ictEnablementMetadata), ManualUpdate)
        }
        val ver3Snapshot = deltaLog.update()
        val observedEnablementTimestamp =
          DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_TIMESTAMP.fromMetaData(ver3Snapshot.metadata)
        val observedEnablementVersion =
          DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_VERSION.fromMetaData(ver3Snapshot.metadata)
        // Make sure that this transaction resulted in a conflict.
        assert(filterUsageRecords(usageRecords, "delta.commit.retry").length == 1)
        assert(observedEnablementTimestamp.get == getInCommitTimestamp(deltaLog, version = 3))
        assert(observedEnablementVersion.get == 3)
      }
    }
  }

  // Catalog Owned will also automatically enable ICT.
  testWithDefaultCommitCoordinatorUnset(
    "commitLarge should correctly set the enablement tracking properties") {
    withTempTable(createTable = false) { tableName =>
      spark.range(2).write.format("delta").saveAsTable(tableName)
      val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
      val ver0Snapshot = deltaLog.snapshot
      assert(DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.fromMetaData(ver0Snapshot.metadata))
      // Disable ICT in version 1.
      spark.sql(
        s"ALTER TABLE $tableName " +
          s"SET TBLPROPERTIES ('${DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.key}' = 'false')")
      assert(!DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.fromMetaData(deltaLog.update().metadata))

      // Use a restore command to return the table state to version 0.
      // This should internally invoke commitLarge and the enablement tracking properties should be
      // updated correctly.
      val usageRecords = Log4jUsageLogger.track {
        spark.sql(s"RESTORE TABLE $tableName TO VERSION AS OF 0")
      }
      val ver2Snapshot = deltaLog.update()
      assert(DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.fromMetaData(ver2Snapshot.metadata))
      val observedEnablementTimestamp =
        DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_TIMESTAMP.fromMetaData(ver2Snapshot.metadata)
      val observedEnablementVersion =
        DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_VERSION.fromMetaData(ver2Snapshot.metadata)
      assert(filterUsageRecords(usageRecords, "delta.commit.large").length == 1)
      assert(observedEnablementTimestamp.isDefined)
      assert(observedEnablementVersion.isDefined)
      assert(observedEnablementTimestamp.get == getInCommitTimestamp(deltaLog, version = 2))
      assert(observedEnablementVersion.get == 2)
    }
  }

  test("snapshot.timestamp should be read from the CRC") {
    withTempTable(createTable = false) { tableName =>
      var deltaLog: DeltaLog = null
      var timestamp = -1L
      val usageRecords = Log4jUsageLogger.track {
        spark.range(1).write.format("delta").saveAsTable(tableName)
        DeltaLog.clearCache() // Clear the post-commit snapshot from the cache.
        deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
        val fs = deltaLog.logPath.getFileSystem(deltaLog.newDeltaHadoopConf())
        assert(fs.exists(FileNames.checksumFile(deltaLog.logPath, 0)))
        timestamp = deltaLog.snapshot.timestamp
      }
      assert(timestamp == getInCommitTimestamp(deltaLog, 0))
      // No explicit read.
      assert(filterUsageRecords(usageRecords, "delta.inCommitTimestamp.read").isEmpty)
    }
  }

  test("postCommitSnapshot.timestamp should be populated by protocolMetadataAndICTReconstruction " +
     "when the table has no checkpoints") {
    // Make sure that we don't retrieve the time from the CRC.
    withSQLConf(DeltaSQLConf.DELTA_WRITE_CHECKSUM_ENABLED.key -> "false") {
      withTempTable(createTable = false) { tableName =>
        var deltaLog: DeltaLog = null
        var timestamp = -1L
        spark.range(1).write.format("delta").saveAsTable(tableName)
        DeltaLog.clearCache()
        val usageRecords = Log4jUsageLogger.track {
          deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
          timestamp = deltaLog.snapshot.timestamp
        }
        assert(timestamp == getInCommitTimestamp(deltaLog, 0))
        // No explicit read.
        assert(filterUsageRecords(usageRecords, "delta.inCommitTimestamp.read").isEmpty)
      }
    }
  }

  test("snapshot.timestamp should be populated by protocolMetadataAndICTReconstruction " +
     "during cold reads of checkpoints + deltas") {
    // Make sure that we don't retrieve the time from the CRC.
    withSQLConf(DeltaSQLConf.DELTA_WRITE_CHECKSUM_ENABLED.key -> "false") {
      withTempTable(createTable = false) { tableName =>
        var deltaLog: DeltaLog = null
        var timestamp = -1L
        spark.range(1).write.format("delta").saveAsTable(tableName)
        deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
        deltaLog.createCheckpointAtVersion(0)
        deltaLog.startTransaction().commit(Seq(createTestAddFile("c1")), ManualUpdate)

        val usageRecords = Log4jUsageLogger.track {
          DeltaLog.clearCache() // Clear the post-commit snapshot from the cache.
          deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
          timestamp = deltaLog.snapshot.timestamp
        }
        assert(deltaLog.snapshot.checkpointProvider.version == 0)
        assert(deltaLog.snapshot.version == 1)
        assert(timestamp == getInCommitTimestamp(deltaLog, 1))
        // No explicit read.
        assert(filterUsageRecords(usageRecords, "delta.inCommitTimestamp.read").isEmpty)
      }
    }
  }

  test("snapshot.timestamp cannot be populated by protocolMetadataAndICTReconstruction " +
     "during cold reads of checkpoints") {
    // Make sure that we don't retrieve the time from the CRC.
    withSQLConf(DeltaSQLConf.DELTA_WRITE_CHECKSUM_ENABLED.key -> "false") {
      withTempTable(createTable = false) { tableName =>
        var deltaLog: DeltaLog = null
        var timestamp = -1L
        spark.range(1).write.format("delta").saveAsTable(tableName)
        DeltaLog.forTable(spark, TableIdentifier(tableName)).createCheckpointAtVersion(0)
        val usageRecords = Log4jUsageLogger.track {
          DeltaLog.clearCache() // Clear the post-commit snapshot from the cache.
          deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
          timestamp = deltaLog.snapshot.timestamp
        }
        assert(deltaLog.snapshot.checkpointProvider.version == 0)
        assert(timestamp == getInCommitTimestamp(deltaLog, 0))
        assert(filterUsageRecords(usageRecords, "delta.inCommitTimestamp.read").length == 1)
      }
    }
  }

  test("snapshot.timestamp is read from file when CRC doesn't have ICT and " +
    "the latest version has a checkpoint") {
    withTempTable(createTable = false) { tableName =>
      var deltaLog: DeltaLog = null
      var timestamp = -1L
      spark.range(1).write.format("delta").saveAsTable(tableName)
      deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
      deltaLog.createCheckpointAtVersion(0)
      // Remove the ICT from the CRC.
      InCommitTimestampTestUtils.overwriteICTInCrc(deltaLog, 0, None)
      val usageRecords = Log4jUsageLogger.track {
        DeltaLog.clearCache() // Clear the post-commit snapshot from the cache.
        deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
        timestamp = deltaLog.snapshot.timestamp
      }
      assert(deltaLog.snapshot.checkpointProvider.version == 0)
      assert(timestamp == getInCommitTimestamp(deltaLog, 0))
      val ictReadLog = filterUsageRecords(usageRecords, "delta.inCommitTimestamp.read").head
      val blob = JsonUtils.fromJson[Map[String, String]](ictReadLog.blob)
      assert(blob("version") == "0")
      assert(blob("checkpointVersion") == "0")
      assert(blob("isCRCPresent") == "true")
    }
  }

  test("Exceptions during ICT reads from file should be logged") {
    // Make sure that we don't retrieve the time from the CRC.
    withSQLConf(DeltaSQLConf.DELTA_WRITE_CHECKSUM_ENABLED.key -> "false") {
      withTempTable(createTable = false) { tableName =>
        val deltaLog = createInitialTable(tableName)
        // Remove CommitInfo from the commit.
        val commit1Path = DeltaCommitFileProvider(deltaLog.unsafeVolatileSnapshot).deltaFile(1)
        val actions = deltaLog.store.readAsIterator(commit1Path, deltaLog.newDeltaHadoopConf())
        val actionsWithoutCommitInfo =
          actions.filterNot(Action.fromJson(_).isInstanceOf[CommitInfo])
        deltaLog.store.write(
          commit1Path,
          actionsWithoutCommitInfo,
          overwrite = true,
          deltaLog.newDeltaHadoopConf())

        DeltaLog.clearCache()
        val latestSnapshot = DeltaLog.forTable(spark, TableIdentifier(tableName)).snapshot
        val usageRecords = Log4jUsageLogger.track {
          try {
            latestSnapshot.timestamp
          } catch {
            case _ : DeltaIllegalStateException => ()
          }
        }
        val ictReadLog = filterUsageRecords(usageRecords, "delta.inCommitTimestamp.read").head
        val blob = JsonUtils.fromJson[Map[String, String]](ictReadLog.blob)
        assert(blob("version") == "1")
        assert(blob("checkpointVersion") == "-1")
        assert(blob("exceptionMessage").startsWith("[DELTA_MISSING_COMMIT_INFO]"))
        assert(blob("exceptionStackTrace").contains(Snapshot.getClass.getName.stripSuffix("$")))
      }
    }
  }

  test("DeltaHistoryManager.getActiveCommitAtTimeFromICTRange") {
    withTempTable(createTable = false) { tableName =>
      spark.range(10).write.format("delta").saveAsTable(tableName)
      val startTime = System.currentTimeMillis()
      val clock = new ManualClock(startTime)
      val deltaLog = getDeltaLogWithClock(tableName, clock)
      val commitTimeDelta = 10
      val numberAdditionalCommits = 25
      assert(clock eq deltaLog.clock)
      for (i <- 1 to numberAdditionalCommits) {
        clock.setTime(startTime + i*commitTimeDelta)
        deltaLog.startTransaction().commit(Seq(createTestAddFile(i.toString)), ManualUpdate)
      }
      val deltaCommitFileProvider = DeltaCommitFileProvider(deltaLog.update())
      val commit0 = DeltaHistoryManager.Commit(0, getInCommitTimestamp(deltaLog, 0))
      var commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          startTime + commitTimeDelta*11,
          startCommit = commit0,
          numberAdditionalCommits + 1,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 3,
          spark,
          deltaCommitFileProvider).get
      assert(commit.version == 11)
      assert(commit.version == deltaLog.history.getActiveCommitAtTime(
        startTime + commitTimeDelta*11, true).version)

      // Search for commit 11 when the timestamp is not an exact match.
      commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          startTime + commitTimeDelta * 11 + 5,
          startCommit = commit0,
          numberAdditionalCommits + 1,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 3,
          spark,
          deltaCommitFileProvider).get
      assert(commit.version == 11)

      // Search for the last commit.
      commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          startTime + commitTimeDelta*25,
          startCommit = commit0,
          numberAdditionalCommits + 1,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 3,
          spark,
          deltaCommitFileProvider).get
      assert(commit.version == 25)
      // Search for the first commit.
      commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          commit0.timestamp,
          startCommit = commit0,
          numberAdditionalCommits + 1,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 3,
          spark,
          deltaCommitFileProvider).get
      assert(commit.version == 0)
    }
  }

  test("DeltaHistoryManager.getActiveCommitAtTimeFromICTRange --- " +
    "search for a timestamp after the last commit") {
    withTempTable(createTable = false) { tableName =>
      spark.range(10).write.format("delta").saveAsTable(tableName)
      val startTime = System.currentTimeMillis()
      val clock = new ManualClock(startTime)
      val deltaLog = getDeltaLogWithClock(tableName, clock)
      val commitTimeDelta = 10
      val numberAdditionalCommits = 2
      assert(clock eq deltaLog.clock)
      for (i <- 1 to numberAdditionalCommits) {
        clock.setTime(startTime + i * commitTimeDelta)
        deltaLog.startTransaction().commit(Seq(createTestAddFile(i.toString)), ManualUpdate)
      }
      val commit = deltaLog.history.getActiveCommitAtTime(
        new Timestamp(startTime + commitTimeDelta * (numberAdditionalCommits + 1)),
        catalogTableOpt = None,
        canReturnLastCommit = true)
      assert(commit.version == numberAdditionalCommits)

      // Searching beyond the last commit should throw an error
      // when canReturnLastCommit is false.
      val e = intercept[DeltaErrors.TemporallyUnstableInputException] {
        deltaLog.history.getActiveCommitAtTime(
          new Timestamp(startTime + commitTimeDelta * (numberAdditionalCommits + 1)),
          catalogTableOpt = None,
          canReturnLastCommit = false)
      }
      assert(e.getMessage.contains("The provided timestamp") && e.getMessage.contains("is after"))
    }
  }

  // Catalog Owned will also automatically enable ICT.
  testWithDefaultCommitCoordinatorUnset("DeltaHistoryManager.getActiveCommitAtTime: " +
    "works correctly when the history has both ICT and non-ICT commits") {
    withSQLConf(
      DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.defaultTablePropertyKey -> false.toString) {
      withTempTable(createTable = false) { tableName =>
        spark.range(10).write.format("delta").saveAsTable(tableName)
        val numNonICTCommits = 6
        val numICTCommits = 5
        val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
        for (i <- 1 to (numNonICTCommits-1)) {
          deltaLog.startTransaction().commit(Seq(createTestAddFile(i.toString)), ManualUpdate)
        }

        // Enable ICT.
        spark.sql(
          s"ALTER TABLE $tableName " +
            s"SET TBLPROPERTIES ('${DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.key}' = 'true')")

        for (i <- 1 to (numICTCommits-1)) {
          deltaLog.startTransaction().commit(Seq(createTestAddFile(i.toString)), ManualUpdate)
        }
        val currentVersion = deltaLog.update().version
        for (version <- 0L to currentVersion) {
          val ts = deltaLog.getSnapshotAt(version).timestamp
          // Search for the exact timestamp.
          var commit = deltaLog.history.getActiveCommitAtTime(ts, true)
          assert(commit.version == version)

          // Search using a timestamp just before the current timestamp.
          commit = deltaLog.history.getActiveCommitAtTime(
            new Timestamp(ts-1),
            catalogTableOpt = None,
            canReturnLastCommit = true,
            canReturnEarliestCommit = true)
          val expectedVersion = if (version == 0) 0 else version - 1
          assert(commit.version == expectedVersion)

          // Search using a timestamp just after the current timestamp.
          commit = deltaLog.history.getActiveCommitAtTime(ts + 1, true)
          assert(commit.version == version)
        }

        val enablementCommit =
          InCommitTimestampUtils.getValidatedICTEnablementInfo(deltaLog.snapshot.metadata).get
        // Create a checkpoint before deleting commits.
        deltaLog.createCheckpointAtVersion(enablementCommit.version + 2)

        // Search for an ICT commit when all the ICT commits leading up to and including it are
        // absent.
        val fs = deltaLog.logPath.getFileSystem(deltaLog.newDeltaHadoopConf())
        // Search for the commit immediately after the enablement commit.
        val searchTimestamp = getInCommitTimestamp(deltaLog, enablementCommit.version + 1)
        // Delete the first two ICT commits before performing the search.
        (enablementCommit.version to enablementCommit.version + 1).foreach { version =>
          fs.delete(FileNames.unsafeDeltaFile(deltaLog.logPath, version), false)
        }
        val e = intercept[DeltaErrors.TimestampEarlierThanCommitRetentionException] {
          deltaLog.history.getActiveCommitAtTime(searchTimestamp, false)
        }
        assert(
          e.getMessage.contains("The provided timestamp") && e.getMessage.contains("is before"))

        // Search for a non-ICT commit when all the non-ICT commits are missing.
        // Delete all the non-ICT commits.
        (0L until numNonICTCommits).foreach { version =>
          fs.delete(FileNames.unsafeDeltaFile(deltaLog.logPath, version), false)
        }
        intercept[DeltaErrors.TimestampEarlierThanCommitRetentionException] {
          deltaLog.history.getActiveCommitAtTime(enablementCommit.timestamp-1, false)
        }
        // The same query should work when the earliest commit is allowed to be returned.
        // The returned commit will be the earliest available ICT commit.
        val commit = deltaLog.history.getActiveCommitAtTime(
          new Timestamp(enablementCommit.timestamp-1),
          catalogTableOpt = None,
          canReturnLastCommit = false,
          canReturnEarliestCommit = true)
        // Note that we have already deleted the first two ICT commits.
        assert(commit.version == enablementCommit.version + 2)
        val earliestAvailableICTCommitTs = getInCommitTimestamp(
          deltaLog,
          enablementCommit.version + 2)
        assert(commit.timestamp == earliestAvailableICTCommitTs)
      }
    }
  }

  // Catalog Owned will also automatically enable ICT.
  testWithDefaultCommitCoordinatorUnset("DeltaHistoryManager.getHistory --- " +
      "works correctly when the history has both ICT and non-ICT commits") {
    withSQLConf(
      DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.defaultTablePropertyKey -> false.toString) {
      withTempTable(createTable = false) { tableName =>
        spark.range(1).write.format("delta").saveAsTable(tableName)
        val numNonICTCommits = 6
        val numICTCommits = 5
        val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
        for (i <- 1 to (numNonICTCommits - 1)) {
          deltaLog.startTransaction().commit(Seq(createTestAddFile(i.toString)), ManualUpdate)
        }

        // Enable ICT.
        spark.sql(
          s"ALTER TABLE $tableName " +
            s"SET TBLPROPERTIES ('${DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.key}' = 'true')")

        for (i <- 1 to (numICTCommits - 1)) {
          deltaLog.startTransaction().commit(Seq(createTestAddFile(i.toString)), ManualUpdate)
        }
        val currentVersion = deltaLog.update().version
        val ictEnablementVersion = numNonICTCommits

        // Fetch the entire history.
        val history = deltaLog.history.getHistory(None)
        assert(history.length == currentVersion + 1)
        val fs = deltaLog.logPath.getFileSystem(deltaLog.newDeltaHadoopConf())
        history.reverse.zipWithIndex.foreach { case (hist, version) =>
          assert(hist.getVersion == version)
          val expectedTimestamp = if (version < ictEnablementVersion) {
            fs.getFileStatus(FileNames.unsafeDeltaFile(deltaLog.logPath, version))
              .getModificationTime
          } else {
            getInCommitTimestamp(deltaLog, version)
          }
          assert(hist.timestamp.getTime == expectedTimestamp)
        }
        // Try fetching only the non-ICT commits.
        val nonICTHistory =
          deltaLog.history.getHistory(start = 0, end = Some(ictEnablementVersion - 1))
        assert(nonICTHistory.length == ictEnablementVersion)
        nonICTHistory.reverse.zipWithIndex.foreach { case (hist, version) =>
          assert(hist.getVersion == version)
          val expectedTimestamp =
            fs.getFileStatus(FileNames.unsafeDeltaFile(deltaLog.logPath, version))
              .getModificationTime
          assert(hist.timestamp.getTime == expectedTimestamp)
        }
        // Try fetching only the ICT commits.
        val ictHistory = deltaLog.history.getHistory(start = ictEnablementVersion, end = None)
        assert(ictHistory.length == currentVersion - ictEnablementVersion + 1)
        ictHistory
          .reverse
          .zip(ictEnablementVersion to currentVersion.toInt)
          .foreach { case (hist, version) =>
            assert(hist.getVersion == version)
            assert(hist.timestamp.getTime == getInCommitTimestamp(deltaLog, version))
          }
        // Try fetching some non-ICT + some ICT commits.
        val mixedHistory = deltaLog.history.getHistory(start = 2, end = Some(6))
        assert(mixedHistory.length == 5)
        mixedHistory
          .reverse
          .zip(2 to 6)
          .foreach { case (hist, version) =>
            assert(hist.getVersion == version)
            val expectedTimestamp = if (version < ictEnablementVersion) {
                fs.getFileStatus(FileNames.unsafeDeltaFile(deltaLog.logPath, version))
                  .getModificationTime
              } else {
                getInCommitTimestamp(deltaLog, version)
              }
            assert(hist.timestamp.getTime == expectedTimestamp)
        }
      }
    }
  }

  test("DeltaHistoryManager.getActiveCommitAtTimeFromICTRange -- boundary cases" ) {
    withTempTable(createTable = false) { tableName =>
      spark.range(10).write.format("delta").saveAsTable(tableName)
      val startTime = System.currentTimeMillis()
      val clock = new ManualClock(startTime)
      val deltaLog = getDeltaLogWithClock(tableName, clock)
      val commit0 = DeltaHistoryManager.Commit(0, deltaLog.snapshot.timestamp)
      val commitTimeDelta = 10
      val numberAdditionalCommits = 10
      assert(clock eq deltaLog.clock)
      for (i <- 1 to numberAdditionalCommits) {
        clock.setTime(startTime + i * commitTimeDelta)
        deltaLog.startTransaction().commit(Seq(createTestAddFile(i.toString)), ManualUpdate)
      }
      def getICTCommit(version: Long): DeltaHistoryManager.Commit =
        DeltaHistoryManager.Commit(version, startTime + commitTimeDelta * version)

      val deltaCommitFileProvider = DeltaCommitFileProvider(deltaLog.update())

      // Degenerate case: start + 1 == end.
      var commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          getICTCommit(2).timestamp,
          getICTCommit(2),
          end = 2 + 1,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 3,
          spark,
          deltaCommitFileProvider).get
      assert(commit == getICTCommit(2))

      // start + 1 == end, search for a timestamp that is after the window.
      commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
        getICTCommit(5).timestamp,
        getICTCommit(2),
          end = 2 + 1,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 3,
          spark,
          deltaCommitFileProvider).get
      assert(commit == getICTCommit(2))

      // start + 1 == end, search for a timestamp that is before the window.
      val commitOpt = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          getICTCommit(1).timestamp,
          getICTCommit(2),
          end = 2 + 1,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 3,
          spark,
          deltaCommitFileProvider)
      assert(commitOpt.isEmpty)

      // window size is exactly equal to `numChunks`.
      // Search for an intermediate commit.
      commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          getICTCommit(7).timestamp,
          getICTCommit(5),
          end = 9 + 1,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 5,
          spark,
          deltaCommitFileProvider).get
      assert(commit == getICTCommit(7))
      // Search for the last commit.
      commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          getICTCommit(9).timestamp,
          getICTCommit(5),
          end = 9 + 1,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 5,
          spark,
          deltaCommitFileProvider).get
      assert(commit == getICTCommit(9))

      // Delete the last few commits in the window.
      val fs = deltaLog.logPath.getFileSystem(deltaLog.newDeltaHadoopConf())
      fs.delete(FileNames.unsafeDeltaFile(deltaLog.logPath, 5), false)
      fs.delete(FileNames.unsafeDeltaFile(deltaLog.logPath, 6), false)
      // Search for the commit just before the deleted commits.
      commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          getICTCommit(4).timestamp,
          getICTCommit(2),
          end = 6 + 1,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 3,
          spark,
          deltaCommitFileProvider).get
      assert(commit == getICTCommit(4))

      // Search with the first couple of commits in the window deleted.
      commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          getICTCommit(8).timestamp,
          // Commits 5 and 6 have been deleted. We start from commit 5,
          // which does not exist anymore.
          getICTCommit(5),
          end = 10 + 1,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 3,
          spark,
          deltaCommitFileProvider).get
      assert(commit == getICTCommit(8))

      // Make one chunk in the first iteration completely empty.
      // Window -> [0, 11)
      // numChunks = 5, chunkSize = (11-0)/5 = 2
      // chunks -> [0, 2), [2, 4), [4, 6), [6, 8), [8, 10), [10, 11)
      fs.delete(FileNames.unsafeDeltaFile(deltaLog.logPath, 4), false)
      // 4, 5, 6 have been deleted, so window [4, 6) is completely empty.

      // Search for the commit 6.
      commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          getICTCommit(6).timestamp,
          commit0,
          end = 11,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 5,
          spark,
          deltaCommitFileProvider).get
      // [4,6] have been deleted, so we should get the commit at version 3.
      assert(commit == getICTCommit(3))

      // Search for a commit just after the deleted chunk (7).
      commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          getICTCommit(7).timestamp,
          commit0,
          end = 11,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 5,
          spark,
          deltaCommitFileProvider).get
      assert(commit == getICTCommit(7))

      // Scenario with many empty chunks.
      fs.delete(FileNames.unsafeDeltaFile(deltaLog.logPath, 8), false)
      fs.delete(FileNames.unsafeDeltaFile(deltaLog.logPath, 9), false)

      // Window -> [0, 11)
      // numChunks = 11, chunkSize = (11-0)/11 = 1
      // chunks -> [0, 1), [1, 2), [2, 3), ... [9, 10), [10, 11)
      // 4, 5, 6, 8, 9 have been deleted.
      // [4, 6), [5, 6) and [8, 9) are completely empty.

      // Search for a commit in between empty chunks (7).
      commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          getICTCommit(7).timestamp,
          commit0,
          end = 11,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 11,
          spark,
          deltaCommitFileProvider).get
      assert(commit == getICTCommit(7))

      // Search for a commit just after the last deleted chunk (10).
      commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          getICTCommit(10).timestamp,
          commit0,
          end = 11,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 11,
          spark,
          deltaCommitFileProvider).get
      assert(commit == getICTCommit(10))

      fs.delete(FileNames.unsafeDeltaFile(deltaLog.logPath, 10), false)
      // Everything after and including `end` does not exist.
      commit = DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
        getICTCommit(7).timestamp,
        commit0,
        // The last commit in the table is at version 9. But our
        // search window here is [7, 11).
        end = 11,
        deltaLog.newDeltaHadoopConf(),
        deltaLog.logPath,
        deltaLog.store,
        numChunks = 3,
        spark,
        deltaCommitFileProvider).get
      assert(commit == getICTCommit(7))
    }
  }

  test("DeltaHistoryManager.getHistory --- all ICT commits") {
    withTempTable(createTable = false) { tableName =>
      spark.range(1).write.format("delta").saveAsTable(tableName)
      val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
      val numberAdditionalCommits = 4
      for (i <- 1 to numberAdditionalCommits) {
        deltaLog.startTransaction().commit(Seq(createTestAddFile(i.toString)), ManualUpdate)
      }
      val history = deltaLog.history.getHistory(None)
      assert(history.length == numberAdditionalCommits + 1)
      history.reverse.zipWithIndex.foreach { case (hist, version) =>
        assert(hist.timestamp.getTime == getInCommitTimestamp(deltaLog, version))
      }
      // Try fetching a limited subset of the history.
      val historySubset = deltaLog.history.getHistory(start = 2, end = Some(2))
      assert(historySubset.length == 1)
      assert(historySubset.head.timestamp.getTime == getInCommitTimestamp(deltaLog, 2))
    }
  }

  for (ictEnablementVersion <- Seq(1, 4, 7))
  testWithDefaultCommitCoordinatorUnset(s"CDC read with all commits being ICT " +
    s"[ictEnablementVersion = $ictEnablementVersion]") {
    withSQLConf(
      DeltaConfigs.CHANGE_DATA_FEED.defaultTablePropertyKey -> "true",
      DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.defaultTablePropertyKey -> "false"
    ) {
      withTempTable(createTable = false) { tableName =>
        for (i <- 0 to 7) {
          if (i == ictEnablementVersion) {
            spark.sql(
              s"ALTER TABLE $tableName " +
                s"SET TBLPROPERTIES ('${DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.key}' = 'true')")
          } else {
            spark.range(i, i + 1).write.format("delta").mode("append").saveAsTable(tableName)
          }
        }
        val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
        val result = spark.read
          .format("delta")
          .option("startingVersion", "1")
          .option("endingVersion", "7")
          .option("readChangeFeed", "true")
          .table(tableName)
          .select("_commit_timestamp", "_commit_version")
          .collect()
        val fileTimestampsMap = getFileModificationTimesMap(deltaLog, 0, 7)
        result.foreach { row =>
          val v = row.getAs[Long]("_commit_version")
          val expectedTimestamp = if (v >= ictEnablementVersion) {
            getInCommitTimestamp(deltaLog, v)
          } else {
            fileTimestampsMap(v)
          }
          assert(row.getAs[Timestamp]("_commit_timestamp").getTime == expectedTimestamp)
        }
      }
    }
  }

  for (ictEnablementVersion <- Seq(1, 4, 7))
  testWithDefaultCommitCoordinatorUnset(s"Streaming query + CDC " +
    s"[ictEnablementVersion = $ictEnablementVersion]") {
    withSQLConf(
      DeltaConfigs.CHANGE_DATA_FEED.defaultTablePropertyKey -> "true",
      DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.defaultTablePropertyKey -> "false"
    ) {
      withTempTable(createTable = false) { sourceTableName =>
        withTempDir { checkpointDir =>
          withTempTable(createTable = false) { sinkTableName =>
        spark.range(0).write.format("delta").mode("append").saveAsTable(sourceTableName)

        val sourceDeltaLog = DeltaLog.forTable(spark, TableIdentifier(sourceTableName))
        val streamingQuery = spark.readStream
          .format("delta")
          .option("readChangeFeed", "true")
          .table(sourceTableName)
          .select(
            col("_commit_timestamp").alias("source_commit_timestamp"),
            col("_commit_version").alias("source_commit_version"))
          .writeStream
          .format("delta")
          .option("checkpointLocation", checkpointDir.getCanonicalPath)
          .toTable(sinkTableName)
        for (i <- 1 to 7) {
          if (i == ictEnablementVersion) {
            spark.sql(s"ALTER TABLE $sourceTableName " +
              s"SET TBLPROPERTIES ('${DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.key}' = 'true')")
          } else {
            spark.range(i, i + 1).write.format("delta").mode("append").saveAsTable(sourceTableName)
          }
        }
        streamingQuery.processAllAvailable()
        val fileTimestampsMap = getFileModificationTimesMap(sourceDeltaLog, 0, 7)
        val result = spark.read.format("delta")
          .table(sinkTableName)
          .collect()
        result.foreach { row =>
          val v = row.getAs[Long]("source_commit_version")
          val expectedTimestamp = if (v >= ictEnablementVersion) {
            getInCommitTimestamp(sourceDeltaLog, v)
          } else {
            fileTimestampsMap(v)
          }
          assert(
            row.getAs[Timestamp]("source_commit_timestamp").getTime == expectedTimestamp)
        }
      }}}
    }
  }

  private def testICTEnablementPropertyRetention(
      expectRetention: Boolean,
      expectICTEnabled: Option[Boolean] = None)(runCommand: (String) => Unit): Unit = {
    val ictConfOpt =
      spark.conf.getOption(DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.defaultTablePropertyKey)
    try {
      spark.conf.unset(DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.defaultTablePropertyKey)
      withTempTable(createTable = false) { tableName =>
        spark.range(1).write.format("delta").saveAsTable(tableName)
        val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tableName))
        // Enable ICT at version 1 instead of 0 so that we can test the retention of
        // enablement provenance properties as well.
        spark.sql(
          s"ALTER TABLE $tableName " +
            s"SET TBLPROPERTIES ('${DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.key}' = 'true')")
        val enablementVersion =
          DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_VERSION.fromMetaData(
            deltaLog.snapshot.metadata)
        val enablementTimestamp =
          DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_TIMESTAMP.fromMetaData(
            deltaLog.snapshot.metadata)
        assert(enablementVersion.contains(1))
        assert(enablementTimestamp.isDefined)

        spark.range(2, 3).write.format("delta").mode("overwrite").saveAsTable(tableName)

        // Run the REPLACE/CLONE command.
        runCommand(tableName)

        val metadataAfterReplace = deltaLog.update().metadata
        assert(
          DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.fromMetaData(
            metadataAfterReplace) == expectICTEnabled.getOrElse(expectRetention))
        if (expectRetention) {
          assert(
            DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_TIMESTAMP.fromMetaData(
              metadataAfterReplace) == enablementTimestamp)
          assert(
            DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_VERSION.fromMetaData(
              metadataAfterReplace) == enablementVersion)
        } else {
          Seq(
            DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_TIMESTAMP.key,
            DeltaConfigs.IN_COMMIT_TIMESTAMP_ENABLEMENT_VERSION.key
          ).foreach { key =>
            assert(!metadataAfterReplace.configuration.contains(key))
          }
        }
      }
    } finally {
      ictConfOpt.foreach { ictConf =>
        spark.conf.set(DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.defaultTablePropertyKey, ictConf)
      }
    }
  }

  testWithDefaultCommitCoordinatorUnset(
    "ICT enablement properties remain unchanged after a REPLACE with explicit enablement") {
    testICTEnablementPropertyRetention(expectRetention = true) { tableName =>
      sql(s"REPLACE TABLE $tableName USING delta " +
        s"TBLPROPERTIES ('${DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.key}' = 'true') " +
        "AS SELECT * FROM range(3, 4)")
    }
  }

  testWithDefaultCommitCoordinatorUnset(
    "ICT enablement properties are dropped after a REPLACE with explicit enablement " +
      s"when the ${DeltaSQLConf.IN_COMMIT_TIMESTAMP_RETAIN_ENABLEMENT_INFO_FIX_ENABLED.key} " +
      s"is disabled") {
    withSQLConf(
      DeltaSQLConf.IN_COMMIT_TIMESTAMP_RETAIN_ENABLEMENT_INFO_FIX_ENABLED.key -> "false"
    ) {
      testICTEnablementPropertyRetention(
        expectRetention = false, expectICTEnabled = Some(true)) { tableName =>
          sql(
            s"REPLACE TABLE $tableName USING delta " +
              s"TBLPROPERTIES ('${DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.key}' = 'true') " +
              "AS SELECT * FROM range(3, 4)")
      }
    }
  }

  testWithDefaultCommitCoordinatorUnset(
    "ICT enablement properties are dropped after a REPLACE with explicit disablement") {
    testICTEnablementPropertyRetention(expectRetention = false) { tableName =>
      sql(s"REPLACE TABLE $tableName USING delta " +
        s"TBLPROPERTIES ('${DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.key}' = 'false') " +
        "AS SELECT * FROM range(3, 4)")
    }
  }

  testWithDefaultCommitCoordinatorUnset(
    "ICT is completely dropped after a REPLACE with no explicit disablement") {
    testICTEnablementPropertyRetention(expectRetention = false) { tableName =>
      sql(s"REPLACE TABLE $tableName USING delta AS SELECT * FROM range(3, 4)")
    }
  }
}

class InCommitTimestampWithCatalogOwnedBatch1Suite extends InCommitTimestampSuite {
  override def catalogOwnedCoordinatorBackfillBatchSize: Option[Int] = Some(1)
}

class InCommitTimestampWithCatalogOwnedBatch2Suite extends InCommitTimestampSuite {
  override def catalogOwnedCoordinatorBackfillBatchSize: Option[Int] = Some(2)
}

class InCommitTimestampWithCatalogOwnedBatch5Suite extends InCommitTimestampSuite {
  override def beforeAll(): Unit = {
    super.beforeAll()
    spark.conf.set(DeltaConfigs.IN_COMMIT_TIMESTAMPS_ENABLED.defaultTablePropertyKey, "true")
  }

  override def catalogOwnedCoordinatorBackfillBatchSize: Option[Int] = Some(5)

  test("getActiveCommitAtTime works correctly within catalog owned range") {
    CatalogOwnedCommitCoordinatorProvider.clearBuilders()
    CatalogOwnedCommitCoordinatorProvider.registerBuilder(
      catalogName = CatalogOwnedTableUtils.DEFAULT_CATALOG_NAME_FOR_TESTING,
      commitCoordinatorBuilder = TrackingInMemoryCommitCoordinatorBuilder(batchSize = 10)
    )
    withTempTable(createTable = false) { tableName =>
      spark.range(10).write.format("delta").saveAsTable(tableName)
      val (deltaLog, snapshot) = DeltaLog.forTableWithSnapshot(spark, TableIdentifier(tableName))
      val commit0 = DeltaHistoryManager.Commit(0, snapshot.timestamp)
      val tableCommitCoordinatorClient =
        CatalogOwnedTableUtils.populateTableCommitCoordinatorFromCatalog(
          spark,
          catalogTableOpt = deltaLog.initialCatalogTable,
          snapshot
        ).get
      val numberAdditionalCommits = 4
      // Create 4 unbackfilled commits.
      for (i <- 1 to numberAdditionalCommits) {
        deltaLog.startTransaction().commit(Seq(createTestAddFile(i.toString)), ManualUpdate)
      }
      val commitFileProvider = DeltaCommitFileProvider(deltaLog.update())
      val unbackfilledCommits =
        tableCommitCoordinatorClient
          .getCommits(Some(1L))
          .getCommits.asScala
          .map { commit => DeltaHistoryManager.Commit(commit.getVersion, commit.getCommitTimestamp)}
      val commits = (Seq(commit0) ++ unbackfilledCommits).toList
      // Search for the exact timestamp.
      for (commit <- commits) {
        val resCommit = deltaLog.history.getActiveCommitAtTime(
          new Timestamp(commit.timestamp), catalogTableOpt = None, canReturnLastCommit = false)
        assert(resCommit.version == commit.version)
        assert(resCommit.timestamp == commit.timestamp)
      }

      // getActiveCommitAtTimeFromICTRange should throw an IllegalStateException
      // if it does not manage to find an unbackfilled commit.

      // Delete the target unbackfilled commit:
      val fs = deltaLog.logPath.getFileSystem(deltaLog.newDeltaHadoopConf())
      val commit3Path = commitFileProvider.deltaFile(3)
      fs.delete(commit3Path, false)
      val commit3Timestamp = unbackfilledCommits(2).timestamp
      var errorOpt = Option.empty[org.apache.spark.SparkException]
      try {
        DeltaHistoryManager.getActiveCommitAtTimeFromICTRange(
          commit3Timestamp,
          commit0,
          numberAdditionalCommits + 1,
          deltaLog.newDeltaHadoopConf(),
          deltaLog.logPath,
          deltaLog.store,
          numChunks = 5,
          spark,
          commitFileProvider)
      } catch {
        case e: org.apache.spark.SparkException => errorOpt = Some(e)
          e.getStackTrace.exists(_.toString.contains(
            s"Could not find commit 3 which was expected to be at " +
              s"path ${commit3Path.toString}."))
      }
      assert(errorOpt.isDefined)
    }
  }
}
