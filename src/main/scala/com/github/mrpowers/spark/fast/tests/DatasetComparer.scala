package com.github.mrpowers.spark.fast.tests

import com.github.mrpowers.spark.fast.tests.DatasetComparerLike.naiveEquality
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.functions._

import scala.reflect.ClassTag

case class DatasetSchemaMismatch(smth: String) extends Exception(smth)
case class DatasetContentMismatch(smth: String) extends Exception(smth)

object DatasetComparerLike {
  def naiveEquality[T](o1: T, o2: T): Boolean = {
    o1.equals(o2)
  }
}

trait DatasetComparer {

  private def schemaMismatchMessage[T](actualDS: Dataset[T], expectedDS: Dataset[T]): String = {
    s"""
Actual Schema:
${actualDS.schema}
Expected Schema:
${expectedDS.schema}
"""
  }

  private def contentMismatchMessage[T](actualDS: Dataset[T], expectedDS: Dataset[T]): String = {
    s"""
Actual DataFrame Content:
${DataFramePrettyPrint.showString(actualDS.toDF(), 10)}
Expected DataFrame Content:
${DataFramePrettyPrint.showString(expectedDS.toDF(), 10)}
"""
  }

  private def countMismatchMessage(actualCount: Long, expectedCount: Long): String = {
    s"""
Actual DataFrame Row Count: '${actualCount}'
Expected DataFrame Row Count: '${expectedCount}'
"""
  }

  /**
   * Raises an error unless `actualDS` and `expectedDS` are equal
   */
  def assertSmallDatasetEquality[T](
    actualDS: Dataset[T],
    expectedDS: Dataset[T],
    orderedComparison: Boolean = true
  ): Unit = {
    if (!actualDS.schema.equals(expectedDS.schema)) {
      throw DatasetSchemaMismatch(schemaMismatchMessage(actualDS, expectedDS))
    }
    if (orderedComparison) {
      if (!actualDS.collect().sameElements(expectedDS.collect())) {
        throw DatasetContentMismatch(contentMismatchMessage(actualDS, expectedDS))
      }
    } else {
      val actualSortedDF = defaultSortDataset(actualDS)
      val expectedSortedDF = defaultSortDataset(expectedDS)
      if (!actualSortedDF.collect().sameElements(expectedSortedDF.collect())) {
        throw DatasetContentMismatch(contentMismatchMessage(actualSortedDF, expectedSortedDF))
      }
    }
  }

  def defaultSortDataset[T](ds: Dataset[T]): Dataset[T] = {
    val colNames = ds.columns.sorted
    val cols = colNames.map(col)
    ds.sort(cols: _*)
  }

  /**
   * Raises an error unless `actualDS` and `expectedDS` are equal
   */
  def assertLargeDatasetEquality[T: ClassTag](
    actualDS: Dataset[T],
    expectedDS: Dataset[T],
    equals: (T, T) => Boolean = naiveEquality _
  ): Unit = {
    if (!actualDS.schema.equals(expectedDS.schema)) {
      throw DatasetSchemaMismatch(schemaMismatchMessage(actualDS, expectedDS))
    }
    try {
      actualDS.rdd.cache
      expectedDS.rdd.cache

      val actualCount = actualDS.rdd.count
      val expectedCount = expectedDS.rdd.count
      if (actualCount != expectedCount) {
        throw DatasetContentMismatch(countMismatchMessage(actualCount, expectedCount))
      }

      val expectedIndexValue: RDD[(Long, T)] = zipWithIndex(actualDS.rdd)
      val resultIndexValue: RDD[(Long, T)] = zipWithIndex(expectedDS.rdd)
      val unequalRDD = expectedIndexValue
        .join(resultIndexValue)
        .filter {
          case (idx, (o1, o2)) =>
            !equals(o1, o2)
        }
      val maxUnequalRowsToShow = 10
      if (!unequalRDD.isEmpty()) {
        throw DatasetContentMismatch(countMismatchMessage(actualCount, expectedCount))
      }
      unequalRDD.take(maxUnequalRowsToShow)

    } finally {
      actualDS.rdd.unpersist()
      expectedDS.rdd.unpersist()
    }
  }

  /**
   * Zip RDD's with precise indexes. This is used so we can join two DataFrame's
   * Rows together regardless of if the source is different but still compare based on
   * the order.
   */
  private def zipWithIndex[T](rdd: RDD[T]): RDD[(Long, T)] = {
    rdd.zipWithIndex().map {
      case (row, idx) =>
        (idx, row)
    }
  }

}

