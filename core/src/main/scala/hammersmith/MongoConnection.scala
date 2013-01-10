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

import java.net.InetSocketAddress
import hammersmith.bson._
import hammersmith.bson.collection._
import java.nio.ByteOrder
import hammersmith.wire._
import scala.collection.JavaConversions._
import hammersmith.futures._
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ ConcurrentHashMap, Executors, SynchronousQueue, ThreadPoolExecutor, TimeUnit }
import scala.collection.mutable.{ ConcurrentMap, WeakHashMap }
import hammersmith.util.{ ConcurrentQueue, CursorCleaningTimer }
import org.bson.types.ObjectId
import hammersmith.bson.util.Logging
import hammersmith.util._
import hammersmith.netty.NettyConnection

/**
 * Base trait for all connections, be it direct, replica set, etc
 *
 * This contains common code for any type of connection.
 *
 * NOTE: Connection instances are instances of a *POOL*, always.
 *
 * @since 0.1
 */
abstract class MongoConnection extends Logging {
  type E

  MongoConnection.cleaningTimer.acquire(this)
  
  connect

  def initialize: ConnectionContext

  def eventLoop: Option[E] = None

  // Default, run at object init.
  def connect {
    initialize
    checkMaster(true, true)
  }
  
  def defaultEventLoop: E

  protected implicit def context = initialize


  /**
   * Utility method to pull back a number of pieces of information
   * including maxBSONObjectSize, and sort of serves as a verification
   * of a live connection.
   *
   * @param force Forces the isMaster call to run regardless of cached status
   * @param requireMaster Requires a master to be found or throws an Exception
   * @throws MongoException
   */
  def checkMaster(force: Boolean = false, requireMaster: Boolean = true) {
    if (!connected_? || force) {
      log.debug("Checking Master Status... (BSON Size: %d Force? %s)", context.maxBSONObjectSize, force)
      val gotIsMaster = new AtomicBoolean(false)
      val qMsg = MongoConnection.createCommand("admin", Document("isMaster" -> 1))
      MongoConnection.send(qMsg, SimpleRequestFutures.command((doc: Document) ⇒ {
        log.debug("Got a result from command: %s", doc)
        context.isMaster = doc.getAsOrElse[Boolean]("ismaster", false)
        context.maxBSONObjectSize = doc.getAsOrElse[Int]("maxBsonObjectSize", MongoMessage.DefaultMaxBSONObjectSize)
        gotIsMaster.set(true)
        if (requireMaster && !context.isMaster) throw new Exception("Couldn't find a master.") else _connectedState(connected = true)
        //handler.maxBSONObjectSize = maxBSONObjectSize
        log.debug("Server Status read.  Is Master? %s MaxBSONSize: %s", context.isMaster, context.maxBSONObjectSize)
      }), _overrideLiveCheck = true)
    } else {
      log.debug("Already have cached master status. Skipping.")
    }
  }

  /**
   * WARNING: You *must* use an ordered list or commands won't work
   */
  protected[hammersmith] def runCommand[Cmd <% BSONDocument](ns: String, cmd: Cmd)(f: SingleDocQueryRequestFuture) {
    val qMsg = MongoConnection.createCommand(ns, cmd)
    log.trace("Created Query Message: %s, id: %d", qMsg, qMsg.requestID)
    send(qMsg, f)
  }

  protected[hammersmith] def send(msg: MongoClientMessage, f: RequestFuture)(implicit concern: WriteConcern = this.writeConcern) =
    MongoConnection.send(msg, f)

  /**
   * Remember, a DB is basically a future since it doesn't have to exist.
   */
  def apply(dbName: String): DB = database(dbName)

  /**
   * Remember, a DB is basically a future since it doesn't have to exist.
   */
  def database(dbName: String): DB = new DB(dbName)(this)

  def databaseNames(callback: Seq[String] ⇒ Unit) {
    runCommand("admin", Document("listDatabases" -> 1))(SimpleRequestFutures.command((doc: Document) ⇒ {
      log.trace("Got a result from 'listDatabases' command: %s", doc)
      if (!doc.isEmpty) {
        val dbs = {
          val lst = doc.as[BSONList]("databases").asList
          lst.map(_.asInstanceOf[Document].as[String]("name"))
        }
        callback(dbs)
      } else {
        log.warning("Command 'listDatabases' failed. Doc: %s", doc)
        callback(List.empty[String])
      }
    }))
  }

