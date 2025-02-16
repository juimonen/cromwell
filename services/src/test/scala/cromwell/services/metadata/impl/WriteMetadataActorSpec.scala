package cromwell.services.metadata.impl

import java.sql.{Connection, Timestamp}
import akka.actor.ActorRef
import akka.testkit.{TestFSMRef, TestProbe}
import cats.data.NonEmptyVector
import com.typesafe.config.ConfigFactory
import cromwell.core.{TestKitSuite, WorkflowId}
import cromwell.database.sql.joins.MetadataJobQueryValue
import cromwell.database.sql.tables.{InformationSchemaEntry, MetadataEntry, WorkflowMetadataSummaryEntry}
import cromwell.database.sql.{MetadataSqlDatabase, SqlDatabase}
import cromwell.services.metadata.MetadataService.{
  MetadataWriteAction,
  MetadataWriteFailure,
  MetadataWriteSuccess,
  PutMetadataAction,
  PutMetadataActionAndRespond
}
import cromwell.services.metadata.impl.MetadataStatisticsRecorder.MetadataStatisticsDisabled
import cromwell.services.metadata.impl.WriteMetadataActorSpec.BatchSizeCountingWriteMetadataActor
import cromwell.services.metadata.{MetadataEvent, MetadataInt, MetadataKey, MetadataValue}
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success}

class WriteMetadataActorSpec extends TestKitSuite with AnyFlatSpecLike with Matchers with Eventually {

  behavior of "WriteMetadataActor"

