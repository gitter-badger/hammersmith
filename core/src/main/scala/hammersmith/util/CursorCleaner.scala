/**
 * Copyright (c) 2011-2013 Brendan W. McAdams <http://evilmonkeylabs.com>
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

package hammersmith
package util

import scala.ref.WeakReference
import hammersmith.bson.util.Logging
import scala.collection.mutable.{ WeakHashMap, HashSet }

/**
 * Based on com.twitter.util.ReferenceCountedTimer
 * Tracks if any active channels are held and turns on / off the cleaner as needed
 */
protected[hammersmith] class CursorCleaningTimer(val period: Long = 5000) extends Logging {
  private[this] val connections = WeakHashMap.empty[MongoConnection, Boolean]
  private[this] var underlying: Timer = null
  private[this] val factory = () ⇒ new JavaTimer(true)

  def acquire(conn: MongoConnection) = synchronized {
    connections += conn -> true
    if (!connections.isEmpty) {
      if (underlying == null) {
        underlying = factory()
        scheduleCleanup()
      }
    }
  }

  def stop(conn: MongoConnection) = synchronized {
    connections -= conn
    if (connections.isEmpty) {
      log.info("Connections empty.  Stopping scheduler thread.")
      underlying.stop()
      underlying = null
    } else {
      log.trace("Connections not empty: %s", connections)
    }
  }

  protected[hammersmith] def scheduleCleanup() = underlying.schedule(period) {
    log.trace("Cleanup; holding connections: %s", connections)
    MongoConnection.cleanup()
  }

}
