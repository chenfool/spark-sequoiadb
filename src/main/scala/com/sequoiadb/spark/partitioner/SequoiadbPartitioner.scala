/*
 *  Licensed to SequoiaDB (C) under one or more contributor license agreements.
 *  See the NOTICE file distributed with this work for additional information
 *  regarding copyright ownership. The SequoiaDB (C) licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package com.sequoiadb.spark.partitioner

/**
 * Source File Name = SequoiadbPartitioner.scala
 * Description      = Utiliy to extract partition information for a given collection
 * When/how to use  = SequoiadbRDD.getPartitions calls this class to extract Partition array
 * Restrictions     = N/A
 * Change Activity:
 * Date     Who                Description
 * ======== ================== ================================================
 * 20150305 Tao Wang           Initial Draft
 * 20150324 Tao Wang           Use DBCollection.explain instead of snapshot
 */
import com.sequoiadb.spark.SequoiadbConfig
import com.sequoiadb.spark.SequoiadbException
import com.sequoiadb.spark.schema.SequoiadbRowConverter
import com.sequoiadb.spark.util.ConnectionUtil
import com.sequoiadb.spark.io.SequoiadbReader
import com.sequoiadb.base.SequoiadbDatasource
import com.sequoiadb.base.Sequoiadb
import com.sequoiadb.base.DBQuery
import com.sequoiadb.exception.BaseException
import org.bson.BSONObject
import org.bson.BasicBSONObject
import org.bson.types.BasicBSONList
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import org.apache.spark.sql.sources.Filter
import scala.collection.mutable.Set
import com.sequoiadb.base.DBCollection
import org.slf4j.{Logger, LoggerFactory}
import org.bson.util.JSON
import sun.security.util.Cache.EqualByteArray
//import java.io.FileOutputStream;  


/**
 * @param config Partition configuration
 */
