/*
 Copyright 2013 Twitter, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.twitter.summingbird.batch.state
import java.util.concurrent.atomic.AtomicBoolean

import com.twitter.algebird._
import com.twitter.summingbird.batch._

/**
 * To create an implemetation of CheckpointState you need first define a sub-trait of CheckpointStore, then mix it in with CheckpointState,
 * see {@link com.twitter.summingbird.batch.state.HDFSState} for an example
 *
 * Sub-traits of CheckpointStore should be responsible for getting the startBatch by checking the checkpoints of
 * previous batch run and getting the endBatch by number of batches the clients asks to run.
 * It implements cake pattern with CheckpointState. Checkpointstate must mixes in an implementation of the CheckpointStore.
 * The CheckpointStore should provide concrete implementation of how to read previous batch and checkpointing current batch
 * Type T is the token of each batch run created by startBatch(), the token is then provided back to checkPoint store for checkpointing Success or Failure.
 * @author Tianshuo Deng
 */
trait CheckpointStore[T] {

  def batcher: Batcher

  // should get startBatch by checking the previous batch run, it's used to calculate requested time interval for the job
  def startBatch: InclusiveLower[BatchID]

  // usually it's startBatch +  numBatchesToRun, it's used to calculate requested time interval for the job
  def endBatch: ExclusiveUpper[BatchID]

  // is called when the batch is started. notice the intersection is not necessarily the same as [startBatch..endBatch),
  // since there could be only part of the data available given a requested time range.
  // It returns a batch token of typ T, which will be provided when the job is completed or failed for checkpointing.
  def checkpointBatchStart(intersection: Intersection[InclusiveLower, ExclusiveUpper, Timestamp]): T

  // is called when the batches are finished successfully
  def checkpointSuccessfulRun(batchToken: T)

  // is called when the scalding job failed
  def checkpointFailure(batchToken: T, err: Throwable)

  // is called when the planning is failed to start the scalding job
  def checkpointPlanFailure(err: Throwable)
}

/**
 * State machine for checkpointed states. It creates the requested time interval by asking checkpoint store for startBatch and endBatch.
 * After flow planner minifies the time interval(by checking data available), it decides to accept the interval by making sure there is no
 * hole in between the start time of the interval and the end time of previous batch run.
 * It requires a CheckpointStore to be mixed-in.
 */
trait CheckpointState[T] extends WaitingState[Interval[Timestamp]] {
  this: CheckpointStore[T] =>
  //guard for concurrent modification
  private val hasStarted = new AtomicBoolean(false)

  override def begin: PrepareState[Interval[Timestamp]] = new CheckpointPrepareState(this)

  class CheckpointPrepareState(val waitingState: CheckpointState[T]) extends PrepareState[Interval[Timestamp]] {

    def requested: Interval[Timestamp] = Intersection(startBatch, endBatch).mapNonDecreasing(batcher.earliestTimeOf(_))

    val earliestTimestamp = batcher.earliestTimeOf(startBatch.lower)

    def matchesCurrentBatchStart(low: Option[Timestamp]): Boolean = {
      low.map(Equiv[Timestamp].equiv(_, earliestTimestamp)).getOrElse(false)
    }

    /**
     * only accept interval that aligns size of the batch interval
     * must start from (current) startBatch
     */
    override def willAccept(available: Interval[Timestamp]) =
      available match {
        case intersection @ Intersection(low, high) if (alignedToBatchBoundaries(low, high) &&
          matchesCurrentBatchStart(low.least) && // no holes in the middle
          hasStarted.compareAndSet(false, true)) =>
          intersection.toLeftClosedRightOpen match {
            case Some(leftClosedRightOpenIntersection) => {
              val batchToken: T = checkpointBatchStart(leftClosedRightOpenIntersection)
              Right(new CheckpointRunningState(this, intersection, hasStarted, batchToken))
            }
            case _ => Left(waitingState)
          }
        case _ => Left(waitingState)
      }

    override def fail(err: Throwable) = {
      checkpointPlanFailure(err)
      throw err
    }
  }

  class CheckpointRunningState(prepareState: CheckpointPrepareState, succeedPart: Interval.GenIntersection[Timestamp], isRunning: AtomicBoolean, val batchToken: T) extends RunningState[Interval[Timestamp]] {
    private def setStopped = assert(
      isRunning.compareAndSet(true, false),
      "Concurrent modification of HDFSState!"
    )

    /**
     * On success, checkpoint successful batches
     */
    override def succeed = {
      setStopped
      checkpointSuccessfulRun(batchToken)
      prepareState.waitingState // go back to waiting state
    }

    override def fail(err: Throwable) = {
      checkpointFailure(batchToken, err)
      prepareState.waitingState
    }
  }

  /**
   * Returns true if each timestamp represents the first
   * millisecond of a batch.
   */
  def alignedToBatchBoundaries(low: Lower[Timestamp],
    high: Upper[Timestamp]): Boolean = {
    (for {
      lowerBound <- low.least
      upperBound <- high.greatest
    } yield batcher.isLowerBatchEdge(lowerBound) && batcher.isLowerBatchEdge(upperBound.next)).getOrElse(false)
  }
}