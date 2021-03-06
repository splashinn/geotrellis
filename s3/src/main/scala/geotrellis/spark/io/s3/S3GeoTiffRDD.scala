/*
 * Copyright 2016 Azavea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.spark.io.s3

import java.nio.ByteBuffer

import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.raster.io.geotiff.tags.TiffTags
import geotrellis.spark._
import geotrellis.spark.io.RasterReader
import geotrellis.spark.io.s3.util.S3RangeReader
import geotrellis.util.StreamingByteReader
import geotrellis.vector._
import org.apache.hadoop.conf.Configuration

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import com.amazonaws.services.s3.model._

/**
 * The S3GeoTiffRDD object allows for the creation of whole or windowed RDD[(K, V)]s from files on S3.
 */
object S3GeoTiffRDD {
  final val GEOTIFF_TIME_TAG_DEFAULT = "TIFFTAG_DATETIME"
  final val GEOTIFF_TIME_FORMAT_DEFAULT = "yyyy:MM:dd HH:mm:ss"

  /**
    * This case class contains the various parameters one can set when reading RDDs from S3 using Spark.
    *
    * @param tiffExtensions     Read all file with an extension contained in the given list.
    * @param crs            Override CRS of the input files. If [[None]], the reader will use the file's original CRS.
    * @param timeTag        Name of tiff tag containing the timestamp for the tile.
    * @param timeFormat     Pattern for [[java.time.format.DateTimeFormatter]] to parse timeTag.
    * @param maxTileSize    Maximum allowed size of each tiles in output RDD.
    *                       May result in a one input GeoTiff being split amongst multiple records if it exceeds this size.
    *                       If no maximum tile size is specific, then each file file is read fully.
    * @param numPartitions  How many partitions Spark should create when it repartitions the data.
    * @param partitionBytes Desired partition size in bytes, at least one item per partition will be assigned
    * @param chunkSize      How many bytes should be read in at a time.
    * @param getS3Client    A function to instantiate an S3Client. Must be serializable.
    */
  case class Options(
    tiffExtensions: Seq[String] = Seq(".tif", ".TIF", ".tiff", ".TIFF"),
    crs: Option[CRS] = None,
    timeTag: String = GEOTIFF_TIME_TAG_DEFAULT,
    timeFormat: String = GEOTIFF_TIME_FORMAT_DEFAULT,
    maxTileSize: Option[Int] = None,
    numPartitions: Option[Int] = None,
    partitionBytes: Option[Long] = None,
    chunkSize: Option[Int] = None,
    getS3Client: () => S3Client = () => S3Client.DEFAULT
  ) extends RasterReader.Options

  object Options {
    def DEFAULT = Options()
  }

  /**
   * Create Configuration for [[S3InputFormat]] based on parameters and options.
   *
   * @param bucket   Name of the bucket on S3 where the files are kept.
   * @param prefix   Prefix of all of the keys on S3 that are to be read in.
   * @param options  An instance of [[Options]] that contains any user defined or default settings.
   */
  private def configuration(bucket: String, prefix: String, options: S3GeoTiffRDD.Options)(implicit sc: SparkContext): Configuration = {
    val conf =sc.hadoopConfiguration
    S3InputFormat.setBucket(conf, bucket)
    S3InputFormat.setPrefix(conf, prefix)
    S3InputFormat.setExtensions(conf, options.tiffExtensions)
    S3InputFormat.setCreateS3Client(conf, options.getS3Client)
    options.numPartitions.foreach{ n => S3InputFormat.setPartitionCount(conf, n) }
    options.partitionBytes.foreach{ n => S3InputFormat.setPartitionBytes(conf, n) }
    conf
  }

  /**
    * Creates a RDD[(K, V)] whose K and V  on the type of the GeoTiff that is going to be read in.
    *
    * @param bucket   Name of the bucket on S3 where the files are kept.
    * @param prefix   Prefix of all of the keys on S3 that are to be read in.
    * @param options  An instance of [[Options]] that contains any user defined or default settings.
    */
  def apply[K, V](bucket: String, prefix: String, options: Options = Options.DEFAULT)
    (implicit sc: SparkContext, rr: RasterReader[Options, (K, V)]): RDD[(K, V)] = {

    val conf = configuration(bucket, prefix, options)

    options.maxTileSize match {
      case Some(tileSize) =>
        val objectRequestsToDimensions: RDD[(GetObjectRequest, (Int, Int))] =
          sc.newAPIHadoopRDD(
            conf,
            classOf[TiffTagsS3InputFormat],
            classOf[GetObjectRequest],
            classOf[TiffTags]
          ).mapValues { tiffTags => (tiffTags.cols, tiffTags.rows) }

        apply[K, V](objectRequestsToDimensions, options)
      case None =>
        sc.newAPIHadoopRDD(
          conf,
          classOf[BytesS3InputFormat],
          classOf[String],
          classOf[Array[Byte]]
        ).mapPartitions(
          _.map { case (_, bytes) => rr.readFully(ByteBuffer.wrap(bytes), options) },
          preservesPartitioning = true
        )

    }
  }

