package indigo.gameengine

import indigo.Millis
import indigo.GameTime
import indigo.gameengine.assets._
import indigo.gameengine.audio.AudioPlayer
import indigo.gameengine.events._
import indigo.gameengine.scenegraph._
import indigo.gameengine.scenegraph.animation._
import indigo.gameengine.scenegraph.datatypes.FontInfo
import indigo.gameengine.subsystems.{SubSystem, SubSystemsRegister}
import indigo.renderer._
import indigo.runtime._
import indigo.runtime.metrics._
import indigo.shared.{AssetType, GameConfig}
import org.scalajs.dom
import org.scalajs.dom.html.Canvas

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import indigo.EqualTo._

final case class GameEngine[StartupData, StartupError, GameModel, ViewModel](
    config: GameConfig,
    configAsync: Future[Option[GameConfig]],
    assets: Set[AssetType],
    assetsAsync: Future[Set[AssetType]],
    fonts: Set[FontInfo],
    animations: Set[Animation],
    subSystems: Set[SubSystem],
    initialise: AssetCollection => Startup[StartupError, StartupData],
    initialModel: StartupData => GameModel,
    updateModel: (GameTime, GameModel) => GlobalEvent => Outcome[GameModel],
    initialViewModel: StartupData => GameModel => ViewModel,
    updateViewModel: (GameTime, GameModel, ViewModel, FrameInputEvents) => Outcome[ViewModel],
    updateView: (GameTime, GameModel, ViewModel, FrameInputEvents) => SceneUpdateFragment
) {

  def start(): Unit =
    GameEngine.start(
      config,
      configAsync,
      assets,
      assetsAsync,
      fonts,
      animations,
      subSystems,
      initialise,
      initialModel,
      updateModel,
      initialViewModel,
      updateViewModel,
      updateView
    )

}

object GameEngine {

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def start[StartupData, StartupError, GameModel, ViewModel](
      config: GameConfig,
      configAsync: Future[Option[GameConfig]],
      assets: Set[AssetType],
      assetsAsync: Future[Set[AssetType]],
      fonts: Set[FontInfo],
      animations: Set[Animation],
      subSystems: Set[SubSystem],
      initialise: AssetCollection => Startup[StartupError, StartupData],
      initialModel: StartupData => GameModel,
      updateModel: (GameTime, GameModel) => GlobalEvent => Outcome[GameModel],
      initialViewModel: StartupData => GameModel => ViewModel,
      updateViewModel: (GameTime, GameModel, ViewModel, FrameInputEvents) => Outcome[ViewModel],
      updateView: (GameTime, GameModel, ViewModel, FrameInputEvents) => SceneUpdateFragment
  ): Unit = {

    IndigoLogger.info("Starting Indigo")

    // Arrange config
    configAsync.map(_.getOrElse(config)).foreach { gameConfig =>
      IndigoLogger.info("Configuration: " + gameConfig.asString)

      if ((gameConfig.viewport.width % 2 !== 0) || (gameConfig.viewport.height % 2 !== 0))
        IndigoLogger.info(
          "WARNING: Setting a resolution that has a width and/or height that is not divisible by 2 could cause stretched graphics!"
        )

      // Arrange assets
      assetsAsync.flatMap(aa => AssetManager.loadAssets(aa ++ assets)).foreach { assetCollection =>
        IndigoLogger.info("Asset load complete")

        val audioPlayer: AudioPlayer =
          GameEngine.startAudioPlayer(assetCollection.sounds)

        implicit val metrics: Metrics =
          Metrics.getInstance(gameConfig.advanced.recordMetrics, gameConfig.advanced.logMetricsReportIntervalMs)

        implicit val globalEventStream: GlobalEventStream =
          GlobalEventStream.default(audioPlayer)

        implicit val globalSignals: GlobalSignals =
          GlobalSignals.default

        val startupData: Startup[StartupError, StartupData] = initialise(assetCollection)

        val subSystemsRegister: SubSystemsRegister =
          SubSystemsRegister
            .add(SubSystemsRegister.empty, subSystems.toList ++ startupData.additionalSubSystems.toList)

        val x: GameContext[Long => Int] =
          for {
            _                   <- GameEngine.registerAnimations(animations ++ startupData.additionalAnimations)
            _                   <- GameEngine.registerFonts(fonts ++ startupData.additionalFonts)
            textureAtlas        <- GameEngine.createTextureAtlas(assetCollection)
            loadedTextureAssets <- GameEngine.extractLoadedTextures(textureAtlas)
            assetMapping        <- GameEngine.setupAssetMapping(textureAtlas)
            startUpSuccessData  <- GameEngine.initialisedGame(startupData)
            canvas              <- GameEngine.createCanvas(gameConfig)
            _                   <- GameEngine.listenToWorldEvents(canvas, gameConfig.magnification)
            renderer            <- GameEngine.startRenderer(gameConfig, loadedTextureAssets, canvas)
            gameLoopInstance <- GameEngine.initialiseGameLoop(
              Millis(System.currentTimeMillis()),
              gameConfig,
              assetMapping,
              renderer,
              audioPlayer,
              subSystemsRegister,
              initialModel(startUpSuccessData),
              updateModel,
              initialViewModel(startUpSuccessData),
              updateViewModel,
              updateView
            )
          } yield gameLoopInstance.loop(0)

        x.attemptRun match {
          case Right(f) =>
            IndigoLogger.info("Starting main loop, there will be no more info log messages.")
            IndigoLogger.info("You may get first occurrence error logs.")
            dom.window.requestAnimationFrame(t => f(t.toLong))

            ()

          case Left(e) =>
            IndigoLogger.error("Error during startup")
            IndigoLogger.error(e.getMessage)

            ()
        }
      }

    }
  }