  def find[Qry <: BSONDocument, Flds <: BSONDocument](db: String)(collection: String)(query: Qry = Document.empty, fields: Flds = Document.empty, numToSkip: Int = 0, batchSize: Int = 0)(callback: CursorQueryRequestFuture)(implicit concern: WriteConcern = this.writeConcern) {
    val qMsg = QueryMessage(db + "." + collection, numToSkip, batchSize, query, fieldSpec(fields))
    send(qMsg, callback)
  }

  def findOne[Qry <: BSONDocument, Flds <: BSONDocument](db: String)(collection: String)(query: Qry = Document.empty, fields: Flds = Document.empty)(callback: SingleDocQueryRequestFuture)(implicit concern: WriteConcern = this.writeConcern) {
    val qMsg = QueryMessage(db + "." + collection, 0, -1, query, fieldSpec(fields))
    send(qMsg, callback)
  }

  // TODO - should we allow any and do boxing elsewhere?
  // TODO - FindOne is Option[] returning, ensure!
  def findOneByID[A <: AnyRef, Flds <: BSONDocument](db: String)(collection: String)(id: A, fields: Flds = Document.empty)(callback: SingleDocQueryRequestFuture) =
    findOne(db)(collection)(Document("_id" -> id), fields)(callback)

  // TODO - Immutable mode / support immutable objects
  def insert[T](db: String)(collection: String)(doc: T, validate: Boolean = true)(callback: WriteRequestFuture)(implicit concern: WriteConcern = this.writeConcern, m: SerializableBSONObject[T]) {
    log.trace("Inserting: %s to %s.%s with WriteConcern: %s", doc, db, collection, concern)
    val checked = if (validate) {
      m.checkObject(doc)
      m.checkID(doc)
    } else {
      log.debug("Validation of objects disabled; no ID Gen.")
      doc
    }
    send(InsertMessage(db + "." + collection, checked), callback)
  }

  /**
   * Insert multiple documents at once.
   * Keep in mind, that WriteConcern behavior may be wonky if you do a batchInsert
   * I believe the behavior of MongoDB will cause getLastError to indicate the LAST error
   * on your batch ---- not the first, or all of them.
   *
   * The WriteRequest used here returns a Seq[] of every generated ID, not a single ID
   * TODO - Support turning off ID Validation
   */
  def batchInsert[T](db: String)(collection: String)(docs: T*)(callback: WriteRequestFuture)(implicit concern: WriteConcern = this.writeConcern, m: SerializableBSONObject[T]) {
    log.trace("Batch Inserting: %s to %s.%s with WriteConcern: %s", docs, db, collection, concern)
    val checked = docs.map(x ⇒ {
      m.checkObject(x)
      m.checkID(x)
    })
    send(InsertMessage(db + "." + collection, checked: _*), callback)
  }

  /**
   * Counts the number of documents in a given namespace
   * -1 indicates an error, for now
   */
  def count[Qry: SerializableBSONObject, Flds: SerializableBSONObject](db: String)(collection: String)(query: Qry = Document.empty,
                                                                                                       fields: Flds = Document.empty,
                                                                                                       limit: Long = 0,
                                                                                                       skip: Long = 0)(callback: Int ⇒ Unit) = {
    val builder = OrderedDocument.newBuilder
    builder += ("count" -> collection)
    builder += ("query" -> query)
    builder += ("fields" -> fields)
    if (limit > 0)
      builder += ("limit" -> limit)
    if (skip > 0)
      builder += ("skip" -> skip)
    runCommand(db, builder.result)(SimpleRequestFutures.command((doc: Document) ⇒ {
      log.trace("Got a result from 'count' command: %s", doc)
      callback(doc.getAsOrElse[Double]("n", -1.0).toInt)
    }))
  }

