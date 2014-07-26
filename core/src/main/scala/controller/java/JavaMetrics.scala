package io.prediction.controller.java

import io.prediction.controller.Params
import io.prediction.core.BaseMetrics

import java.util.{ List => JList }
import java.lang.{ Iterable => JIterable }

import scala.collection.JavaConversions._
import scala.reflect._

/**
 * Base class of metrics.
 *
 * Metrics compare predictions with actual known values and produce numerical
 * comparisons.
 */
abstract class JavaMetrics[MP <: Params, DP, Q, P, A, MU, MR, MMR <: AnyRef]
  extends BaseMetrics[MP, DP, Q, P, A, MU, MR, MMR]()(
    JavaUtils.fakeManifest[MP]) {

  def computeUnitBase(input: (Q, P, A)): MU = {
    computeUnit(input._1, input._2, input._3)
  }

  /**
   * Implement this method to calculate a unit of metrics, comparing a pair of
   * predicted and actual values.
   */
  def computeUnit(query: Q, predicted: P, actual: A): MU

  def computeSetBase(dataParams: DP, metricUnits: Seq[MU]): MR = {
    computeSet(dataParams, metricUnits)
  }

  /**
   * Implement this method to calculate metrics results of an evaluation.
   */
  def computeSet(dataParams: DP, metricUnits: JIterable[MU]): MR

  def computeMultipleSetsBase(input: Seq[(DP, MR)]): MMR = {
    computeMultipleSets(input)
  }

  /**
   * Implement this method to aggregate all metrics results generated by each
   * evaluation's {@link JavaMetrics#computeSet} to produce the final result.
   */
  def computeMultipleSets(input: JIterable[Tuple2[DP, MR]]): MMR
}
