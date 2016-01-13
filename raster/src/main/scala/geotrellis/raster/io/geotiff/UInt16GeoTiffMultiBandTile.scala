package geotrellis.raster.io.geotiff

import geotrellis.raster._
import geotrellis.raster.io.geotiff.compression._

import java.nio.ByteBuffer

class UInt16GeoTiffMultiBandTile(
  compressedBytes: Array[Array[Byte]],
  decompressor: Decompressor,
  segmentLayout: GeoTiffSegmentLayout,
  compression: Compression,
  bandCount: Int,
  hasPixelInterleave: Boolean,
  val cellType: CellType
) extends GeoTiffMultiBandTile(compressedBytes, decompressor, segmentLayout, compression, bandCount, hasPixelInterleave)
    with UInt16GeoTiffSegmentCollection {

  val noDataValue = cellType match {
    case _: RawCellType => None
    case _: ConstantNoDataCellType => Some(Int.MinValue)
    case UserDefinedNoDataCellType(nd) => Some(nd)
  }

  protected def createSegmentCombiner(targetSize: Int): SegmentCombiner =
    new SegmentCombiner {
      private val arr = Array.ofDim[Int](targetSize)

      def set(targetIndex: Int, v: Int): Unit = {
        arr(targetIndex) = v
      }

      def setDouble(targetIndex: Int, v: Double): Unit = {
        arr(targetIndex) = d2i(v)
      }

      def getBytes(): Array[Byte] = {
        val result = new Array[Byte](targetSize * IntConstantNoDataCellType.bytes)
        val bytebuff = ByteBuffer.wrap(result)
        bytebuff.asIntBuffer.put(arr)
        result
      }
    }
}
