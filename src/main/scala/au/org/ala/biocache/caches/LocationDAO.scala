package au.org.ala.biocache.caches

import java.util

import au.com.bytecode.opencsv.CSVReader
import au.org.ala.biocache.util.LayersStore
import org.ala.layers.client.Client
import org.slf4j.LoggerFactory
import au.org.ala.biocache.Config
import scala.collection.mutable.HashMap
import au.org.ala.biocache.model.Location
import au.org.ala.biocache.vocab.StateProvinces
import scala.collection.JavaConverters._

/**
 * DAO for location lookups (lat, long -> locality).
 */
object LocationDAO {

  val logger = LoggerFactory.getLogger("LocationDAO")
  private val columnFamily = "loc"
  private val lock : AnyRef = new Object()
  private val lru = new org.apache.commons.collections.map.LRUMap(10000)
  private val persistenceManager = Config.persistenceManager
  private final val latitudeCol = "lat"
  private final val longitudeCol = "lon"

  /**
   * Directly add location lookups to cache - for mock testing purposes.
   * TODO remove the use of objects for these caches so that mock objects can
   * be done properly.
   */
  def addToCache(latitude:Float, longitude:Float, stateProvince:String, country:String, el:Map[String,String], cl:Map[String,String]) :  Location = {
    val location = new Location
    val guid = getLatLongKey(latitude, longitude)
    location.decimalLatitude = latitude.toString
    location.decimalLongitude = longitude.toString
    location.stateProvince = stateProvince
    location.country = country
    lock.synchronized { lru.put(guid, Some(location, el, cl)) }
    location
  }

  /**
   * Add a tag to a location
   */
  def addTagToLocation (latitude:Float, longitude:Float, tagName:String, tagValue:String) {
    val guid = getLatLongKey(latitude, longitude)
    persistenceManager.put(guid, columnFamily, latitudeCol, latitude.toString)
    persistenceManager.put(guid, columnFamily, longitudeCol, longitude.toString)
    persistenceManager.put(guid, columnFamily, tagName, tagValue)
  }

  /**
   * Add a region mapping for this point.
   */
  def addRegionToPoint (latitude:Double, longitude:Double, mapping:Map[String,String]) {
    val guid = getLatLongKey(latitude, longitude)
    var properties = scala.collection.mutable.Map[String,String]()
    properties ++= mapping
    properties.put(latitudeCol, latitude.toString)
    properties.put(longitudeCol, longitude.toString)
    persistenceManager.put(guid, columnFamily, properties.toMap)
  }

  /**
   * Add a region mapping for this point.
   */
  def addRegionToPoint (latitude:String, longitude:String, mapping:Map[String,String]) {
    if (latitude!=null && latitude.trim.length>0 && longitude!=null && longitude.trim.length>0){
      val guid = getLatLongKey(latitude, longitude)
      persistenceManager.put(guid, columnFamily, Map(latitudeCol -> latitude, longitudeCol -> longitude) ++ mapping)
    }
  }

  /**
   * Add a region mapping for this point.
   */
  def addLayerIntersects (latitude:String, longitude:String, contextual:Map[String,String], environmental:Map[String,Float]) {
    if (latitude!=null && latitude.trim.length>0 && longitude!=null && longitude.trim.length>0){
      val guid = getLatLongKey(latitude, longitude)

      val mapBuffer = new HashMap[String, String]
      mapBuffer += (latitudeCol -> latitude)
      mapBuffer += (longitudeCol-> longitude)
      mapBuffer ++= contextual
      mapBuffer ++= environmental.map(x => x._1 -> x._2.toString)

      persistenceManager.put(guid, columnFamily, mapBuffer.toMap)
    }
  }

  private def getLatLongKey(latitude:String, longitude:String) : String = {
    latitude.toFloat.toString.trim + "|" + longitude.toFloat.toString
  }

  private def getLatLongKey(latitude:Float, longitude:Float) : String = {
    latitude.toString.trim + "|" + longitude.toString
  }

  private def getLatLongKey(latitude:Double, longitude:Double) : String = {
    latitude.toString.trim + "|" + longitude.toString
  }

