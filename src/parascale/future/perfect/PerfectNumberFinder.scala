/*
 Copyright (c) Ron Coleman

 Permission is hereby granted, free of charge, to any person obtaining
 a copy of this software and associated documentation files (the
 "Software"), to deal in the Software without restriction, including
 without limitation the rights to use, copy, modify, merge, publish,
 distribute, sublicense, and/or sell copies of the Software, and to
 permit persons to whom the Software is furnished to do so, subject to
 the following conditions:

 The above copyright notice and this permission notice shall be
 included in all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package parascale.future.perfect

/**
  * Finds the perfect numbers from a candidate list.
  */
object PerfectNumberFinder extends App {
  (0 until candidates.length).foreach { index =>
    val num = candidates(index)
    println(num + " is perfect? "+ ask(_isPerfect,num))
  }

  /**
    * Retruns true if the candidate is perfect.
    * @param candidate Candidate number
    * @return True if candidate is perfect, false otherwise
    */
  def isPerfect(candidate: Long) = 2 * candidate == sumOfFactors(candidate)

  /**
    * Retruns true if the candidate is perfect.
    * @param candidate Candidate number
    * @return True if candidate is perfect, false otherwise
    */
  def _isPerfect(candidate: Long) = 2 * candidate == _sumOfFactors(candidate)
}
