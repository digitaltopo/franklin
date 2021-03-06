package com.azavea.franklin.api

import cats.effect._
import cats.effect._
import cats.implicits._
import com.azavea.franklin.api.commands.{ApiConfig, Commands, DatabaseConfig}
import com.azavea.franklin.api.endpoints.{
  CollectionEndpoints,
  CollectionItemEndpoints,
  LandingPageEndpoints,
  SearchEndpoints,
  TileEndpoints
}
import com.azavea.franklin.api.services._
import com.azavea.franklin.datamodel.LandingPage
import com.google.common.util.concurrent.ThreadFactoryBuilder
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.blaze._
import org.http4s.server.middleware._
import org.http4s.server.{Router, Server => HTTP4sServer}
import sttp.tapir.docs.openapi._
import sttp.tapir.openapi.circe.yaml._
import sttp.tapir.swagger.http4s.SwaggerHttp4s

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext

import java.util.concurrent.Executors

object Server extends IOApp {

  val franklinIO: ContextShift[IO] = IO.contextShift(
    ExecutionContext.fromExecutor(
      Executors.newCachedThreadPool(
        new ThreadFactoryBuilder().setNameFormat("raster-io-%d").build()
      )
    )
  )

  override implicit val contextShift: ContextShift[IO] = franklinIO

  private val banner: List[String] =
    """
   $$$$$$$$$
$$$$$$$$$$$$   ________                            __        __  __
$$$$$$$$$$$$  /        |                          /  |      /  |/  |
$$            $$$$$$$$/______   ______   _______  $$ |   __ $$ |$$/  _______
 $$$$$$$$     $$ |__  /      \ /      \ /       \ $$ |  /  |$$ |/  |/       \
$$$$$$$$      $$    |/$$$$$$  |$$$$$$  |$$$$$$$  |$$ |_/$$/ $$ |$$ |$$$$$$$  |
$$$$$$$       $$$$$/ $$ |  $$/ /    $$ |$$ |  $$ |$$   $$<  $$ |$$ |$$ |  $$ |
$             $$ |   $$ |     /$$$$$$$ |$$ |  $$ |$$$$$$  \ $$ |$$ |$$ |  $$ |
$$$$$$        $$ |   $$ |     $$    $$ |$$ |  $$ |$$ | $$  |$$ |$$ |$$ |  $$ |
$$$$$         $$/    $$/       $$$$$$$/ $$/   $$/ $$/   $$/ $$/ $$/ $$/   $$/
$$$$
""".split("\n").toList

  private def createServer(
      apiConfig: ApiConfig,
      dbConfig: DatabaseConfig
  ): Resource[IO, HTTP4sServer[IO]] =
    for {
      connectionEc  <- ExecutionContexts.fixedThreadPool[IO](2)
      transactionEc <- ExecutionContexts.cachedThreadPool[IO]
      xa <- HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver",
        dbConfig.jdbcUrl,
        dbConfig.dbUser,
        dbConfig.dbPass,
        connectionEc,
        Blocker.liftExecutionContext(transactionEc)
      )
      collectionItemEndpoints = new CollectionItemEndpoints(
        apiConfig.defaultLimit,
        apiConfig.enableTransactions,
        apiConfig.enableTiles
      )
      collectionEndpoints = new CollectionEndpoints(
        apiConfig.enableTransactions,
        apiConfig.enableTiles
      )
      landingPage = new LandingPageEndpoints[IO]()
      allEndpoints = landingPage.endpoints ++ collectionEndpoints.endpoints ++ collectionItemEndpoints.endpoints ++ SearchEndpoints.endpoints ++ new TileEndpoints(
        apiConfig.enableTiles
      ).endpoints
      docs              = allEndpoints.toOpenAPI("Franklin", "0.0.1")
      docRoutes         = new SwaggerHttp4s(docs.toYaml, "open-api", "spec.yaml").routes[IO]
      landingPageRoutes = new LandingPageService[IO](apiConfig, xa).routes
      searchRoutes = new SearchService[IO](
        apiConfig.apiHost,
        apiConfig.defaultLimit,
        apiConfig.enableTiles,
        xa
      ).routes
      staticService = new StaticService[IO](Blocker.liftExecutionContext(transactionEc))
      tileRoutes    = new TileService[IO](apiConfig.apiHost, apiConfig.enableTiles, xa).routes
      collectionRoutes = new CollectionsService[IO](xa, apiConfig).routes <+> new CollectionItemsService[
        IO
      ](
        xa,
        apiConfig
      ).routes
      router = CORS(
        Router(
          "/" -> ResponseLogger.httpRoutes(false, false)(
            landingPageRoutes <+> collectionRoutes <+> searchRoutes <+> tileRoutes <+> docRoutes
          ),
          "/assets" -> staticService.routes
        )
      ).orNotFound
      serverBuilderBlocker <- Blocker[IO]
      server <- {
        BlazeServerBuilder[IO](serverBuilderBlocker.blockingContext)
          .bindHttp(apiConfig.internalPort.value, "0.0.0.0")
          .withConnectorPoolSize(128)
          .withBanner(banner)
          .withHttpApp(router)
          .resource
      }
    } yield {
      server
    }

  override def run(args: List[String]): IO[ExitCode] = {
    import Commands._

    applicationCommand.parse(args) map {
      case RunServer(apiConfig, dbConfig) if !apiConfig.runMigrations =>
        createServer(apiConfig, dbConfig)
          .use(_ => IO.never)
          .as(ExitCode.Success)
      case RunServer(apiConfig, dbConfig) =>
        runMigrations(dbConfig) *>
          createServer(apiConfig, dbConfig).use(_ => IO.never).as(ExitCode.Success)
      case RunMigrations(config) => runMigrations(config)
      case RunCatalogImport(catalogRoot, dbConfig, dryRun) =>
        runCatalogImport(catalogRoot, dbConfig, dryRun) map { _ => ExitCode.Success }
      case RunItemsImport(collectionId, itemUris, dbConfig, dryRun) => {
        runStacItemImport(collectionId, itemUris, dbConfig, dryRun) map {
          case Left(error) => {
            println(s"Import failed: $error")
            ExitCode.Error
          }
          case Right(items) => {
            println(s"Import succesful: ${items.size} items imported")
            ExitCode.Success
          }
        }
      }
    } match {
      case Left(e) =>
        IO {
          println(e.toString())
        } map { _ => ExitCode.Error }
      case Right(s) => s
    }
  }
}