  /**
   * Get location information for point.
   * For geo spatial requirements we don't want to round the latitude , longitudes
   */
  def getByLatLon(latitude:String, longitude:String) : Option[(Location, Map[String,String], Map[String,String])] = {

    if (latitude == null || longitude == null || latitude.trim.length == 0 || longitude.trim.length == 0){
      return None
    }

    val uuid = getLatLongKey(latitude, longitude)

    val cachedObject = lock.synchronized { lru.get(uuid) }

    if(cachedObject != null){
        cachedObject.asInstanceOf[Option[(Location, Map[String, String], Map[String, String])]]
    } else {
        val map = persistenceManager.get(uuid,columnFamily)
        map match {
          case Some(map) => {
            val location = new Location
            location.decimalLatitude = latitude
            location.decimalLongitude = longitude

            //map this to sensible values we are used to
            val stateProvinceValue = map.getOrElse(Config.stateProvinceLayerID, null)
            if (stateProvinceValue != null & stateProvinceValue != ""){
              StateProvinces.matchTerm(stateProvinceValue) match {
                case Some(term) => location.stateProvince = term.canonical
                case None => {
                  /*do nothing for now */
                  logger.warn("Unrecognised state province value retrieved from layer " + Config.stateProvinceLayerID + " : " + stateProvinceValue)
                }
              }
            }

            location.isTerrestrial = {
              !map.get(Config.terrestrialLayerID).isEmpty
            }

            location.isMarine = {
              !map.get(Config.marineLayerID).isEmpty
            }

            location.country = map.getOrElse(Config.countriesLayerID, null)
            location.lga = map.getOrElse(Config.localGovLayerID, null)

            //if the country is null but the stateProvince has a value we can assume that it is an Australian point
            if(location.country == null && location.stateProvince != null) {
              location.country = Config.defaultCountry
            }

            val el = map.filter(x => x._1.startsWith("el"))
            val cl = map.filter(x => x._1.startsWith("cl"))

            val returnValue = Some((location, el, cl))

            lock.synchronized { lru.put(uuid,returnValue) }

            returnValue
          }
          case None => {
            //do a layer lookup???
            if(!Config.fieldsToSample.isEmpty) {
              logger.warn("Location lookup failed for [" + latitude + "," + longitude + "] - Sampling may need to be re-ran")
            }
            None
          }
        }
    }
  }

  private def doLayerIntersectForPoint(latitude:String, longitude:String) : Option[(Location, Map[String,String], Map[String,String])] = {

    //do a layers-store lookup
    val points = Array(Array[Double](longitude.toDouble, latitude.toDouble))

    var samples: java.util.ArrayList[String] = new util.ArrayList[String]()

    //allow local and remote sampling
    if (Config.layersServiceSampling) {
      val layersStore = new LayersStore(Config.layersServiceUrl)
      val samplesReader:CSVReader = new CSVReader(layersStore.sample(Config.fieldsToSample, points))
      val samplesRemote:util.List[Array[String]] = samplesReader.readAll()

      //exclude header and longitude,latitude in the first 2 columns
      if (samplesRemote.size() == 2) {
        samples = new util.ArrayList[String](samplesRemote.get(1).length - 2)
        samplesRemote.get(1).slice(2, samplesRemote.get(1).length).foreach(samples.add(_))
      }
    } else {
      val layerIntersectDAO = Client.getLayerIntersectDao()
      samples = layerIntersectDAO.sampling(Config.fieldsToSample, points)
    }

    if(!samples.isEmpty){
      val values:Array[String] = samples.toArray(Array[String]())
      //create a map to store in loc
      val mapBuffer = new HashMap[String, String]
      mapBuffer += (latitudeCol -> latitude)
      mapBuffer += (longitudeCol -> longitude)
      mapBuffer ++= (Config.fieldsToSample zip values).filter(x => x._2.trim.length != 0 && x._2 != "n/a")
      val propertyMap = mapBuffer.toMap
      val guid = getLatLongKey(latitude, longitude)
      persistenceManager.put(guid, columnFamily, propertyMap)
      //now map fields to elements of the model object "Location" and return this
      val location = new Location
      location.decimalLatitude = latitude
      location.decimalLongitude = longitude
      val stateProvinceValue = propertyMap.getOrElse(Config.stateProvinceLayerID, null)
      //now do the state vocab substitution
      if (stateProvinceValue != null & stateProvinceValue != ""){
        StateProvinces.matchTerm(stateProvinceValue) match {
          case Some(term) => location.stateProvince = term.canonical
          case None => {
            /*do nothing for now */
            logger.warn("Unrecognised state province value retrieved from layer cl927: " + stateProvinceValue)
          }
        }
      }
      location.country = propertyMap.getOrElse(Config.countriesLayerID, null)

      val el = propertyMap.filter(x => x._1.startsWith("el"))
      val cl = propertyMap.filter(x => x._1.startsWith("cl"))

      Some((location, el, cl))
    } else {
      None
    }
  }
}