  /**
   * Calls findAndModify in remove only mode with
   * fields={}, sort={}, remove=true, getNew=false, upsert=false
   * @param query
   * @return the removed document
   */
  def findAndRemove[Qry: SerializableBSONObject](db: String)(collection: String)(query: Qry = Document.empty)(callback: FindAndModifyRequestFuture) = findAndModify(db)(collection)(query = query, remove = true, update = Option[Document](null))(callback)

  /**
   * Finds the first document in the query and updates it.
   * @param query query to match
   * @param fields fields to be returned
   * @param sort sort to apply before picking first document
   * @param remove if true, document found will be removed
   * @param update update to apply
   * @param getNew if true, the updated document is returned, otherwise the old document is returned (or it would be lost forever) [ignored in remove]
   * @param upsert do upsert (insert if document not present)
   * @return the document
   */
  def findAndModify[Qry: SerializableBSONObject, Srt: SerializableBSONObject, Upd: SerializableBSONObject, Flds: SerializableBSONObject](db: String)(collection: String)(
    query: Qry = Document.empty,
    sort: Srt = Document.empty,
    remove: Boolean = false,
    update: Option[Upd] = None,
    getNew: Boolean = false,
    fields: Flds = Document.empty,
    upsert: Boolean = false)(callback: FindAndModifyRequestFuture) = {
    val cmd = OrderedDocument("findandmodify" -> collection,
      "query" -> query,
      "fields" -> fields,
      "sort" -> sort)

    //if (remove && (update.isEmpty || update.get.isEmpty) && !getNew)
    //throw new IllegalArgumentException("Cannot mix update statements or getNew param with 'REMOVE' mode.")

    if (remove) {
      log.debug("FindAndModify 'remove' mode.")
      cmd += "remove" -> true
    } else {
      log.debug("FindAndModify 'modify' mode.  GetNew? %s Upsert? %s", getNew, upsert)
      update.foreach(_up ⇒ {
        log.trace("Update spec set. %s", _up)
        // If first key does not start with a $, then the object must be inserted as is and should be checked.
        // TODO - FIX AND UNCOMMENT ME
        //if (_up.filterKeys(k => k.startsWith("$")).isEmpty) checkObject(_up)
        cmd += "update" -> _up
        // TODO - Make sure an error is thrown here that forces its way out.
      })
      cmd += "new" -> getNew
      cmd += "upsert" -> upsert
    }

    implicit val valM = callback.m
    implicit val valDec = new SerializableFindAndModifyResult[callback.T]()(callback.decoder, valM)

    runCommand(db, cmd)(SimpleRequestFutures.command((reply: FindAndModifyResult[callback.T]) ⇒ {
      log.trace("Got a result from 'findAndModify' command: %s", reply)
      val doc = reply.value
      if (boolCmdResult(reply, false) && !doc.isEmpty) {
        callback(doc.get.asInstanceOf[callback.T])
      } else {
        callback(reply.getAs[String]("errmsg") match {
          case Some("No matching object found") ⇒ new NoMatchingDocumentError()
          case default ⇒ {
            log.warning("Command 'findAndModify' may have failed. Bad Reply: %s", reply)
            new MongoException("FindAndModifyError: %s".format(default))
          }
        })
      }
    }))

  }

  def update[Upd](db: String)(collection: String)(query: BSONDocument, update: Upd, upsert: Boolean = false, multi: Boolean = false)(callback: WriteRequestFuture)(implicit concern: WriteConcern = this.writeConcern, uM: SerializableBSONObject[Upd]) {
    /**
     * If a field block doesn't start with a ($ - special type) we need to validate the keys
     * Since you can't mix $set, etc with a regular "object" this filters safely.
     * TODO - Fix and uncomment!!!!
     */
    // if (update.filterKeys(k => k.startsWith("$")).isEmpty) checkObject(update)
    send(UpdateMessage(db + "." + collection, query, update, upsert, multi), callback)
  }

