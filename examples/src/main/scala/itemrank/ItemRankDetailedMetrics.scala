package io.prediction.examples.itemrank

import io.prediction.controller.Metrics
import io.prediction.controller.Params

import com.github.nscala_time.time.Imports._
import scala.math.BigDecimal
import breeze.stats.{ mean, meanAndVariance }

/*
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization.{read, write}
import org.json4s.native.Serialization
*/

import java.io.FileOutputStream
import java.io.ObjectOutputStream
import java.io.FileInputStream
import java.io.ObjectInputStream


import scala.io.Source
import java.io.PrintWriter
import java.io.File

case class Stats(val average: Double, val count: Int) extends Serializable

case class DetailedMetricsData(
  val baselineMean: Double,
  val baselineStdev: Double,
  val algoMean: Double,
  val algoStdev: Double,
  val aggregations: Seq[(String, Seq[(String, Stats)])])
  extends Serializable {
 
  override def toString(): String = html.detailed(this).toString
}

// optOutputPath is used for debug purpose. If specified, metrics will output
// the data class to the specified path, and the renderer can generate the html
// independently.
class DetailedMetricsParams(val optOutputPath: Option[String] = None) 
  extends Params {}

class ItemRankDetailedMetrics(params: DetailedMetricsParams)
  extends Metrics[DetailedMetricsParams,
    DataParams, Query, Prediction, Actual,
      MetricUnit, Seq[MetricUnit], DetailedMetricsData] {

  override def computeUnit(query: Query, prediction: Prediction,
    actual: Actual): MetricUnit  = {

    val k = query.items.size
    
    new MetricUnit(
      q = query,
      p = prediction,
      a = actual,
      score = averagePrecisionAtK(k, prediction.items.map(_._1),
        actual.items.toSet),
      baseline = averagePrecisionAtK(k, query.items,
        actual.items.toSet))
  }

  // calcualte MAP at k
  override def computeSet(dataParams: DataParams,
    metricUnits: Seq[MetricUnit]): Seq[MetricUnit] = metricUnits
  
  def calculate(values: Seq[Double]): Stats = {
    Stats(values.sum / values.size, values.size)
  }

  def aggregate(
    units: Seq[MetricUnit],
    groupByFunc: MetricUnit => String): Seq[(String, Stats)] = {
    units
      .groupBy(groupByFunc)
      .mapValues(_.map(_.score))
      .map{ case(k, l) => (k, calculate(l)) }
      .toSeq
      .sortBy(-_._2.average)
  } 


  override def computeMultipleSets(
    input: Seq[(DataParams, Seq[MetricUnit])]): DetailedMetricsData = {

    val algoMeanList = input
      .map(_._2.map(_.score))
      .map(mus => meanAndVariance(mus)._1)  // get mean
    val (algoMean, algoVariance, algoCount) = meanAndVariance(algoMeanList)
    val algoStdev = math.sqrt(algoVariance)
      
    val baselineMeanList = input
      .map(_._2.map(_.baseline))
      .map(mus => meanAndVariance(mus)._1)  // get mean
    val (baselineMean, baselineVariance, baselineCount) = 
      meanAndVariance(baselineMeanList)
    val baselineStdev = math.sqrt(baselineVariance)

    val allUnits: Seq[MetricUnit] = input.flatMap(_._2) 


    val aggregateByActualSize: Seq[(String, Stats)] = allUnits
      .groupBy(_.a.items.size)
      .mapValues(_.map(_.score))
      .map{ case(k, l) => (k.toString, calculate(l)) }
      .toSeq
      .sortBy(-_._2.average)


    val outputData = DetailedMetricsData (
      baselineMean = baselineMean,
      baselineStdev = baselineStdev,
      algoMean = algoMean,
      algoStdev = algoStdev,
      aggregations = Seq(
        ("ByActualSize", aggregate(allUnits, _.a.items.size.toString)),
        ("ByScore", aggregate(allUnits, mu => {
            val d = (mu.score * 10).toInt
            f"[${d / 10.0}%.1f, ${(d + 1)/10.0}%.1f)"
          }))
      )
    )

    // FIXME: Use param opt path
    params.optOutputPath.map { path => 
      ItemRankDetailedMain.save(outputData, path)
      println("Output to: $path")
    }

    outputData
  }

  private def printDouble(d: Double): String = {
    BigDecimal(d).setScale(4, BigDecimal.RoundingMode.HALF_UP).toString
  }

  // metric
  private def averagePrecisionAtK[T](k: Int, p: Seq[T], r: Set[T]): Double = {
    // supposedly the predictedItems.size should match k
    // NOTE: what if predictedItems is less than k? use the avaiable items as k.
    val n = scala.math.min(p.size, k)

    // find if each element in the predictedItems is one of the relevant items
    // if so, map to 1. else map to 0
    // (0, 1, 0, 1, 1, 0, 0)
    val rBin: Seq[Int] = p.take(n).map { x => if (r(x)) 1 else 0 }
    val pAtKNom = rBin.scanLeft(0)(_ + _)
      .drop(1) // drop 1st one which is initial 0
      .zip(rBin)
      .map(t => if (t._2 != 0) t._1.toDouble else 0.0)
    // ( number of hits at this position if hit or 0 if miss )

    val pAtKDenom = 1 to rBin.size
    val pAtK = pAtKNom.zip(pAtKDenom).map { t => t._1 / t._2 }
    val apAtKDenom = scala.math.min(n, r.size)
    if (apAtKDenom == 0) 0 else pAtK.sum / apAtKDenom
  }

}

// Work around for classNotFound error.
class ObjectInputStreamWithCustomClassLoader(
  fileInputStream: FileInputStream
) extends ObjectInputStream(fileInputStream) {
  override def resolveClass(desc: java.io.ObjectStreamClass): Class[_] = {
    try { Class.forName(desc.getName, false, getClass.getClassLoader) }
    catch { case ex: ClassNotFoundException => super.resolveClass(desc) }
  }
}

// Helper for rendering pre-calcuated data.
object ItemRankDetailedMain {
  def save(data: DetailedMetricsData, path: String) {
    val oos = new ObjectOutputStream(new FileOutputStream(path))
    oos.writeObject(data)
    oos.close()
  }

  def load(path: String): DetailedMetricsData = {
    val ois = new ObjectInputStreamWithCustomClassLoader(new FileInputStream(path))
    val obj = ois.readObject().asInstanceOf[DetailedMetricsData]
    ois.close
    return obj
  }

  def render(data: DetailedMetricsData, path: String) {
    val content = html.detailed(data)
    val outputPath = s"${path}.html"
    println("OutputPath: " + outputPath)
    val writer = new PrintWriter(new File(outputPath))
    writer.write(content.toString)
    writer.close()
  }

  def main(args: Array[String]) {
    render(load(args(0)), args(0))
  }
}