  implicit val defaultPatience = PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = Span(500, Millis))

  it should "process jobs in the correct batch sizes" in {
    val registry = TestProbe().ref
    val writeActor = TestFSMRef(new BatchSizeCountingWriteMetadataActor(10, 10.millis, registry, Int.MaxValue, List()) {
      override val metadataDatabaseInterface = mockDatabaseInterface(0)
    })

    def metadataEvent(index: Int) = PutMetadataAction(
      MetadataEvent(MetadataKey(WorkflowId.randomId(), None, s"metadata_key_$index"), MetadataValue(s"hello_$index"))
    )

    val probes = (0 until 27)
      .map { _ =>
        val probe = TestProbe()
        probe
      }
      .zipWithIndex
      .map { case (probe, index) =>
        probe -> metadataEvent(index)
      }

    probes foreach { case (probe, msg) =>
      probe.send(writeActor, msg)
    }

    eventually {
      writeActor.underlyingActor.batchSizes should be(Vector(10, 10, 7))
    }

    writeActor.stop()
  }

  val failuresBetweenSuccessValues = List(0, 5, 9)
  failuresBetweenSuccessValues foreach { failureRate =>
    it should s"succeed metadata writes and respond to all senders even with $failureRate failures between each success" in {
      val registry = TestProbe().ref
      val writeActor =
        TestFSMRef(new BatchSizeCountingWriteMetadataActor(10, 10.millis, registry, Int.MaxValue, List()) {
          override val metadataDatabaseInterface = mockDatabaseInterface(failureRate)
        })

      def metadataEvent(index: Int, probe: ActorRef) =
        PutMetadataActionAndRespond(List(
                                      MetadataEvent(MetadataKey(WorkflowId.randomId(), None, s"metadata_key_$index"),
                                                    MetadataValue(s"hello_$index")
                                      )
                                    ),
                                    probe
        )

      val probes = (0 until 43)
        .map { _ =>
          val probe = TestProbe()
          probe
        }
        .zipWithIndex
        .map { case (probe, index) =>
          probe -> metadataEvent(index, probe.ref)
        }

      probes foreach { case (probe, msg) =>
        probe.send(writeActor, msg)
      }

      probes.foreach { case (probe, msg) =>
        probe.expectMsg(MetadataWriteSuccess(msg.events))
      }
      eventually {
        writeActor.underlyingActor.failureCount should be(5 * failureRate)
      }

      writeActor.stop()
    }
  }

  it should s"fail metadata writes and respond to all senders with failures" in {
    val registry = TestProbe().ref
    val writeActor = TestFSMRef(new BatchSizeCountingWriteMetadataActor(10, 10.millis, registry, Int.MaxValue, List()) {
      override val metadataDatabaseInterface = mockDatabaseInterface(100)
    })

    def metadataEvent(index: Int, probe: ActorRef) = PutMetadataActionAndRespond(
      List(
        MetadataEvent(MetadataKey(WorkflowId.randomId(), None, s"metadata_key_$index"), MetadataValue(s"hello_$index"))
      ),
      probe
    )

    val probes = (0 until 43)
      .map { _ =>
        val probe = TestProbe()
        probe
      }
      .zipWithIndex
      .map { case (probe, index) =>
        probe -> metadataEvent(index, probe.ref)
      }

    probes foreach { case (probe, msg) =>
      probe.send(writeActor, msg)
    }

    probes.foreach { case (probe, msg) =>
      probe.expectMsg(MetadataWriteFailure(WriteMetadataActorSpec.IntermittentException, msg.events))
    }
    eventually {
      writeActor.underlyingActor.failureCount should be(5 * 10)
    }

    writeActor.stop()
  }

  it should s"test removing emojis from metadata works as expected" in {
    val registry = TestProbe().ref
    val writeActor =
      TestFSMRef(new BatchSizeCountingWriteMetadataActor(10, 10.millis, registry, Int.MaxValue, List("metadata_key")) {
        override val metadataDatabaseInterface = mockDatabaseInterface(100)
      })

    def metadataEvent(index: Int, probe: ActorRef) = PutMetadataActionAndRespond(
      List(
        MetadataEvent(MetadataKey(WorkflowId.randomId(), None, "metadata_key"), MetadataValue(s"🎉_$index"))
      ),
      probe
    )

    val probes = (0 until 43)
      .map { _ =>
        val probe = TestProbe()
        probe
      }
      .zipWithIndex
      .map { case (probe, index) =>
        probe -> metadataEvent(index, probe.ref)
      }

    val metadataWriteActions = probes.map(probe => probe._2).toVector
    val metadataWriteActionNE = NonEmptyVector(metadataWriteActions.head, metadataWriteActions.tail)

    val sanitizedWriteActions = writeActor.underlyingActor.sanitizeInputs(metadataWriteActionNE)

    sanitizedWriteActions.map { writeAction =>
      writeAction.events.map { event =>
        if (event.value.getOrElse(fail("Removed value from metadata event")).value.contains("\uD83C\uDF89")) {
          fail("Metadata event contains emoji")
        }

        if (!event.value.getOrElse(fail("Removed value from metadata event")).value.contains("\uFFFD")) {
          fail("Incorrect character used to replace emoji")
        }
      }
    }
  }

  it should s"test removing emojis from metadata which doesn't contain emojis returns the string" in {
    val registry = TestProbe().ref
    val writeActor =
      TestFSMRef(new BatchSizeCountingWriteMetadataActor(10, 10.millis, registry, Int.MaxValue, List("metadata_key")) {
        override val metadataDatabaseInterface = mockDatabaseInterface(100)
      })

    def metadataEvent(index: Int, probe: ActorRef) = PutMetadataActionAndRespond(
      List(
        MetadataEvent(MetadataKey(WorkflowId.randomId(), None, "metadata_key"), MetadataValue(s"hello_$index"))
      ),
      probe
    )

    val probes = (0 until 43)
      .map { _ =>
        val probe = TestProbe()
        probe
      }
      .zipWithIndex
      .map { case (probe, index) =>
        probe -> metadataEvent(index, probe.ref)
      }

    val metadataWriteActions = probes.map(probe => probe._2).toVector
    val metadataWriteActionNE = NonEmptyVector(metadataWriteActions.head, metadataWriteActions.tail)

    val sanitizedWriteActions = writeActor.underlyingActor.sanitizeInputs(metadataWriteActionNE)

    sanitizedWriteActions.map { writeAction =>
      writeAction.events.map { event =>
        if (event.value.getOrElse(fail("Removed value from metadata event")).value.contains("\uD83C\uDF89")) {
          fail("Metadata event contains emoji")
        }

        if (event.value.getOrElse(fail("Removed value from metadata event")).value.contains("\uFFFD")) {
          fail("Incorrectly replaced character in metadata event")
        }
      }
    }
  }

  it should s"test sanitize inputs does not modify metadata values whose keys are not included in the keys to clean" in {
    val registry = TestProbe().ref
    val writeActor =
      TestFSMRef(new BatchSizeCountingWriteMetadataActor(10, 10.millis, registry, Int.MaxValue, List("some_key")) {
        override val metadataDatabaseInterface = mockDatabaseInterface(100)
      })

    def metadataEvent(index: Int, probe: ActorRef) = PutMetadataActionAndRespond(
      List(
        MetadataEvent(MetadataKey(WorkflowId.randomId(), None, "metadata_key"), MetadataValue(s"🎉_$index"))
      ),
      probe
    )

    val probes = (0 until 43)
      .map { _ =>
        val probe = TestProbe()
        probe
      }
      .zipWithIndex
      .map { case (probe, index) =>
        probe -> metadataEvent(index, probe.ref)
      }

    val metadataWriteActions = probes.map(probe => probe._2).toVector
    val metadataWriteActionNE = NonEmptyVector(metadataWriteActions.head, metadataWriteActions.tail)

    val sanitizedWriteActions = writeActor.underlyingActor.sanitizeInputs(metadataWriteActionNE)

    sanitizedWriteActions.map { writeAction =>
      writeAction.events.map { event =>
        if (!event.value.getOrElse(fail("Removed value from metadata event")).value.contains("\uD83C\uDF89")) {
          fail("Metadata event was incorrectly sanitized")
        }

        if (event.value.getOrElse(fail("Removed value from metadata event")).value.contains("\uFFFD")) {
          fail("Incorrectly replaced character in metadata event")
        }
      }
    }
  }

  it should s"test sanitize inputs does not modify metadata values that are not strings" in {
    val registry = TestProbe().ref
    val writeActor =
      TestFSMRef(new BatchSizeCountingWriteMetadataActor(10, 10.millis, registry, Int.MaxValue, List("metadata_key")) {
        override val metadataDatabaseInterface = mockDatabaseInterface(100)
      })

    def metadataEvent(index: Int, probe: ActorRef) = PutMetadataActionAndRespond(
      List(
        MetadataEvent(MetadataKey(WorkflowId.randomId(), None, "metadata_key"), MetadataValue(100))
      ),
      probe
    )

    val probes = (0 until 43)
      .map { _ =>
        val probe = TestProbe()
        probe
      }
      .zipWithIndex
      .map { case (probe, index) =>
        probe -> metadataEvent(index, probe.ref)
      }

    val metadataWriteActions = probes.map(probe => probe._2).toVector
    val metadataWriteActionNE = NonEmptyVector(metadataWriteActions.head, metadataWriteActions.tail)

    val sanitizedWriteActions = writeActor.underlyingActor.sanitizeInputs(metadataWriteActionNE)

    sanitizedWriteActions.map { writeAction =>
      writeAction.events.map { event =>
        if (!(event.value.getOrElse(fail("Removed value from metadata event")).valueType == MetadataInt)) {
          fail("Changed metadata type")
        }

        if (!event.value.getOrElse(fail("Removed value from metadata event")).value.equals("100")) {
          fail("Modified metadata value")
        }
      }
    }
  }

  it should s"test metadata types are correct before db insertion" in {
    val registry = TestProbe().ref
    val writeActor =
      TestFSMRef(new BatchSizeCountingWriteMetadataActor(10, 10.millis, registry, Int.MaxValue, List("metadata_key")) {
        override val metadataDatabaseInterface = mockDatabaseInterface(100)
      })

    val metadataEvents = PutMetadataAction(
      List(
        MetadataEvent(MetadataKey(WorkflowId.randomId(), None, "metadata_key"), MetadataValue(100)),
        MetadataEvent(MetadataKey(WorkflowId.randomId(), None, "metadata_key"), MetadataValue(5.5)),
        MetadataEvent(MetadataKey(WorkflowId.randomId(), None, "metadata_key"), MetadataValue(true)),
        MetadataEvent(MetadataKey(WorkflowId.randomId(), None, "metadata_key"), MetadataValue("hello"))
      )
    )

    val processedMetadataTuple = writeActor.underlyingActor.prepareMetadata(NonEmptyVector(metadataEvents, Vector()))
    val allPutEvents = processedMetadataTuple._2

    allPutEvents.zipWithIndex.map { eventWithIndex =>
      val expectedEvent = metadataEvents.events.toList(eventWithIndex._2)
      val actualEvent = eventWithIndex._1
      actualEvent shouldBe expectedEvent
    }
  }

  // Mock database interface.
  // A customizable number of failures occur between each success
  def mockDatabaseInterface(failuresBetweenEachSuccess: Int) = new MetadataSqlDatabase with SqlDatabase {
    private def notImplemented() = throw new UnsupportedOperationException

    override protected val urlKey = "mock_database_url"
    override protected val originalDatabaseConfig = ConfigFactory.empty

    override def connectionDescription: String = "Mock Database"

    override def existsMetadataEntries()(implicit ec: ExecutionContext): Nothing = notImplemented()

    var requestsSinceLastSuccess = 0
    // Return successful
    override def addMetadataEntries(metadataEntries: Iterable[MetadataEntry],
                                    startMetadataKey: String,
                                    endMetadataKey: String,
                                    nameMetadataKey: String,
                                    statusMetadataKey: String,
                                    submissionMetadataKey: String,
                                    parentWorkflowIdKey: String,
                                    rootWorkflowIdKey: String,
                                    labelMetadataKey: String
    )(implicit ec: ExecutionContext): Future[Unit] =
      if (requestsSinceLastSuccess == failuresBetweenEachSuccess) {
        requestsSinceLastSuccess = 0
        Future.successful(())
      } else {
        requestsSinceLastSuccess += 1
        Future.failed(WriteMetadataActorSpec.IntermittentException)
      }

    override def metadataSummaryEntryExists(workflowExecutionUuid: String)(implicit ec: ExecutionContext): Nothing =
      notImplemented()

    override def queryMetadataEntries(workflowExecutionUuid: String, timeout: Duration)(implicit
      ec: ExecutionContext
    ): Nothing = notImplemented()

    override def streamMetadataEntries(workflowExecutionUuid: String): Nothing = notImplemented()

    override def queryMetadataEntries(workflowExecutionUuid: String, metadataKey: String, timeout: Duration)(implicit
      ec: ExecutionContext
    ): Nothing = notImplemented()

    override def queryMetadataEntries(workflowExecutionUuid: String,
                                      callFullyQualifiedName: String,
                                      jobIndex: Option[Int],
                                      jobAttempt: Option[Int],
                                      timeout: Duration
    )(implicit ec: ExecutionContext): Nothing = notImplemented()

    override def queryMetadataEntries(workflowUuid: String,
                                      metadataKey: String,
                                      callFullyQualifiedName: String,
                                      jobIndex: Option[Int],
                                      jobAttempt: Option[Int],
                                      timeout: Duration
    )(implicit ec: ExecutionContext): Nothing = notImplemented()

    override def queryMetadataEntryWithKeyConstraints(workflowExecutionUuid: String,
                                                      metadataKeysToFilterFor: List[String],
                                                      metadataKeysToFilterAgainst: List[String],
                                                      metadataJobQueryValue: MetadataJobQueryValue,
                                                      timeout: Duration
    )(implicit ec: ExecutionContext): Nothing = notImplemented()

    override def summarizeIncreasing(
      labelMetadataKey: String,
      limit: Int,
      buildUpdatedSummary: (Option[WorkflowMetadataSummaryEntry], Seq[MetadataEntry]) => WorkflowMetadataSummaryEntry
    )(implicit ec: ExecutionContext): Nothing = notImplemented()

    /**
      * Retrieves a window of summarizable metadata satisfying the specified criteria.
      *
      * @param buildUpdatedSummary Takes in the optional existing summary and the metadata, returns the new summary.
      * @return A `Future` with the maximum metadataEntryId summarized by the invocation of this method.
      */
    override def summarizeDecreasing(
      summaryNameDecreasing: String,
      summaryNameIncreasing: String,
      labelMetadataKey: String,
      limit: Int,
      buildUpdatedSummary: (Option[WorkflowMetadataSummaryEntry], Seq[MetadataEntry]) => WorkflowMetadataSummaryEntry
    )(implicit ec: ExecutionContext): Nothing = notImplemented()

    override def getWorkflowStatus(workflowExecutionUuid: String)(implicit ec: ExecutionContext): Nothing =
      notImplemented()

    override def getWorkflowLabels(workflowExecutionUuid: String)(implicit ec: ExecutionContext): Nothing =
      notImplemented()

    override def getRootAndSubworkflowLabels(rootWorkflowExecutionUuid: String)(implicit
      ec: ExecutionContext
    ): Nothing = notImplemented()

    override def queryWorkflowSummaries(parentWorkflowIdMetadataKey: String,
                                        workflowStatuses: Set[String],
                                        workflowNames: Set[String],
                                        workflowExecutionUuids: Set[String],
                                        labelAndKeyLabelValues: Set[(String, String)],
                                        labelOrKeyLabelValues: Set[(String, String)],
                                        excludeLabelAndValues: Set[(String, String)],
                                        excludeLabelOrValues: Set[(String, String)],
                                        submissionTimestamp: Option[Timestamp],
                                        startTimestampOption: Option[Timestamp],
                                        endTimestampOption: Option[Timestamp],
                                        metadataArchiveStatus: Set[Option[String]],
                                        includeSubworkflows: Boolean,
                                        page: Option[Int],
                                        pageSize: Option[Int],
                                        newestFirst: Boolean
    )(implicit ec: ExecutionContext): Nothing =
      notImplemented()

    override def countWorkflowSummaries(parentWorkflowIdMetadataKey: String,
                                        workflowStatuses: Set[String],
                                        workflowNames: Set[String],
                                        workflowExecutionUuids: Set[String],
                                        labelAndKeyLabelValues: Set[(String, String)],
                                        labelOrKeyLabelValues: Set[(String, String)],
                                        excludeLabelAndValues: Set[(String, String)],
                                        excludeLabelOrValues: Set[(String, String)],
                                        submissionTimestamp: Option[Timestamp],
                                        startTimestampOption: Option[Timestamp],
                                        endTimestampOption: Option[Timestamp],
                                        metadataArchiveStatus: Set[Option[String]],
                                        includeSubworkflows: Boolean
    )(implicit ec: ExecutionContext): Nothing =
      notImplemented()

    override def updateMetadataArchiveStatus(workflowExecutionUuid: String,
                                             newArchiveStatus: Option[String]
    ): Future[Int] = notImplemented()

    override def withConnection[A](block: Connection => A): Nothing =
      notImplemented()

    override def close(): Nothing = notImplemented()

    override def deleteAllMetadataForWorkflowAndUpdateArchiveStatus(workflowId: String,
                                                                    newArchiveStatus: Option[String]
    )(implicit ec: ExecutionContext): Future[Int] =
      notImplemented()

    override def getRootWorkflowId(workflowId: String)(implicit ec: ExecutionContext): Future[Option[String]] =
      notImplemented()

    override def queryWorkflowIdsByArchiveStatusAndEndedOnOrBeforeThresholdTimestamp(archiveStatus: Option[String],
                                                                                     thresholdTimestamp: Timestamp,
                                                                                     batchSize: Long
    )(implicit ec: ExecutionContext): Future[Seq[String]] =
      notImplemented()

    override def getSummaryQueueSize()(implicit ec: ExecutionContext): Future[Int] =
      notImplemented()

    override def countMetadataEntries(workflowExecutionUuid: String, expandSubWorkflows: Boolean, timeout: Duration)(
      implicit ec: ExecutionContext
    ): Future[Int] =
      notImplemented()

    override def countMetadataEntries(workflowExecutionUuid: String,
                                      metadataKey: String,
                                      expandSubWorkflows: Boolean,
                                      timeout: Duration
    )(implicit ec: ExecutionContext): Future[Int] =
      notImplemented()

    override def countMetadataEntries(workflowExecutionUuid: String,
                                      callFullyQualifiedName: String,
                                      jobIndex: Option[Int],
                                      jobAttempt: Option[Int],
                                      expandSubWorkflows: Boolean,
                                      timeout: Duration
    )(implicit ec: ExecutionContext): Future[Int] =
      notImplemented()

    override def countMetadataEntries(workflowUuid: String,
                                      metadataKey: String,
                                      callFullyQualifiedName: String,
                                      jobIndex: Option[Int],
                                      jobAttempt: Option[Int],
                                      expandSubWorkflows: Boolean,
                                      timeout: Duration
    )(implicit ec: ExecutionContext): Future[Int] =
      notImplemented()

    override def countMetadataEntryWithKeyConstraints(workflowExecutionUuid: String,
                                                      metadataKeysToFilterFor: List[String],
                                                      metadataKeysToFilterAgainst: List[String],
                                                      metadataJobQueryValue: MetadataJobQueryValue,
                                                      expandSubWorkflows: Boolean,
                                                      timeout: Duration
    )(implicit ec: ExecutionContext): Future[Int] =
      notImplemented()

    override def getMetadataArchiveStatusAndEndTime(workflowId: String)(implicit
      ec: ExecutionContext
    ): Future[(Option[String], Option[Timestamp])] = notImplemented()

    override def queryWorkflowsToArchiveThatEndedOnOrBeforeThresholdTimestamp(workflowStatuses: List[String],
                                                                              workflowEndTimestampThreshold: Timestamp,
                                                                              batchSize: Long
    )(implicit ec: ExecutionContext): Future[Seq[WorkflowMetadataSummaryEntry]] = notImplemented()

    override def countWorkflowsLeftToArchiveThatEndedOnOrBeforeThresholdTimestamp(
      workflowStatuses: List[String],
      workflowEndTimestampThreshold: Timestamp
    )(implicit ec: ExecutionContext): Future[Int] = notImplemented()

    override def countWorkflowsLeftToDeleteThatEndedOnOrBeforeThresholdTimestamp(
      workflowEndTimestampThreshold: Timestamp
    )(implicit ec: ExecutionContext): Future[Int] = notImplemented()

    override def getMetadataTableSizeInformation()(implicit
      ec: ExecutionContext
    ): Future[Option[InformationSchemaEntry]] = notImplemented()

    override def getFailedJobsMetadataWithWorkflowId(rootWorkflowId: String)(implicit
      ec: ExecutionContext
    ): Future[Vector[MetadataEntry]] = notImplemented()
  }
}

object WriteMetadataActorSpec {

  val IntermittentException = new Exception("Simulated Database Flakiness Exception") with NoStackTrace

  class BatchSizeCountingWriteMetadataActor(override val batchSize: Int,
                                            override val flushRate: FiniteDuration,
                                            override val serviceRegistryActor: ActorRef,
                                            override val threshold: Int,
                                            val metadataKeysToClean: List[String]
  ) extends WriteMetadataActor(batchSize,
                               flushRate,
                               serviceRegistryActor,
                               threshold,
                               MetadataStatisticsDisabled,
                               metadataKeysToClean
      ) {

    var batchSizes: Vector[Int] = Vector.empty
    var failureCount: Int = 0

    override val recentArrivalThreshold = Some(100.millis)

    override def process(e: NonEmptyVector[MetadataWriteAction]) = {
      batchSizes = batchSizes :+ e.length
      val result = super.process(e)
      result.onComplete {
        case Success(_) => // Don't increment failure count
        case Failure(_) => failureCount += 1
      }

      result
    }
  }

}
