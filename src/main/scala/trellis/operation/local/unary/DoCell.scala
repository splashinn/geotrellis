package trellis.operation

import trellis._
import trellis.process._

/**
 * Perform a function on every cell in a raster.
 *
 * For example,
 * <pre>
 * val R = LoadFile(f)
 * val D = DoCell(R, x => x + 3 ) // add 3 to every cell in the raster  
 * </pre>
 */
case class DoCell(r:Op[IntRaster], f:(Int) => Int) extends SimpleUnaryLocal {
  def getCallback = f
}