  def save[T](db: String)(collection: String)(obj: T)(callback: WriteRequestFuture)(implicit concern: WriteConcern = this.writeConcern, m: SerializableBSONObject[T]) {
    m.checkObject(obj)
    throw new UnsupportedOperationException("Save doesn't currently function with the new system.")
    /*obj.get("_id") match {
      case Some(id) => {
        id match {
          case oid: ObjectId => oid.notNew()
          case default => {}
        }
        update(db)(collection)(Document("_id" -> id), obj, true, false)(callback)(concern)
      }
      case None => {
        obj += "_id" -> new ObjectId()
        insert(db)(collection)(obj)(callback)(concern)
      }
    }*/
  }

  def remove[T](db: String)(collection: String)(obj: T, removeSingle: Boolean = false)(callback: WriteRequestFuture)(implicit concern: WriteConcern = this.writeConcern, m: SerializableBSONObject[T]) {
    send(DeleteMessage(db + "." + collection, obj, removeSingle), callback)
  }

  // TODO - FindAndModify / FindAndRemove

  def createIndex[Kys <% BSONDocument, Opts <% BSONDocument](db: String)(collection: String)(keys: Kys, options: Opts = Document.empty)(callback: WriteRequestFuture) {
    implicit val idxSafe = WriteConcern.Safe
    val b = Document.newBuilder
    b += "name" -> indexName(keys)
    b += "ns" -> (db + "." + collection)
    b += "key" -> keys
    b ++= options
    insert(db)("system.indexes")(b.result, validate = false)(callback)
  }

  def createUniqueIndex[Idx <% BSONDocument](db: String)(collection: String)(keys: Idx)(callback: WriteRequestFuture) {
    implicit val idxSafe = WriteConcern.Safe
    createIndex(db)(collection)(keys, Document("unique" -> true))(callback)
  }

  /**
   * NOTE: If you want the "Returns Bool" version of these, use the version on Collection or DB
   */
  def dropAllIndexes(db: String)(collection: String)(callback: SingleDocQueryRequestFuture) {
    dropIndex(db)(collection)("*")(callback)
  }

  /**
   * NOTE: If you want the "Returns Bool" version of these, use the version on Collection or DB
   */
  def dropIndex(db: String)(collection: String)(name: String)(callback: SingleDocQueryRequestFuture) {
    // TODO index cache
    runCommand(db, Document("deleteIndexes" -> (db + "." + collection), "index" -> name))(callback)
  }


  def connected_? = MongoConnection.connected_?(context)
  
  def _connectedState(connected: Boolean) = MongoConnection.setConnectionState(context, connected)

  val addr: InetSocketAddress

  protected[hammersmith] var _writeConcern: WriteConcern = WriteConcern.Normal

  protected[hammersmith] def shutdown() {
    log.debug("Shutting Down & Cleaning up connection handler.")
    MongoConnection.cleaningTimer.stop(this)
    context.close()
    _connectedState(connected = false)
  }

  def close() {
    log.info("Closing down connection.")
    shutdown()
  }

  /**
   *
   * Set the write concern for this database.
   * Will be used for writes to any collection in this database.
   * See the documentation for {@link WriteConcern} for more info.
   *
   * @param concern (WriteConcern) The write concern to use
   * @see WriteConcern
   * @see http://www.thebuzzmedia.com/mongodb-single-server-data-durability-guide/
   */
  def writeConcern_=(concern: WriteConcern) = _writeConcern = concern

  /**
   *
   * get the write concern for this database,
   * which is used for writes to any collection in this database.
   * See the documentation for {@link WriteConcern} for more info.
   *
   * @see WriteConcern
   * @see http://www.thebuzzmedia.com/mongodb-single-server-data-durability-guide/
   */
  def writeConcern = _writeConcern
}

/**
 * Factory object for creating connections
 * based on things like URI Spec
 *
 * NOTE: Connection instances are instances of a *POOL*, always.
 *
 * @since 0.1
 */
object MongoConnection extends Logging {

  protected[hammersmith] val dispatcher: ConcurrentMap[Int, CompletableRequest] =
    new ConcurrentHashMap[Int, CompletableRequest]()

  protected[hammersmith] val cleaningTimer = new CursorCleaningTimer()

  /**
   * Cursors that need to be cleaned up
   * Weak is GOOD.  The idea here with a WeakHashMap is that internally
   * the Key is stored as a WeakReference.
   *
   * This means that a channel being in the deadCursors map will NOT PREVENT IT
   * from being garbage collected.
   */
  protected[hammersmith] val deadCursors = new WeakHashMap[ConnectionContext, ConcurrentQueue[Long]]

