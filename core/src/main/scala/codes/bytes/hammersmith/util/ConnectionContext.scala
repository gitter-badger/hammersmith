/**
 * Copyright (c) 2011-2015 Brendan McAdams <http://bytes.codes>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package codes.bytes.hammersmith
package util


import codes.bytes.hammersmith.wire._
import java.io.OutputStream


trait ConnectionContext {
  /** Maximum size of BSON this server allows. */
  protected var _maxBSON: Int =  MongoMessage.DefaultMaxBSONObjectSize
  protected var _isMaster: Boolean = false

  def maxBSONObjectSize: Int = _maxBSON
  protected[hammersmith] def maxBSONObjectSize_=(size: Int) {
    _maxBSON = size
  }

  def isMaster: Boolean = _isMaster
  protected[hammersmith] def isMaster_=(is: Boolean) {
    _isMaster = is
  }

  def connected_?(): Boolean

  def newOutputStream: OutputStream

  def write(msg: AnyRef)

  def close()
}

