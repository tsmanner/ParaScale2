/*
 * Copyright (c) Ron Coleman
 * See CONTRIBUTORS.TXT for a full list of copyright holders.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the Scaly Project nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE DEVELOPERS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package parabond.cluster

import org.apache.log4j.Logger
import parabond.util.Constant.PORTF_NUM
import casa.MongoDbObject
import parabond.util.MongoHelper.{bondCollection, mongo}
import parabond.entry.SimpleBond
import parabond.util.{Helper, Job, MongoHelper, Result}
import parabond.util.Constant.NUM_PORTFOLIOS
import parabond.value.SimpleBondValuator
import parascale.util.getPropertyOrElse
import scala.util.Random
import scala.collection.parallel.CollectionConverters._

/**
  * Runs a memory-bound node which retrieves the portfolios in random order, loads them all into memory
  * then prices as a parallel collection.
  */
object MemoryBoundNode extends App {
  val LOG = Logger.getLogger(getClass)

  val seed = getPropertyOrElse("seed",0)
  val size = getPropertyOrElse("size", NUM_PORTFOLIOS)
  val n = getPropertyOrElse("n", PORTF_NUM)
  val begin = getPropertyOrElse("begin", 0)

  val checkIds = checkReset(n)

  val analysis = new MemoryBoundNode analyze(Partition(n=n, begin=begin))

  report(LOG, analysis, checkIds)
}

/**
  * Prices one portfolio per core by first loading all the bonds of a portfolio into memory.
  */
class MemoryBoundNode extends Node {
  def analyze(partition: Partition): Analysis = {
    // Clock in
    val t0 = System.nanoTime

    // Seed must be same for every host in cluster as this establishes
    val ran = new Random(partition.seed)

    // Shuffled deck of portfolios -- random sample without replacement
    val sample = (0 until partition.size).toList
    val deck = ran.shuffle(sample)

    // Number of portfolios to analyze
    // Start and end (inclusive) in analysis sequence
    val begin = partition.begin
    val end = begin + partition.n

    // Indices in the deck we're working on
    // Note: k+1 since portf ids are 1-based
    val portfIds = for(k <- begin until end) yield Job(deck(k) + 1)

    // Get the proper collection depending on whether we're measuring T1 or TN
    val jobs = if(partition.para) loadPortfsParallel(portfIds.toList).par else loadPortfsSequential(portfIds)

    // Run the analysis
    val results = jobs.map(price)

    // Clock out
    val t1 = System.nanoTime

    Analysis(results.toList, t0, t1)
  }

  def price(work: Job): Job = {
    // Value each bond in the portfolio
    val t0 = System.nanoTime

    // We already have to bonds in memory.
    val value = work.bonds.foldLeft(0.0) { (sum, bond) =>
      // Price the bond
      val valuator = new SimpleBondValuator(bond, Helper.yieldCurve)

      val price = valuator.price

      // Updated portfolio price so far
      sum + price
    }

    MongoHelper.updatePrice(work.portfId,value)

    val t1 = System.nanoTime

    // Return the result for this portfolio
    Job(work.portfId,null,Result(work.portfId,value,work.bonds.size,t0,t1))
  }

  /**
    * Loads portfolios using and their bonds into memory serially.
    */
  def loadPortfsSequential(tasks: Seq[Job]) : Seq[Job] = {
    // Connect to the portfolio collection
    val portfsCollecton = mongo("Portfolios")

    val portfIdToBondsPairs = tasks.foldLeft(List[Job] ()) { (list, input) =>
      // Select a portfolio
      val portfId = input.portfId

      // Retrieve the portfolio
      val portfsQuery = MongoDbObject("id" -> portfId)

      val portfsCursor = portfsCollecton.find(portfsQuery)

      // Get the bonds in the portfolio
      val bondIds = MongoHelper.asList(portfsCursor, "instruments")

      val bonds = bondIds.foldLeft(List[SimpleBond]()) { (bonds, id) =>
        // Get the bond from the bond collection
        val bondQuery = MongoDbObject("id" -> id)

        val bondCursor = bondCollection.find(bondQuery)

        val bond = MongoHelper.asBond(bondCursor)

        // The price into the aggregate sum
        bonds ++ List(bond)
      }

      Job(portfId,bonds,null) :: list
    }

    portfIdToBondsPairs
  }

  /**
    * Parallel loads portfolios using and their bonds into memory using futures.
    */
  def loadPortfsParallel(tasks: List[Job]) : List[Job] = {
    import scala.concurrent.{Await, Future}
    import scala.concurrent.ExecutionContext.Implicits.global

    val futures = for(input <- tasks) yield Future {
      // Select a portfolio
      val portfId = input.portfId

      // Fetch its bonds
      MongoHelper.fetchBonds(portfId)
    }

    val list = futures.foldLeft(List[Job]()) { (list, future) =>
      import scala.concurrent.duration.DurationInt
      import parabond.util.Constant._
      val result = Await.result(future, MAX_WAIT_TIME.seconds)

      // Use null because we don't have result yet -- completed when we analyze the portfolio
      Job(result.portfId, result.bonds, null) :: list
    }

    list
  }
}