  /**
   * Canonical guide of the status of any channels.
   * e.g. what we know about their current live or dead status.
   */
  protected[hammersmith] val connectionState = new WeakHashMap[ConnectionContext, AtomicBoolean]

  /**
   * Operation queue for channels which aren't connected yet.
   */
  protected[hammersmith] val connectionOpQueue = new WeakHashMap[ConnectionContext, ConcurrentQueue[(Int) ⇒ Unit]]

  def apply(hostname: String = "localhost", port: Int = 27017) = {
    log.debug("New Connection with hostname '%s', port '%s'", hostname, port)
    // For now, only support Direct Connection

    new NettyConnection(new InetSocketAddress(hostname, port))
  }
  
  def connected_?(context: ConnectionContext): Boolean = connectionState.get(context) match {
    case Some(bool) => bool.get()
    case None => false
  }
  
  /**
   * Connect to MongoDB using a URI format.
   *
   * Because we can't tell if you are giving us just a host, a DB or a collection
   * The return type is a Triple of Connection, Option[DB], Option[Collection].
   *
   * You'll be given each of thes ethat could be validly built.
   *
   * @see http://www.mongodb.org/display/DOCS/Connections
   */
  def fromURI(uri: String): (MongoConnection, Option[DB], Option[Collection]) = uri match {
    case MongoURI(hosts, db, collection, username, password, options) ⇒ {
      require(hosts.size > 0, "No valid hosts found in parsed host list")
      if (hosts.size == 1) {
        val _conn = MongoConnection(hosts.head._1, hosts.head._2)
        // TODO - Authentication!
        val _db = db match {
          case Some(dbName) ⇒ Some(_conn(dbName))
          case None ⇒ None
        }

        val _coll = collection match {
          case Some(collName) ⇒ {
            assume(_db.isDefined, "Cannot specify a collection name with no DB Name")
            Some(_db.get.apply(collName))
          }
          case None ⇒ None
        }
        (_conn, _db, _coll)
      } else throw new UnsupportedOperationException("No current support for multiple hosts in this driver")
    }
  }

  /** TODO - Support timing out of ops */
  def setConnectionState(context: ConnectionContext, connected: Boolean) = {
    log.info("Setting a context state up to '%s' for '%s'", connected, context)
    val oldState = connectionState.getOrElseUpdate(context, new AtomicBoolean(false)).getAndSet(connected)
    if (oldState) connected match {
      case true ⇒ {
        log.trace("Connection state was already connected, set to connected again.  NOOP")
      }
      case false ⇒ {
        log.trace("Connection state was connected, set to disconnected. Otherwise, NOOP.")
      }
    }
    else connected match {
      case true ⇒ {
        log.info("Connection state was disconnected, set to connected.  Dequeueing any backed up operations.")
        connectionOpQueue.get(context).foreach { queue ⇒
          queue.dequeueAll().foreach { op ⇒ op(context.maxBSONObjectSize) }
        }
      }
      case false ⇒ {
        log.trace("Connection state was disconnected, set to disconnected again.  NOOP.")
      }
    }
  }

