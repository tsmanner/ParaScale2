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
package parabond

import org.apache.log4j.Logger
import casa.MongoDbObject
import parascale.util.getPropertyOrElse
import parabond.util.Constant.{NUM_PORTFOLIOS, PORTF_NUM}
import parabond.util.MongoHelper

package object cluster {
  /**
    * Converts nano-seconds to seconds implicitly.
    * See https://alvinalexander.com/scala/scala-how-to-add-new-methods-to-existing-classes
    * @param dt Time
    */
  class NanoToSecondsCoverter(dt: Double) {
    def this(t: Long) = this(t.toDouble)
    def seconds = dt / 1000000000.0
  }

  // Compiler may complain without this import
  import scala.language.implicitConversions

  // Actual implicit conversions
  implicit def nanoSecondsToSeconds(dt: Long) = new NanoToSecondsCoverter(dt)
  implicit def nanoSecondsToSeconds(dt: Double) = new NanoToSecondsCoverter(dt)

  /**
    * Writes a report to the diagnostic log
    * @param log Logger to use
    * @param analysis Results to report
    */
  def report(log: Logger, analysis: Analysis, checkIds: List[Int]): Unit = {
    // Generate the detailed output report, if needed
    val details = getPropertyOrElse("details",false)

    val results = analysis.results

    if(details) {
      log.info("%6s %10.10s %-5s %-2s".format("PortId","Price","Bonds","dt"))

      val t0 = analysis.t0

      results.foreach { output =>
        val id = output.portfId

        val dt = (output.result.t1 - output.result.t0) seconds

        val bondCount = output.result.bondCount

        val price = output.result.value

        log.info("%6d %10.2f %5d %6.4f %12d %12d".format(id, price, bondCount, dt, output.result.t1 - t0, output.result.t0 - t0))
      }
    }

    val dt1 = results.foldLeft(0.0) { (sum, output) =>
      sum + (output.result.t1 - output.result.t0)
    } seconds


    val dtN = (analysis.t1 - analysis.t0) seconds

    val speedup = dt1 / dtN

    val numCores = Runtime.getRuntime().availableProcessors()

    val e = speedup / numCores

    val n = getPropertyOrElse("n", PORTF_NUM)

    log.info("n: %d  T1: %7.4f  TN: %7.4f  cores: %d  R: %5.2f  e: %5.2f ".format(n, dt1,dtN,numCores,speedup,e))
//    log.info("DONE! %d %7.4f %7.4f".format(n, dt1, dtN))

    // Check if check portfolios were priced
    val misses = check(checkIds)

    log.info("Miss count: "+misses.length)
    if(misses.length != 0) {
      misses.foreach { portfId =>
        log.info("missed portfid = " + portfId)
      }
    }
  }

  /** Samples 5% of portfolios */
  val CHECK_RATE = 0.05

  /** A portfolio with a negative has not been priced */
  val CHECK_VALUE = -1

  /**
    * Resets the check portfolios to the "not priced" state.
    * @param n Number of portfolios
    * @param begin Offset
    * @param seed Random seed
    * @param size Number of portfolios in the universe where n <= size
    * @return List of portfolios to check.
    */
  def checkReset(n: Int, seed: Int=0, size: Int=NUM_PORTFOLIOS): List[Int] = {
    import scala.util.Random

    Random.setSeed(n)
    val indices = Random.shuffle((0 until n).toList)

    val numChecks = 1 max (n * CHECK_RATE).toInt

    Random.setSeed(seed)
    val deck = Random.shuffle((0 until size).toList)

    val checkIds = (0 until numChecks).foldLeft(List[Int]()) { (checkIds, k) =>
      val index = indices(k)

      val portfId = deck(index) + 1

      MongoHelper.updatePrice(portfId,CHECK_VALUE)

      portfId :: checkIds
    }

    checkIds
  }

  /**
    * Resets the check portfolios to the "not priced" state.
    * @param n Number of portfolios
    * @param begin Offset
    * @param seed Random seed
    * @param size Number of portfolios in the universe where n <= size
    * @return List of portfolios to check.
    */
  def checkReset2(n: Int, seed: Int=0, size: Int=NUM_PORTFOLIOS): List[Int] = {
    import scala.util.Random

    Random.setSeed(seed)
    val deck = Random.shuffle((0 until size).toList)

    val checkIds = (0 until n).foldLeft(List[Int]()) { (checkIds, k) =>
      val lottery = Random.nextDouble

      if(lottery <= CHECK_RATE) {
        val portfId = deck(k) + 1

        MongoHelper.updatePrice(portfId, CHECK_VALUE)

        portfId :: checkIds
      }
      else
        checkIds
    }

    checkIds
  }

  /**
    * Checks the portfolios in the database against those we expect to be priced.
    * @param portfIds Check portfolio ids
    * @return List of unpriced portfolios
    */
  def check(portfIds: List[Int]): List[Int] = {
    val misses = portfIds.foldLeft(List[Int]()) { (misses, portfId) =>
      val portfsQuery = MongoDbObject("id" -> portfId)

      val portfsCursor = MongoHelper.portfolioCollection.find(portfsQuery)

      val price = MongoHelper.asDouble(portfsCursor,"price")

      if(price == CHECK_VALUE)
        portfId :: misses
      else
        misses
    }

    misses
  }
}
