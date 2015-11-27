package grasshopper.geocoder.api

import akka.stream.scaladsl._
import feature._
import geometry.Point
import grasshopper.client.parser.AddressParserClient
import grasshopper.client.parser.model.ParsedAddress
import grasshopper.geocoder.model._
import grasshopper.geocoder.search.addresspoints.AddressPointsGeocode
import grasshopper.geocoder.search.census.CensusGeocode
import grasshopper.model.SearchableAddress
import org.elasticsearch.client.Client
import scala.concurrent.ExecutionContext

trait GeocodeFlow extends AddressPointsGeocode with CensusGeocode with ParallelismFactor {

  def client: Client

  def parseFlow: Flow[String, ParsedAddress, Unit] = {
    Flow[String]
      .mapAsync(numCores)(a => AddressParserClient.parse(a))
      .map { x =>
        if (x.isRight) {
          x.right.getOrElse(ParsedAddress.empty)
        } else {
          ParsedAddress.empty
        }
      }
  }

  def parsedInputAddressFlow: Flow[ParsedAddress, SearchableAddress, Unit] = {
    Flow[ParsedAddress]
      .map { parsed =>

        val partMap: Map[String, String] = parsed.parts.map(part => (part.code, part.value)).toMap

        SearchableAddress(
          partMap.getOrElse("address_number_full", ""),
          partMap.getOrElse("street_name_full", ""),
          partMap.getOrElse("city_name", ""),
          partMap.getOrElse("zip_code", ""),
          partMap.getOrElse("state_name", "")
        )
      }
  }

  def geocodePointFlow: Flow[String, Feature, Unit] = {
    Flow[String]
      .map(s => geocodePoint(client, "address", "point", s, 1).head)
  }

  def geocodePointFieldsFlow: Flow[SearchableAddress, Feature, Unit] = {
    Flow[SearchableAddress]
      .map(p => geocodePointFields(client, "address", "point", p, 1).head)
  }

  def geocodeLineFlow: Flow[SearchableAddress, Feature, Unit] = {
    Flow[SearchableAddress]
      .map(p => geocodeLine(client, "census", "addrfeat", p, 1).head)
  }

  def tupleToListFlow[T]: Flow[(T, T), List[T], Unit] = {
    Flow[(T, T)]
      .map(t => List(t._1, t._2))
  }

  def generateResponseFlow: Flow[(ParsedAddress, List[Feature]), GeocodeResponse, Unit] = {
    Flow[(ParsedAddress, List[Feature])]
      .map(t => GeocodeResponse(t._1.input, t._1.parts, t._2))
  }

  def featureToCsv: Flow[Feature, String, Unit] = {
    Flow[Feature]
      .map { f =>
        val address = f.get("address").getOrElse("").toString
        val longitude = f.geometry.centroid.x
        val latitude = f.geometry.centroid.y
        s"${address},${longitude},${latitude}"
      }
  }

  def filterFeatureListFlow: Flow[List[Feature], List[Feature], Unit] = {
    Flow[List[Feature]].map(xs => xs.filter(f => f.geometry.centroid.x != 0 && f.geometry.centroid.y != 0))
  }

  def geocodeFlow(implicit ec: ExecutionContext): Flow[String, GeocodeResponse, Unit] = {
    Flow() { implicit b =>
      import FlowGraph.Implicits._

      val input = b.add(Flow[String])
      val broadcastParsed = b.add(Broadcast[ParsedAddress](2))
      val pFlow = b.add(parseFlow)
      val pInputFlow = b.add(parsedInputAddressFlow)
      val broadcastSearchable = b.add(Broadcast[SearchableAddress](2))
      val point = b.add(geocodePointFieldsFlow)
      val line = b.add(geocodeLineFlow)
      val zip = b.add(Zip[Feature, Feature])
      val features = b.add(tupleToListFlow[Feature].via(filterFeatureListFlow))
      val zip1 = b.add(Zip[ParsedAddress, List[Feature]])
      val response = b.add(generateResponseFlow)

      input ~> pFlow ~> broadcastParsed ~> pInputFlow ~> broadcastSearchable
      broadcastSearchable ~> line ~> zip.in0
      broadcastSearchable ~> point ~> zip.in1
      broadcastParsed ~> zip1.in0
      zip.out ~> features ~> zip1.in1
      zip1.out ~> response

      (input.inlet, response.outlet)

    }
  }

  def filterPointResultFlow: Flow[GeocodeResponse, Feature, Unit] = {

    def predicate(source: String): (Feature) => Boolean = { f =>
      f.get("source")
        .getOrElse("")
        .toString == source
    }

    Flow[GeocodeResponse]
      .map(r => r.features)
      .map { features =>
        val points = features
          .filter(predicate("state-address-points"))
        val lines = features
          .filter(predicate("census-tiger"))

        if (points.nonEmpty) {
          points.head
        } else if (lines.nonEmpty) {
          lines.head
        } else {
          val schema = Schema(List(Field("geom", GeometryType()), Field("address", StringType())))
          val values = Map("geom" -> Point(0, 0), "address" -> "ADDRESS NOT FOUND")
          Feature(schema, values)
        }
      }

  }
}