  protected[hammersmith] def send(msg: MongoClientMessage, f: RequestFuture, _overrideLiveCheck: Boolean = false)(implicit context: ConnectionContext, concern: WriteConcern = WriteConcern.Normal) = {
    require(context.connected_?, "Connection is closed.")
    implicit val maxBSON = context.maxBSONObjectSize
    val isWrite = f.isInstanceOf[WriteRequestFuture]
    // TODO - Better pre-estimation of buffer size - We don't have a length attributed to the Message yet
    val outStream = context.newOutputStream
    log.trace("Put msg id: %s f: %s into dispatcher: %s", msg.requestID, f, dispatcher)
    log.trace("PreWrite with outStream '%s'", outStream)
    /**
     * We only setup dispatchers if it is a Non-Write Request or a Write Request w/ a Write Concern that necessitates GLE
     * The GLE / Safe write stuff is setup later
     */
    if (!isWrite) dispatcher.put(msg.requestID, CompletableRequest(msg, f))
    msg.write(outStream)
    log.debug("Writing Message '%s' out to Channel via stream '%s'.", msg, outStream)

    /**
     * Determine if we need to execute a GetLastError (E.G. w > 0),
     * or execute a non-GLEed immediate callback against write requests.
     */
    // Quick callback when needed to be invoked immediately after write
    val writeCB: () ⇒ Unit = if (isWrite) {
      msg match {
        case wMsg: MongoClientWriteMessage ⇒ if (concern.safe_?) {
          val gle = createCommand(wMsg.namespace.split("\\.")(0), Document("getlasterror" -> 1))
          log.trace("Created a GetLastError Message: %s", gle)
          /**
           * We only setup dispatchers if it is a Non-Write Request or a Write Request w/ a Write Concern that necessitates GLE
           * Note we dispatch the GetLastError's ID but with the write message !
           */
          dispatcher.put(gle.requestID, CompletableRequest(msg, f))
          gle.write(outStream)
          log.debug("Wrote a getLastError to the tail end of the output buffer.")
          () ⇒ {}
        } else () ⇒ { wMsg.ids.foreach(x ⇒ f((x, WriteResult(true)).asInstanceOf[f.T])) }
        case unknown ⇒ {
          val e = new IllegalArgumentException("Invalid type of message passed; WriteRequestFutures expect a MongoClientWriteMessage underneath them. Got " + unknown)
          log.error(e, "Error in write.")
          () ⇒ { f(e) }
        }
      }
    } else () ⇒ {}
    // todo - clean this up to be more automatic like the writeCB is

    val exec = (_maxBSON: Int) ⇒ {
      context.write(outStream)
      outStream.close()
      /** If no write Concern and it's a write, kick the callback now.*/
      writeCB()
    }
    // If the channel is open, it still doesn't mean we have a valid Mongo Connection.
    if (!connected_?(context) && !_overrideLiveCheck) {
      log.info("Channel is not currently considered 'live' for MongoDB... May still be connecting or recovering from a Replica Set failover. Queueing operation. (override? %s) ", _overrideLiveCheck)
      connectionOpQueue.getOrElseUpdate(context, new ConcurrentQueue()) += exec
    } else exec(maxBSON)

  }

  protected[hammersmith] def createCommand[Cmd <% BSONDocument](ns: String, cmd: Cmd) = {
    log.trace("Attempting to create command '%s' on DB '%s.$cmd'", cmd, ns)
    QueryMessage(ns + ".$cmd", 0, -1, cmd)
  }

  /**
   * Deferred - doesn't actually happen immediately
   */
  protected[hammersmith] def killCursors(ids: Long*)(implicit context: ConnectionContext) {
    log.debug("Adding Dead Cursors to cleanup list: %s on Context: %s", ids, context)
    deadCursors.getOrElseUpdate(context, new ConcurrentQueue[Long]()) ++= ids
  }

  /**
   *  Clean up any resources (Typically cursors)
   *  Called regularly by a managed CursorCleaner thread.
   */
  protected[hammersmith] def cleanup() {
    log.trace("Cursor Cleanup running.")
    if (deadCursors.isEmpty) {
      log.debug("No Dead Cursors.")
    } else {
      deadCursors.foreach((entry: (ConnectionContext, ConcurrentQueue[Long])) ⇒ if (!entry._2.isEmpty) {
        // TODO - ensure no concurrency issues / blockages here.
        log.trace("Pre DeQueue: %s", entry._2.length)
        val msg = KillCursorsMessage(entry._2.dequeueAll())
        log.trace("Post DeQueue: %s", entry._2.length)
        log.debug("Killing Cursors with Message: %s with %d cursors.", msg, msg.numCursors)
        MongoConnection.send(msg, NoOpRequestFuture)(entry._1) // todo  flexible BSON although I doubt we'll need a 16 meg killcursors
      } else {
        log.debug("Removing Channel '%s' from cursor cleanup queue as it has no dead cursors.", entry._1) // should help with auto shutdown of cleaner thread + process
        deadCursors.remove(entry._1)
      })
    }
  }

}