case class SequoiadbPartitioner(
  config: SequoiadbConfig) extends Serializable {
  /**
   * build full collection name from cs and cl name
   */
  val collectionname: String = config[String](SequoiadbConfig.CollectionSpace) + "." +
                                config[String](SequoiadbConfig.Collection)

  private var LOG: Logger = LoggerFactory.getLogger(this.getClass.getName())
  
  /**
   * Get SequoiaDB CL Obj by cs_cl_name string
   * @param connection SequoiaDB connection object
   * @param cs_cl_name string
   */
  private def getCLObject (conn: Sequoiadb, _collectionname: String): DBCollection = {
    val _col = _collectionname.split('.')
    val collectionspacename = _col(0)
    val collectionname = if ( _col.length > 1 ) _col(1) else ""
    return conn.getCollectionSpace(collectionspacename).getCollection(collectionname)
  }

  /**
   * Get database replication group information
   * @param connection SequoiaDB connection object
   */
  private def getReplicaGroup ( connection: Sequoiadb ) : 
    Option[ArrayBuffer[Map[String,AnyRef]]] = {
    val rg_list : ArrayBuffer[Map[String, AnyRef]] = new ArrayBuffer[Map[String, AnyRef]]
    // try to get replica group, if SDB_RTN_COORD_ONLY is received, we create
    // partition for standalone only
    try {
      // go through all replica groups
      val rgcur = connection.listReplicaGroups
      // append each group into rg list
      while ( rgcur.hasNext ) {
        rg_list.append(SequoiadbRowConverter.dbObjectToMap(rgcur.getNext()))
      }
      rgcur.close
      Option(rg_list)
    }
    catch{
      // if we get SDB_RTN_COORD_ONLY error, that means we connect to standalone or data node
      // in that case we simply return null instead of throwing exception
      case ex: BaseException => {
        if (ex.getErrorType.equals("SDB_RTN_COORD_ONLY")) {
          None
        }
        
        else {
          throw SequoiadbException(ex.getMessage, ex)
        }
      }
      case ex: Exception => throw SequoiadbException(ex.getMessage,ex)
    }
  }
  
  

  /**
   * Convert rg_list to replication group map
   * @param rg_list replication group list
   */
  private def makeRGMap ( rg_list: ArrayBuffer[Map[String, AnyRef]] ) :
    HashMap[String, Seq[SequoiadbHost]] = {
    val rg_map : HashMap[String, Seq[SequoiadbHost]] = HashMap[String, Seq[SequoiadbHost]]()
    // for each record from listReplicaGroup result
    for ( rg <- rg_list ) {
      val hostArray: ArrayBuffer[SequoiadbHost] = ArrayBuffer[SequoiadbHost]()
      // pickup "Group" field of Array and loop for each element
      for ( node <- SequoiadbRowConverter.dbObjectToMap(
          rg("Group").asInstanceOf[BasicBSONList]) ) {
        // pickup the Service name from group member and check each element's type
        for ( port <- SequoiadbRowConverter.dbObjectToMap(
            node._2.asInstanceOf[BSONObject].get("Service").asInstanceOf[BasicBSONList]) ) {
          // if the type represents direct connect port, we record it in hostArray
          if ( port._2.asInstanceOf[BSONObject].get("Type").asInstanceOf[Int] == 0 ) {
            // let's record the hostname and port name
            hostArray.append ( SequoiadbHost (
                node._2.asInstanceOf[BSONObject].get("HostName").asInstanceOf[String] + ":" +
                port._2.asInstanceOf[BSONObject].get("Name").asInstanceOf[String] ))
          }
        }
      }
      // build the map based on the group name and array we built
      rg_map += ( rg("GroupName").asInstanceOf[String] -> hostArray )
    }
    rg_map
  }
  
  /**
   * Extract partition information for given collection by explain
   * @param queryObj
   */
  private def computePartitionsByExplain( queryObj : BSONObject ): Array[SequoiadbPartition] = {
    var ds : Option[SequoiadbDatasource] = None
    var connection : Option[Sequoiadb] = None
    val partition_list: ArrayBuffer[SequoiadbPartition] = ArrayBuffer[SequoiadbPartition]()
    var rg_list : Option[ArrayBuffer[Map[String, AnyRef]]] = None
    var rg_map : HashMap[String, Seq[SequoiadbHost]] = HashMap[String, Seq[SequoiadbHost]]()
    try {
      ds = Option(new SequoiadbDatasource (
          config[List[String]](SequoiadbConfig.Host),
          //new ArrayList(Arrays.asList(config[List[String]](SequoiadbConfig.Host).toArray: _*)),
          config[String](SequoiadbConfig.Username),
          config[String](SequoiadbConfig.Password),
          ConnectionUtil.initConfigOptions,
          ConnectionUtil.initSequoiadbOptions ))
      // pickup a connection
      connection = Option(ds.get.getConnection)
      
      val preferenceObj = ConnectionUtil.getPreferenceObj (
                                              ConnectionUtil.getPreferenceStr(
                                                  config[String](SequoiadbConfig.Preference)
                                                  )
                                         )
      
      connection.get.setSessionAttr(preferenceObj)
      // get replica group
      rg_list = getReplicaGroup ( connection.get )
      // if there's no replica group can be found, let's return the current connected node
      // as the only partition
      if ( rg_list == None ){
        return Array[SequoiadbPartition] ( SequoiadbPartition ( 0, SequoiadbConfig.scanTypeExplain,
          Seq[SequoiadbHost] (SequoiadbHost(connection.get.getServerAddress.getHost + ":" +
            connection.get.getServerAddress.getPort )),
            SequoiadbCollection(collectionname),
            None))
      }
      else {
        // we now need to extract rg list and form a map
        // each key in map represent a group name, with one or more hosts associated with a group
        rg_map = makeRGMap(rg_list.get)

        // now let's try to get explain
        val cursor = connection.get.getCollectionSpace(
          config[String](SequoiadbConfig.CollectionSpace)).getCollection(
            config[String](SequoiadbConfig.Collection)
          ).explain (queryObj, null, null, null, 0, -1, 0, null)
        // loop for every fields in the explain result
        var partition_id = 0
        while ( cursor.hasNext ) {
          // note each row represent a group
          val row = SequoiadbRowConverter.dbObjectToMap(cursor.getNext)
          // record the group name
          val group = rg_map(row("GroupName").asInstanceOf[String])  
          if ( row.contains("SubCollections") ) {
            // if subcollections field exist, that means it's main cl
            for ( collection <- SequoiadbRowConverter.dbObjectToMap(
              row("SubCollections").asInstanceOf[BasicBSONList]) ) {
              // get the collection name
              partition_list += SequoiadbPartition ( partition_id, SequoiadbConfig.scanTypeExplain, group,
                SequoiadbCollection(collection._2.asInstanceOf[BSONObject].get("Name").asInstanceOf[String]), None)
              partition_id += 1
            }
          } else {
            // otherwise it means normal cl
            partition_list += SequoiadbPartition ( partition_id, SequoiadbConfig.scanTypeExplain, group,
              SequoiadbCollection(row("Name").asInstanceOf[String]), None)
            partition_id += 1
          }
        }
      } // if ( rg_list == None )
    } catch {
      case ex: Exception =>
        throw SequoiadbException(ex.getMessage, ex)
    } finally {
      ds.fold(ifEmpty=()) { connectionpool =>
        connection.fold(ifEmpty=()) { conn =>
          conn.closeAllCursors()
          connectionpool.close(conn)
        }
        connectionpool.close
      } // ds.fold(ifEmpty=())
    } // finally
    partition_list.toArray
  }
  
  /**
   * Extract partition information for given collection by getQueryMeta
   * @param queryObj
   */
  private def computePartitionsByGetQueryMeta(queryObj : BSONObject): Array[SequoiadbPartition] = {
    var ds : Option[SequoiadbDatasource] = None
    var connection : Option[Sequoiadb] = None
    val partition_list: ArrayBuffer[SequoiadbPartition] = ArrayBuffer[SequoiadbPartition]()
    val collectionSet:  Set [String] = scala.collection.mutable.Set ()
    
    try {
      ds = Option(new SequoiadbDatasource (
          config[List[String]](SequoiadbConfig.Host),
          //new ArrayList(Arrays.asList(config[List[String]](SequoiadbConfig.Host).toArray: _*)),
          config[String](SequoiadbConfig.Username),
          config[String](SequoiadbConfig.Password),
          ConnectionUtil.initConfigOptions,
          ConnectionUtil.initSequoiadbOptions ))
      // pickup a connection
      connection = Option(ds.get.getConnection)
      
      val preferenceObj = ConnectionUtil.getPreferenceObj (
                                              ConnectionUtil.getPreferenceStr(
                                                  config[String](SequoiadbConfig.Preference)
                                                  )
                                         )
      connection.get.setSessionAttr(preferenceObj)
      // get sdb collection
      val collection = connection.get.getCollectionSpace(
          config[String](SequoiadbConfig.CollectionSpace)).getCollection(
            config[String](SequoiadbConfig.Collection)
          )
          
      val cursor = collection.explain (queryObj, null, null, null, 0, -1, 0, null)
      // get all collection's name
      while ( cursor.hasNext ) {
        // note each row represent a group
        val row = SequoiadbRowConverter.dbObjectToMap(cursor.getNext)
        if ( row.contains("SubCollections") ) {
          // if subcollections field exist, that means it's main cl
          for ( collection <- SequoiadbRowConverter.dbObjectToMap(
            row("SubCollections").asInstanceOf[BasicBSONList]) ) {
            // get the collection name
            collectionSet.add (collection._2.asInstanceOf[BSONObject].get("Name").asInstanceOf[String])
          }
        } else {
          // otherwise it means normal cl
          collectionSet.add (row("Name").asInstanceOf[String])
        }
      }
      cursor.close
      
      
      
      
      def getQueryMetaObj (clObject: DBCollection, queryObj: BSONObject): Array[BSONObject] = {
        
        // if sdb scanType = idxscan, then getQueryMeta will throw error,so we set hint to change query explain
        val hintObj:BSONObject = new BasicBSONObject()
        hintObj.put ("", null)
        
        val bson_list: ArrayBuffer[BSONObject] = ArrayBuffer[BSONObject]()
       
        val cursor = clObject.getQueryMeta(queryObj, null, hintObj, 0, -1, 0)
        while (cursor.hasNext) {
          val tmp = SequoiadbRowConverter.dbObjectToMap(cursor.getNext)
          for (block <- SequoiadbRowConverter.dbObjectToMap(tmp("Datablocks").asInstanceOf[BasicBSONList])){
              val obj: BSONObject = new BasicBSONObject ()

              val _list: BSONObject = new BasicBSONList()
              
              _list.put ("0", block._2.asInstanceOf[Int])
              obj.put ("Datablocks", _list)
              obj.put ("ScanType", "tbscan")
              obj.put ("HostName", tmp("HostName").asInstanceOf[String])
              obj.put ("ServiceName", tmp("ServiceName").asInstanceOf[String])
              
              bson_list += obj
          }
        }
        return bson_list.toArray
      }
      
      var partition_id = 0
      for (_collectionname <- collectionSet) {
        
        val _metaObjList = getQueryMetaObj (getCLObject (connection.get, _collectionname), queryObj)
        
        for (_obj <- _metaObjList){
          val metaObj: BasicBSONObject = new BasicBSONObject ()
          val t = new BasicBSONObject ()
          metaObj.put ("Datablocks", _obj.get ("Datablocks").asInstanceOf[BasicBSONList])
          metaObj.put ("ScanType", "tbscan")
          
          val hostArray: ArrayBuffer[SequoiadbHost] = ArrayBuffer[SequoiadbHost]()
          hostArray.append (SequoiadbHost(_obj.get("HostName").asInstanceOf[String] + ":" + _obj.get("ServiceName").asInstanceOf[String]))
          partition_list += SequoiadbPartition ( partition_id, SequoiadbConfig.scanTypeGetQueryMeta, 
                 hostArray,
                 SequoiadbCollection(_collectionname),
                 Option(metaObj.toString))
          partition_id += 1
        }
      }
    } catch {
      case ex: Exception =>
        throw SequoiadbException(ex.getMessage, ex)
    } finally {
      ds.fold(ifEmpty=()) { connectionpool =>
        connection.fold(ifEmpty=()) { conn =>
          conn.closeAllCursors()
          connectionpool.close(conn)
        }
        connectionpool.close
      } // ds.fold(ifEmpty=())
    } // finally
    return partition_list.toArray
  }

  /**
   * Extract partition information for given collection
   * @param filters
   */
  def computePartitions(filters: Array[Filter]): Array[SequoiadbPartition] = {
    var ds : Option[SequoiadbDatasource] = None
    var connection : Option[Sequoiadb] = None
    var partition_list: Option[Array[SequoiadbPartition]] = None
    var scanType : Option[String] = None
    val queryObj : BSONObject = SequoiadbReader.queryPartition(filters)
    try {
      ds = Option(new SequoiadbDatasource (
          config[List[String]](SequoiadbConfig.Host),
          //new ArrayList(Arrays.asList(config[List[String]](SequoiadbConfig.Host).toArray: _*)),
          config[String](SequoiadbConfig.Username),
          config[String](SequoiadbConfig.Password),
          ConnectionUtil.initConfigOptions,
          ConnectionUtil.initSequoiadbOptions ))
      // pickup a connection
      connection = Option(ds.get.getConnection)
      
      val preferenceObj = ConnectionUtil.getPreferenceObj (
                                              ConnectionUtil.getPreferenceStr(
                                                  config[String](SequoiadbConfig.Preference)
                                                  )
                                         )
      connection.get.setSessionAttr(preferenceObj)   
      
      val csName = config[String](SequoiadbConfig.CollectionSpace)
      val clname = config[String](SequoiadbConfig.Collection)

      LOG.info ("queryObj = " + queryObj.toString)
      val cursor = connection.get.getCollectionSpace(
          csName).getCollection(
            clname
          ).explain (queryObj, null, null, null, 0, -1, 0, null)
      
      var breakValue = false
      var IsExplainHaveValue = false
      var IsMainSubCL = false
      var IsSubCLSplited = false
      
      def checkCLIsSplited (CL : DBCollection, queryObj : BSONObject) : Boolean = {
        val cursor = CL.explain (queryObj, null, null, null, 0, -1, 0, null)
        var i = 0
        while (cursor.hasNext) {
          i=1+i;
          cursor.getNext
        }
        cursor.close 
        
        if (i <= 1) {
          return false
        } else {
          return true
        } 
        
        return false
      }
      
      while ( cursor.hasNext && !breakValue ) {
        IsExplainHaveValue = true
        // note each row represent a group
        val row = SequoiadbRowConverter.dbObjectToMap(cursor.getNext)
        if ( row.contains("SubCollections") ) {
          // if subcollections field exist, that means it's main cl
          IsMainSubCL = true
          for ( collection <- SequoiadbRowConverter.dbObjectToMap(
            row("SubCollections").asInstanceOf[BasicBSONList]) ) {
            // get the collection name
            scanType = Option(collection._2.asInstanceOf[BSONObject].get("ScanType").asInstanceOf[String])
            
            val _collectionname = collection._2.asInstanceOf[BSONObject].get("Name").asInstanceOf[String]
            val CL = getCLObject (connection.get , _collectionname)
            if (checkCLIsSplited (CL, queryObj)) {
              IsSubCLSplited = true
              breakValue = true
            }
          }
        } else {
          // otherwise it means normal cl
          scanType = Option(row("ScanType").asInstanceOf[String])
          IsMainSubCL = false
          IsSubCLSplited = false
          breakValue = true
        }
      }
      cursor.close
      // if explain is NULL, then return List[NULL]
      if (!IsExplainHaveValue) {
        val partition_none: ArrayBuffer[SequoiadbPartition] = ArrayBuffer[SequoiadbPartition]()
        return partition_none.toArray
      }
      
      val checkScanType = config[String](SequoiadbConfig.ScanType)
      if (!checkScanType.equalsIgnoreCase("ixscan") &&
          !checkScanType.equalsIgnoreCase("tbscan") &&
          !checkScanType.equalsIgnoreCase("auto")) {
        LOG.warn ("Config's scanType = " + checkScanType + ", we will set scanType = auto")
      }
      else {
        LOG.info ("Config's scanType = " + checkScanType)
      }
      if (checkScanType.equalsIgnoreCase("ixscan")) {
        LOG.info ("This query is index scan, then get SDB data by explain")
        partition_list = Option(computePartitionsByExplain (queryObj))
      }
      else if (checkScanType.equalsIgnoreCase("tbscan")) {
        LOG.info ("This query is table scan, then get SDB data by getQueryMeta")
        partition_list = Option(computePartitionsByGetQueryMeta (queryObj))
      }
      else { // checkScanType = 'auto'
        // this query is table scan, then get SDB data by getQueryMeta
        if ( scanType.get.equals("tbscan")) {
          if (IsMainSubCL && IsSubCLSplited) {
            LOG.info ("This query is table scan")
            LOG.info (csName + "." + clname + " is MainCL, and SubCL is been splited")
            LOG.info ("Then get SDB data by explain")
            partition_list = Option(computePartitionsByExplain (queryObj))
          }
          else {
            LOG.info ("This query is table scan")
            LOG.info (csName + "." + clname + " is normal CL")
            LOG.info ("Then get SDB data by getQueryMeta")
            partition_list = Option(computePartitionsByGetQueryMeta (queryObj))
          }
        }
        // this query is index scan, then get SDB data by explain
        else if (scanType.get.equals("ixscan")) { 
          LOG.info ("This query is index scan, then get SDB data by explain")
          partition_list = Option(computePartitionsByExplain (queryObj))
        }
        // scanType is unknow
        else {
          LOG.error ("scanType is unknow, scanType = " + scanType.get)
        }
      }
      
      
    } catch {
      case ex: Exception =>
        throw SequoiadbException(ex.getMessage, ex)
    } finally {
      ds.fold(ifEmpty=()) { connectionpool =>
        connection.fold(ifEmpty=()) { conn =>
          conn.closeAllCursors()
          connectionpool.close(conn)
        }
        connectionpool.close
      } // ds.fold(ifEmpty=())
    } // finally
    
    
    LOG.debug ("partition seq before, partition_list = " + SequoiadbPartitioner.getConnInfo (partition_list.get))
    partition_list = SequoiadbPartitioner.seqPartitionList (partition_list)
    LOG.debug ("partition seq over, partition_list = " + SequoiadbPartitioner.getConnInfo (partition_list.get))
    partition_list.get
  }
}