  def registerAnimations(animations: Set[Animation]): GameContext[Unit] =
    GameContext.delay(animations.foreach(AnimationsRegister.register))

  def registerFonts(fonts: Set[FontInfo]): GameContext[Unit] =
    GameContext.delay(fonts.foreach(FontRegister.register))

  def createTextureAtlas(assetCollection: AssetCollection): GameContext[TextureAtlas] =
    GameContext.delay(
      TextureAtlas.create(
        assetCollection.images.map(i => ImageRef(i.name, i.data.width, i.data.height)),
        AssetManager.findByName(assetCollection),
        TextureAtlasFunctions.createAtlasData
      )
    )

  def extractLoadedTextures(textureAtlas: TextureAtlas): GameContext[List[LoadedTextureAsset]] =
    GameContext.delay(
      textureAtlas.atlases.toList
        .map(a => a._2.imageData.map(data => LoadedTextureAsset(a._1.id, data)))
        .collect { case Some(s) => s }
    )

  def setupAssetMapping(textureAtlas: TextureAtlas): GameContext[AssetMapping] =
    GameContext.delay(
      AssetMapping(
        mappings = textureAtlas.legend
          .map { p =>
            p._1 -> TextureRefAndOffset(
              atlasName = p._2.id.id,
              atlasSize = textureAtlas.atlases.get(p._2.id).map(_.size.value).map(Vector2.apply).getOrElse(Vector2.one),
              offset = p._2.offset
            )
          }
      )
    )

  def initialisedGame[StartupError, StartupData](startupData: Startup[StartupError, StartupData]): GameContext[StartupData] =
    startupData match {
      case e: Startup.Failure[_] =>
        IndigoLogger.info("Game initialisation failed")
        IndigoLogger.info(e.report)
        GameContext.raiseError[StartupData](new Exception("Game aborted due to start up failure"))

      case x: Startup.Success[StartupData] =>
        IndigoLogger.info("Game initialisation succeeded")
        GameContext.delay(x.success)
    }

  def createCanvas(gameConfig: GameConfig): GameContext[Canvas] =
    Option(dom.document.getElementById("indigo-container")) match {
      case None =>
        GameContext.raiseError[Canvas](new Exception("""Parent element "indigo-container" could not be found on page."""))

      case Some(parent) =>
        GameContext.delay(Renderer.createCanvas(gameConfig.viewport.width, gameConfig.viewport.height, parent))
    }

  def listenToWorldEvents(canvas: Canvas, magnification: Int)(implicit globalEventStream: GlobalEventStream): GameContext[Unit] = {
    IndigoLogger.info("Starting world events")
    GameContext.delay(WorldEvents(canvas, magnification))
  }

  def startRenderer(gameConfig: GameConfig, loadedTextureAssets: List[LoadedTextureAsset], canvas: Canvas): GameContext[IRenderer] =
    GameContext.delay {
      IndigoLogger.info("Starting renderer")
      Renderer(
        RendererConfig(
          viewport = Viewport(gameConfig.viewport.width, gameConfig.viewport.height),
          clearColor = gameConfig.clearColor,
          magnification = gameConfig.magnification
        ),
        loadedTextureAssets,
        canvas
      )
    }

  def startAudioPlayer(sounds: List[LoadedAudioAsset]): AudioPlayer =
    AudioPlayer(sounds)

  def initialiseGameLoop[GameModel, ViewModel](
      launchTime: Millis,
      gameConfig: GameConfig,
      assetMapping: AssetMapping,
      renderer: IRenderer,
      audioPlayer: AudioPlayer,
      subSystemsRegister: SubSystemsRegister,
      initialModel: GameModel,
      updateModel: (GameTime, GameModel) => GlobalEvent => Outcome[GameModel],
      initialViewModel: GameModel => ViewModel,
      updateViewModel: (GameTime, GameModel, ViewModel, FrameInputEvents) => Outcome[ViewModel],
      updateView: (GameTime, GameModel, ViewModel, FrameInputEvents) => SceneUpdateFragment
  )(implicit metrics: Metrics, globalEventStream: GlobalEventStream, globalSignals: GlobalSignals): GameContext[GameLoop[GameModel, ViewModel]] =
    GameContext.delay(
      new GameLoop[GameModel, ViewModel](
        launchTime: Millis,
        gameConfig,
        assetMapping,
        renderer,
        audioPlayer,
        subSystemsRegister,
        initialModel,
        updateModel,
        initialViewModel(initialModel),
        updateViewModel,
        updateView
      )
    )

}
