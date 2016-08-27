/*
 * Copyright (c) 2014 DigitalGlobe.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.spark

import geotrellis.spark.io.kryo.GeowaveKryoRegistrator

import org.apache.spark.SparkConf
import org.scalatest._


trait GeowaveTestEnvironment extends TestEnvironment { self: Suite =>
  override def setKryoRegistrator(conf: SparkConf) = {
    conf
      .set("spark.kryo.registrator", "geotrellis.spark.io.kryo.GeowaveKryoRegistrator")
      .set("spark.kryo.registrationRequired", "false")
  }
}