object SequoiadbPartitioner {
  
  def getConnInfo (partition_list: Array[SequoiadbPartition]):String = {
      val tmpPartitionArr: ArrayBuffer [String] = ArrayBuffer [String] ()
      
      for (partition <- partition_list) {
        val hostStr = partition.hosts.get(0).getHost
        val serviceStr = partition.hosts.get(0).getService
        if (partition.scanType.equals(SequoiadbConfig.scanTypeGetQueryMeta)) {
          val _metaObjStr = partition.metaObjStr.get
          val _metaObj = JSON.parse (_metaObjStr).asInstanceOf[BSONObject]
          val metaNum = _metaObj.get ("Datablocks").asInstanceOf[BasicBSONList].get(0).asInstanceOf[Int]
          tmpPartitionArr += hostStr + ":" + serviceStr + ":" + metaNum
        }
        else {
          tmpPartitionArr += hostStr + ":" + serviceStr
        }
      }
      tmpPartitionArr.toString
    }

  /**
   *
   * @param partition_list.
   * @return Sequence partition_list
   */
  def seqPartitionList (tmp_partition_list: Option[Array[SequoiadbPartition]]): Option[Array[SequoiadbPartition]] = {
    val partition_list: ArrayBuffer[SequoiadbPartition] = ArrayBuffer[SequoiadbPartition]()
    val hostSet:  Set [String] = scala.collection.mutable.Set ()
    val haveReadHostSet:  Set [String] = scala.collection.mutable.Set ()
    val serviceSet:  Set [String] = scala.collection.mutable.Set ()
    
//    val hostArr = ArrayBuffer[ArrayBuffer[String]]()

    val tmpHostMap = scala.collection.mutable.Map[String, ArrayBuffer[scala.collection.mutable.Map[String, SequoiadbPartition]]]()    
    
    if (tmp_partition_list == None){
      return tmp_partition_list
    }
    for (partition <- tmp_partition_list.get){
      val hostStr = partition.hosts.get(0).getHost.toString
      val serviceStr = partition.hosts.get(0).getService.toString
      hostSet.add (hostStr)
      serviceSet.add (serviceStr)
      
      val serviceMap = scala.collection.mutable.Map[String, SequoiadbPartition] ()
      serviceMap.put (serviceStr, partition)
        
      // tmpHostMap do not have (hostStr, Any) Map
      if (tmpHostMap.get(hostStr) == None) {
        val serviceArr = ArrayBuffer[scala.collection.mutable.Map[String, SequoiadbPartition]] ()
        serviceArr += serviceMap
        tmpHostMap.put (hostStr, serviceArr)  
      } 
      // tmpHostMap has the (hostStr, Any) Map
      else {
        val serviceArr = tmpHostMap.get(hostStr).get
        serviceArr += serviceMap
        tmpHostMap.put (hostStr, serviceArr)  
      }
    } 
    
    // because after sequence Partition List, you need to set Partition number to 0 ~ N
    def initPartitionList_index_number (partitions: Option[ArrayBuffer[SequoiadbPartition]]): Option[ArrayBuffer[SequoiadbPartition]] = {
      if (partitions == None) {
        return None
      }
      val _partitionList = partitions.get
      val partition_list: ArrayBuffer[SequoiadbPartition] = ArrayBuffer[SequoiadbPartition]()
    
      var index_number = 0
      for (partition <- _partitionList) {
        partition.index = index_number
        partition_list += partition
        index_number = index_number + 1
      }
      return Option (partition_list)
    }
    // get the Map's key string value
    def getKey (inputMap: scala.collection.mutable.Map[String, SequoiadbPartition]): String = {
      val keySet = inputMap.keySet
      var key: String = null 
      for (_key <- keySet){
        key = _key
      }
      return key
    }
      
    // init hostMap's serviceMap value
    def initServiceMap ( serviceArrSort: ArrayBuffer[scala.collection.mutable.Map[String, SequoiadbPartition]]
    ): scala.collection.mutable.Map[String, ArrayBuffer[SequoiadbPartition]] = {
      val serviceMap = scala.collection.mutable.Map[String,  ArrayBuffer[SequoiadbPartition]]()
      for (tmpServiceMap <- serviceArrSort) {
        val serviceStr = getKey (tmpServiceMap).toString()
        val partition = tmpServiceMap.get (serviceStr).get
        
        if (serviceMap.get (serviceStr) == None) {
          val serviceArr = ArrayBuffer[SequoiadbPartition] ()
          serviceArr += partition
          serviceMap.put (serviceStr, serviceArr)
        } else {
          val serviceArr = serviceMap.get (serviceStr).get
          serviceArr += partition
          serviceMap.put (serviceStr, serviceArr)
        }
      }
      serviceMap
    }
    
    val hostMap = scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, ArrayBuffer[SequoiadbPartition]]]()
  
    for (serviceObj <- tmpHostMap){
      val serviceArr = serviceObj._2
      val hostStr = serviceObj._1

      hostMap.put (hostStr, initServiceMap (serviceArr))
    }
    
    def initHostTopology (hostMap: scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, ArrayBuffer[SequoiadbPartition]]])
      : scala.collection.mutable.Map[String, Set [String]] = {
      val hostTopology: scala.collection.mutable.Map[String, Set [String]] = 
      scala.collection.mutable.Map[String, scala.collection.mutable.Set[String]]()
      
      for (tmpHostMap <- hostMap) {
        val hostStr = tmpHostMap._1
        val serviceMap = tmpHostMap._2
        
        val serviceSet:  Set [String] = scala.collection.mutable.Set ()
        for (service <- serviceMap) {
          val serviceStr = service._1
          serviceSet.add (serviceStr)
        }
        hostTopology.put (hostStr, serviceSet)
      }
      return hostTopology
    }
    
    
    def initHaveReadHostTopology (hostMap: scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, ArrayBuffer[SequoiadbPartition]]])
      : scala.collection.mutable.Map[String, Set [String]] = {
      val haveReadHostTopology: scala.collection.mutable.Map[String, Set [String]] = 
        scala.collection.mutable.Map[String, scala.collection.mutable.Set[String]]()
        
        for (tmpHostMap <- hostMap) {
          val hostStr = tmpHostMap._1
          val serviceSet:  Set [String] = scala.collection.mutable.Set ()
          haveReadHostTopology.put (hostStr, serviceSet)
        }
        return haveReadHostTopology
    }
    
    val hostTopology = initHostTopology (hostMap)
    val haveReadHostTopology = initHaveReadHostTopology (hostMap)
    
    def ArrHasValue (hostMap: scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, ArrayBuffer[SequoiadbPartition]]]): Boolean = {
      var i = 0
      for (value <- hostMap) {
        i += 1
        return true 
      }
      
      if (i == 0){
        return false
      }
      false
    }
    
    def getPartitionNextHostStr (hostSet: Set [String], haveReadHostSet: Set [String])
    : Option [String] = {
      
      val diffHostSeq = hostSet.filter { hostStr => {
          !haveReadHostSet.exists { x => 
            if (x.equals(hostStr)) true else false 
          }
        } 
      }
      
      if (diffHostSeq.size != 0) {
        return Option (diffHostSeq.head)
      }
      
      return Option (hostSet.head)
    }
    
    def getPartitionNextServiveStr (hostServiceSet: Set[String], haveReadHostServiceSet: Set[String])
    : Option [String] = {
      
      val diffServiceSeq = hostServiceSet.filter { serviceStr => {
          !haveReadHostServiceSet.exists { x => 
            if (x.equals(serviceStr)) true else false 
          }
        } 
      }
      
      if (diffServiceSeq.size != 0) {
        return Option (diffServiceSeq.head)
      }
      
      return Option (hostServiceSet.head)
    }
    
    def getPartitionHostAndService (): String = {

      val nextHostStr = getPartitionNextHostStr (hostSet, haveReadHostSet).get
      
      haveReadHostSet.add (nextHostStr)
      
      val haveReadHostServiceSet = haveReadHostTopology.get (nextHostStr).get
      val hostServiceSet = hostTopology.get (nextHostStr).get
      
      val nextHostServiceStr = getPartitionNextServiveStr (hostServiceSet, haveReadHostServiceSet).get
      
      haveReadHostServiceSet.add (nextHostServiceStr)
      
      if (haveReadHostServiceSet.equals(hostServiceSet)) {
        haveReadHostServiceSet.clear
      }
      haveReadHostTopology.put (nextHostStr, haveReadHostServiceSet)
      
      if (haveReadHostSet.equals(hostSet)) {
        haveReadHostSet.clear
      }
      
      return nextHostStr + "::" + nextHostServiceStr
    }
      
    def getPartition (hostMap: scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, ArrayBuffer[SequoiadbPartition]]]): Option[SequoiadbPartition]= {
     
      
      val nextPartitionHostAndService = getPartitionHostAndService ()
      val nextPartitionHostAndServiceArr = nextPartitionHostAndService.split("::")
      val nextPartitionHostStr = nextPartitionHostAndServiceArr.apply(0)
      val nextPartitionSericeStr = nextPartitionHostAndServiceArr.apply(1)
      
      val _serviecMap = hostMap.get (nextPartitionHostStr).get
      val _partitionList = _serviecMap.get (nextPartitionSericeStr).get
      val _partition = _partitionList.get (0)
      
      return Option (_partition)

    }
      
    def getMetaNum (partition: SequoiadbPartition): Int = {
      
      if (partition.scanType.equals(SequoiadbConfig.scanTypeGetQueryMeta)){        
        if (partition.metaObjStr != None){
          val _metaObjStr = partition.metaObjStr.get
          val _metaObj = JSON.parse (_metaObjStr).asInstanceOf[BSONObject]
          val metaNum = _metaObj.get ("Datablocks").asInstanceOf[BasicBSONList].get(0).asInstanceOf[Int]
          return metaNum
        }
      }
      -1
    }
      
    def dropPartition (oldPartition: SequoiadbPartition, hostMap: scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, ArrayBuffer[SequoiadbPartition]]]): Option[scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, ArrayBuffer[SequoiadbPartition]]]] = {
      val oldHOST = oldPartition.hosts.get(0)
      val oldH = oldHOST.getHost.toString
      val oldS = oldHOST.getService.toString
      val oldCollectionObj = oldPartition.collection
      val oldCsName = oldPartition.collection.collectionspace
      val oldClName = oldPartition.collection.collection
      val oldCollectionName = oldCollectionObj.collectionname
      val oldMetaNum = getMetaNum (oldPartition)
      val scanType = oldPartition.scanType
      
      var removePartition = false
      
      val _serviceMap = hostMap.get(oldH).get
      val _nodeList = _serviceMap.get(oldS).get
      var _newNodeList = _nodeList
      
      // this partition's scanType is DataBlocks
      if (scanType.equals(SequoiadbConfig.scanTypeGetQueryMeta)){ 
        val _tNewNodeList = _nodeList.dropWhile { 
          x => {
            oldMetaNum == getMetaNum (x) &&
            oldCsName.equals(x.collection.collectionspace) &&
            oldClName.equals(x.collection.collection)
            }
        }
        _newNodeList = _tNewNodeList
      }
      // this partition's scalType is IndexScan
      else {
        val _tNewNodeList = _nodeList.dropWhile { 
          x => {
            oldCsName.equals(x.collection.collectionspace) &&
            oldClName.equals(x.collection.collection)
            }
        }
        _newNodeList = _tNewNodeList
      }
      
      val _hostMap = hostMap
      
      if (_newNodeList.length == 0) {
        _serviceMap.remove(oldS)
        
        if (_serviceMap.isEmpty) {
          _hostMap.remove(oldH)
          haveReadHostTopology.remove (oldH)
          hostTopology.remove (oldH)
          hostSet.remove (oldH)
          haveReadHostSet.clear
        }
        else {
          _hostMap.put (oldH, _serviceMap)
          
          val serviceSet = hostTopology.get (oldH).get 
          val haveReadServiceSet = haveReadHostTopology.get (oldH).get
          serviceSet.remove (oldS)
          haveReadServiceSet.clear
          hostTopology.put (oldH, serviceSet)
          haveReadHostTopology.put (oldH, haveReadServiceSet)
        }
      }
      else {
        _serviceMap.put (oldS, _newNodeList)
        _hostMap.put (oldH, _serviceMap)
      }
      return Option (_hostMap)
    }
      
    def getFirstPartition (hostMap: scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, ArrayBuffer[SequoiadbPartition]]]
    ): Option[SequoiadbPartition] = {
      for (hostObj <- hostMap) {
        val serviceMap = hostObj._2
        for (serviceObj <- serviceMap) {
          val partitionArr = serviceObj._2
          for (partition <- partitionArr) {
            haveReadHostSet.add (partition.hosts.get(0).getHost)
            
            if (haveReadHostSet.equals(hostSet)) {
              haveReadHostSet.clear
            }
            
            return Option(partition)
          }
        }
      }
      return None
    }
      
    var breakValue2 = false
    var first2 = true
    var partition2: SequoiadbPartition = null
    var tmpHostMap2 = hostMap
    while (true && ! breakValue2) {
      if (ArrHasValue (tmpHostMap2)) {
        if (first2) {
          partition2 = getFirstPartition (tmpHostMap2).get
          if (Option (partition2) != None) {
            partition_list += partition2
            first2 = false
          }
          else {
            breakValue2 = true
          }
        }
        else {
          partition2 = getPartition (tmpHostMap2).get
          if (Option (partition2) != None) {
            partition_list += partition2
          }
          else {
            breakValue2 = true
          }
        }
        if (Option (partition2) != None) {
          tmpHostMap2 = dropPartition (partition2, tmpHostMap2).get
        }
      } 
      else {
        breakValue2 = true
      } // end if (ArrHasValue (tmpHostMap))
              
    }
    
    val partition_list_last = initPartitionList_index_number (Option (partition_list)).get
    
    return Option (partition_list_last.toArray)
    
  }
  
  
}

