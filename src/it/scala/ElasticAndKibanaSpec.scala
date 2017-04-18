import java.util.UUID

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import cloud.drdrdr.SignRequestForAWS
import cloud.drdrdr.utils.{SignRequestForAWSTest, AWSCredentials}
import cloud.drdrdr.utils.Config.awsConfig
import org.scalatest._
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps


/**
  * Created by Adam Villaflor <adam.villaflor@3drobotics.com> on 11/11/15.
  *
  */
class ElasticAndKibanaSpec extends FunSpec with Matchers{
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val ec: ExecutionContext = testSystem.dispatcher
  implicit val materializer = ActorMaterializer()
  val requestSigner = new SignRequestForAWSTest

  val credentialProvider = new AWSCredentials()
  val credentialsSource = credentialProvider.getCredentials()
  val credentials = Await.result(credentialsSource.getCredentials, 10 seconds)
  val accessKeyID = credentials.accessKeyId
  val kSecret = credentials.secretAccessKey
  val token = credentials.token
  val expiration = credentials.expiration
  val uuid = UUID.randomUUID().toString

  // helps for debugging
  private def jsonPrint(response: HttpResponse) {
    val responseData = Await.result(response.entity.dataBytes.map(_.utf8String).grouped(Int.MaxValue).runWith(Sink.head), 10 seconds).mkString
    val responseJson = responseData.parseJson
    println(responseJson.prettyPrint)
  }

  // sends outgoing request
  private def post(httpRequest: HttpRequest): Future[HttpResponse] = {
    val endpoint = httpRequest.uri.toString()
    val uri = java.net.URI.create(endpoint)
    val outgoingConn = if (uri.getScheme == "https") {
      Http().outgoingConnectionHttps(uri.getHost, if (uri.getPort == -1) 443 else uri.getPort)
    } else {
      Http().outgoingConnection(uri.getHost, if (uri.getPort == -1) 80 else uri.getPort)
    }
    Source.single(httpRequest).via(outgoingConn).runWith(Sink.head)
  }

  // uses a random UUID to prevent using the same index again
  it("Should post the correct httpRequest with all the necessary aws authentication") {
    import DefaultJsonProtocol._
    val region = awsConfig.getString("region")
    val baseURI = awsConfig.getString("URI")
    val service = awsConfig.getString("service")
    val URI = s"${baseURI}test/$uuid"
    val data = Map("item1" -> "1", "item2" -> "2", "item3" -> "3")
    val paramStr2 = data.toJson.compactPrint
    val formContentType = ContentType(MediaTypes.`application/x-www-form-urlencoded`, HttpCharsets.`UTF-8`)
    val entity = HttpEntity(formContentType, paramStr2)
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = URI,
      entity = entity
    )
    val authRequest = Await.result(requestSigner.addAuthorizationHeader(request, kSecret, region, accessKeyID, service, token), 10 seconds)
    val response = Await.result(post(authRequest), 10 seconds)
    response.status shouldBe StatusCodes.Created
  }

  it("should be a sucessful get request by adding the authorization header") {
    val region = awsConfig.getString("region")
    val baseURI = awsConfig.getString("URI")
    val service = awsConfig.getString("service")
    val URI = s"$baseURI?Version=2013-10-15&Action=DescribeRegions"
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = URI
    )
    val authRequest = Await.result(requestSigner.addAuthorizationHeader(request, kSecret, region, accessKeyID, service, token), 10 seconds)
    val response = Await.result(post(authRequest), 10 seconds)
    response.status shouldBe StatusCodes.OK
  }

  it("should be a successful get request of the posted information") {
    val region = awsConfig.getString("region")
    val baseURI = awsConfig.getString("URI")
    val service = awsConfig.getString("service")
    val URI = s"${baseURI}test"
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = URI
    )
    val authRequest = Await.result(requestSigner.addAuthorizationHeader(request, kSecret, region, accessKeyID, service, token), 10 seconds)
    val response = Await.result(post(authRequest), 10 seconds)
    response.status shouldBe StatusCodes.OK
  }

  it("should be a successful get request adding the authorization query String") {
    val region = awsConfig.getString("region")
    val baseURI = awsConfig.getString("URI")
    val service = awsConfig.getString("service")
    val URI = s"$baseURI?Version=2013-10-15&Action=DescribeRegions"
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = URI
    )
    val authRequest = Await.result(requestSigner.addQueryString(request, kSecret, region, accessKeyID, service, 30, token), 10 seconds)
    val response = Await.result(post(authRequest), 10 seconds)
    response.status shouldBe StatusCodes.OK
  }

  it("should be a successful get request using the credential source") {
    val credentialSource = credentialProvider.getCredentials()
    val region = awsConfig.getString("region")
    val baseURI = awsConfig.getString("URI")
    val service = awsConfig.getString("service")
    val URI = s"$baseURI?Version=2013-10-15&Action=DescribeRegions"
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = URI
    )
    val authRequest = Await.result(requestSigner.addAuthorizationHeaderFromCredentialSource(request, region, service, credentialSource), 10 seconds)
    val response = Await.result(post(authRequest), 10 seconds)
    response.status shouldBe StatusCodes.OK
  }
}