  /**
    * Creates a RDD[(K, V)] whose K and V depends on the type of the GeoTiff that is going to be read in.
    *
    * @param objectRequestsToDimensions A RDD of GetObjectRequest of a given GeoTiff and its cols and rows as a (Int, Int).
    * @param options An instance of [[Options]] that contains any user defined or default settings.
    */
  def apply[K, V](objectRequestsToDimensions: RDD[(GetObjectRequest, (Int, Int))], options: Options)
    (implicit rr: RasterReader[Options, (K, V)]): RDD[(K, V)] = {

    val windows =
      objectRequestsToDimensions
        .flatMap { case (objectRequest, (cols, rows)) =>
          RasterReader.listWindows(cols, rows, options.maxTileSize).map((objectRequest, _))
        }

    // Windowed reading may have produced unbalanced partitions due to files of differing size
    val repartitioned =
      options.numPartitions match {
        case Some(p) => windows.repartition(p)
        case None => windows
      }

    repartitioned.map { case (objectRequest: GetObjectRequest, pixelWindow: GridBounds) =>
      val reader = options.chunkSize match {
        case Some(chunkSize) =>
          StreamingByteReader(S3RangeReader(objectRequest, options.getS3Client()), chunkSize)
        case None =>
          StreamingByteReader(S3RangeReader(objectRequest, options.getS3Client()))
      }

      rr.readWindow(reader, pixelWindow, options)
    }
  }

  /**
    * Creates RDD that will read all GeoTiffs in the given bucket and prefix as singleband GeoTiffs.
    * If a GeoTiff contains multiple bands, only the first will be read.
    *
    * @param bucket Name of the bucket on S3 where the files are kept.
    * @param prefix Prefix of all of the keys on S3 that are to be read in.
    */
  def spatial(bucket: String, prefix: String)(implicit sc: SparkContext): RDD[(ProjectedExtent, Tile)] =
    spatial(bucket, prefix, Options.DEFAULT)

  /**
    * Creates RDD that will read all GeoTiffs in the given bucket and prefix as singleband tiles.
    * If a GeoTiff contains multiple bands, only the first will be read.
    *
    * @param bucket   Name of the bucket on S3 where the files are kept.
    * @param prefix   Prefix of all of the keys on S3 that are to be read in.
    * @param options  An instance of [[Options]] that contains any user defined or default settings.
    */
  def spatial(bucket: String, prefix: String, options: Options)(implicit sc: SparkContext): RDD[(ProjectedExtent, Tile)] =
    apply[ProjectedExtent, Tile](bucket, prefix, options)

  /**
    * Creates RDD that will read all GeoTiffs in the given bucket and prefix as multiband tiles.
    *
    * @param bucket   Name of the bucket on S3 where the files are kept.
    * @param prefix   Prefix of all of the keys on S3 that are to be read in.
    */
  def spatialMultiband(bucket: String, prefix: String)(implicit sc: SparkContext): RDD[(ProjectedExtent, MultibandTile)] =
    spatialMultiband(bucket, prefix, Options.DEFAULT)

  /**
    * Creates RDD that will read all GeoTiffs in the given bucket and prefix as multiband tiles.
    *
    * @param bucket   Name of the bucket on S3 where the files are kept.
    * @param prefix   Prefix of all of the keys on S3 that are to be read in.
    * @param options  An instance of [[Options]] that contains any user defined or default settings.
    */
  def spatialMultiband(bucket: String, prefix: String, options: Options)(implicit sc: SparkContext): RDD[(ProjectedExtent, MultibandTile)] =
    apply[ProjectedExtent, MultibandTile](bucket, prefix, options)

  /**
    * Creates RDD that will read all GeoTiffs in the given bucket and prefix as singleband tiles.
    * Will parse a timestamp from the default tiff tags to associate with each file.
    *
    * @param bucket   Name of the bucket on S3 where the files are kept.
    * @param prefix   Prefix of all of the keys on S3 that are to be read in.
    */
  def temporal(bucket: String, prefix: String)(implicit sc: SparkContext): RDD[(TemporalProjectedExtent, Tile)] =
    temporal(bucket, prefix, Options.DEFAULT)

  /**
    * Creates RDD that will read all GeoTiffs in the given bucket and prefix as singleband tiles.
    * Will parse a timestamp from a tiff tags specified in options to associate with each tile.
    *
    * @param bucket   Name of the bucket on S3 where the files are kept.
    * @param prefix   Prefix of all of the keys on S3 that are to be read in.
    * @param options  Options for the reading process. Including the timestamp tiff tag and its pattern.
    */
  def temporal(bucket: String, prefix: String, options: Options)(implicit sc: SparkContext): RDD[(TemporalProjectedExtent, Tile)] =
    apply[TemporalProjectedExtent, Tile](bucket, prefix, options)

  /**
    * Creates RDD that will read all GeoTiffs in the given bucket and prefix as multiband tiles.
    * Will parse a timestamp from a tiff tags specified in options to associate with each tile.
    *
    * @param bucket   Name of the bucket on S3 where the files are kept.
    * @param prefix   Prefix of all of the keys on S3 that are to be read in.
    */
  def temporalMultiband(bucket: String, prefix: String)(implicit sc: SparkContext): RDD[(TemporalProjectedExtent, MultibandTile)] =
    temporalMultiband(bucket, prefix, Options.DEFAULT)

  /**
    * Creates RDD that will read all GeoTiffs in the given bucket and prefix as multiband tiles.
    * Will parse a timestamp from a tiff tags specified in options to associate with each tile.
    *
    * @param bucket   Name of the bucket on S3 where the files are kept.
    * @param prefix   Prefix of all of the keys on S3 that are to be read in.
    * @param options  Options for the reading process. Including the timestamp tiff tag and its pattern.
    */
  def temporalMultiband(bucket: String, prefix: String, options: Options)(implicit sc: SparkContext): RDD[(TemporalProjectedExtent, MultibandTile)] =
    apply[TemporalProjectedExtent, MultibandTile](bucket, prefix, options)
}
