package lila.round

import chess.format.{ Forsyth, FEN, Uci }
import chess.Pos.posAt
import chess.{ Status, Role, Color, MoveOrDrop }
import scalaz.Validation.FlatMap._

import actorApi.round.{ HumanPlay, HumanFlick, DrawNo, TakebackNo, PlayResult, Cheat, ForecastPlay }
import akka.actor.ActorRef
import lila.game.{ Game, Pov, Progress, UciMemo }
import lila.hub.actorApi.map.Tell
import lila.hub.actorApi.round.MoveEvent
import scala.concurrent.duration._

private[round] final class Player(
    fishnetPlayer: lila.fishnet.Player,
    bus: lila.common.Bus,
    finisher: Finisher,
    cheatDetector: CheatDetector,
    uciMemo: UciMemo) {

  def human(play: HumanPlay, round: ActorRef)(pov: Pov)(implicit proxy: GameProxy): Fu[Events] = play match {
    case p@HumanPlay(playerId, uci, blur, lag, promiseOption) => pov match {
      case Pov(game, color) if game playableBy color =>
        p.trace.segmentSync("applyUci", "logic")(applyUci(game, uci, blur, lag + humanLag)).prefixFailuresWith(s"$pov ")
          .fold(errs => fufail(ClientError(errs.shows)), fuccess).flatMap {
            case (progress, moveOrDrop) =>
              p.trace.segment("save", "db")(proxy save progress) >>-
                (pov.game.hasAi ! uciMemo.add(pov.game, moveOrDrop)) >>-
                notifyMove(moveOrDrop, progress.game) >>
                progress.game.finished.fold(
                  moveFinish(progress.game, color) map { progress.events ::: _ }, {
                    cheatDetector(progress.game) addEffect {
                      case Some(color) => round ! Cheat(color)
                      case None =>
                        if (progress.game.playableByAi) requestFishnet(progress.game, round)
                        if (pov.opponent.isOfferingDraw) round ! DrawNo(pov.player.id)
                        if (pov.player.isProposingTakeback) round ! TakebackNo(pov.player.id)
                        moveOrDrop.left.toOption.ifTrue(pov.game.forecastable).foreach { move =>
                          round ! ForecastPlay(move)
                        }
                    } inject progress.events
                  }) >>- promiseOption.foreach(_.success(()))
          } addFailureEffect { e =>
            promiseOption.foreach(_ failure e)
          }
      case Pov(game, _) if game.finished           => fufail(ClientError(s"$pov game is finished"))
      case Pov(game, _) if game.aborted            => fufail(ClientError(s"$pov game is aborted"))
      case Pov(game, color) if !game.turnOf(color) => fufail(ClientError(s"$pov not your turn"))
      case _                                       => fufail(ClientError(s"$pov move refused for some reason"))
    }
  }

  def humanFlick(play: HumanFlick, round: ActorRef)(pov: Pov)(implicit proxy: GameProxy): Fu[Events] = play match {
    case p@HumanFlick(playerId, flick, col, lag, promiseOption) => pov match {
      case Pov(game, color) if (!game.finished && !game.aborted)=>
        p.trace.segmentSync("applyFlick", "logic")(applyFlick(game, flick, col, lag + humanLag)).prefixFailuresWith(s"$pov ")
          .fold(errs => fufail(ClientError(errs.shows)), fuccess).flatMap {
            case (progress, f, c) =>
              p.trace.segment("save", "db")(proxy save progress) >>-
                notifyFlick(f, c, progress.game) >>
                progress.game.finished.fold(
                  flickFinish(progress.game, color, f) map { progress.events ::: _ }, {
                    cheatDetector(progress.game) addEffect {
                      case Some(color) => round ! Cheat(color)
                      case None =>
                        if (progress.game.playableByAi) requestFishnet(progress.game, round)
                        if (pov.opponent.isOfferingDraw) round ! DrawNo(pov.player.id)
                        if (pov.player.isProposingTakeback) round ! TakebackNo(pov.player.id)
                    } inject progress.events
                  }) >>- promiseOption.foreach(_.success(()))
          }
      case Pov(game, _) if game.finished           => fufail(ClientError(s"$pov game is finished"))
      case Pov(game, _) if game.aborted            => fufail(ClientError(s"$pov game is aborted"))
      //case Pov(game, color) if !game.turnOf(color) => fufail(ClientError(s"$pov not your turn"))
      case _                                       => fufail(ClientError(s"$pov move refused for some reason"))
    }
  }
  
  def fishnet(game: Game, uci: Uci, currentFen: FEN, round: ActorRef)(implicit proxy: GameProxy): Fu[Events] =
    if (game.playable && game.player.isAi) {
      if (currentFen == FEN(Forsyth >> game.toChess))
        applyUci(game, uci, blur = false, lag = serverLag)
          .fold(errs => fufail(ClientError(errs.shows)), fuccess).flatMap {
            case (progress, moveOrDrop) =>
              proxy.save(progress) >>-
                uciMemo.add(progress.game, moveOrDrop) >>-
                notifyMove(moveOrDrop, progress.game) >>
                progress.game.finished.fold(
                  moveFinish(progress.game, game.turnColor) map { progress.events ::: _ },
                  fuccess(progress.events)
                )
          }
      else requestFishnet(game, round) >> fufail(FishnetError("Invalid AI move current FEN"))
    }
    else fufail(FishnetError("Not AI turn"))

  private def requestFishnet(game: Game, round: ActorRef): Funit = game.playableByAi ?? {
    if (game.turns <= fishnetPlayer.maxPlies) fishnetPlayer(game)
    else fuccess(round ! actorApi.round.ResignAi)
  }

  private val clientLag = 30.milliseconds
  private val serverLag = 5.milliseconds
  private val humanLag = clientLag + serverLag

  private def applyUci(game: Game, uci: Uci, blur: Boolean, lag: FiniteDuration) = (uci match {
    case Uci.Move(orig, dest, prom) => game.toChess.apply(orig, dest, prom, lag) map {
      case (ncg, move) => ncg -> (Left(move): MoveOrDrop)
    }
    case Uci.Drop(role, pos) => game.toChess.drop(role, pos, lag) map {
      case (ncg, drop) => ncg -> (Right(drop): MoveOrDrop)
    }
  }).map {
    case (newChessGame, moveOrDrop) =>
      game.update(newChessGame, moveOrDrop, blur, lag.some) -> moveOrDrop
  }

  private def applyFlick(game: Game, flick: (List[Int], String, Int), color: String, lag: FiniteDuration) = ((color match {
    case "white" => (Uci.Move.fromStrings("e2", "e4", None))
    case "black" => (Uci.Move.fromStrings("e7", "e5", None))
  }) match {
      case Some(Uci.Move(role, pos, prom)) =>
        game.toChess.apply(role, pos, prom, lag) map {
          case (ncg, move) => ncg -> (Left(move): MoveOrDrop)
      }
      case None => null
    }).map {
    case (newChessGame, moveOrDrop) =>
    game.clock match {
    case Some (c) =>
    (game.updateFlick (newChessGame.clock, newChessGame.turns, flick, color, lag.some), flick, color)
      //(game.update(newChessGame, moveOrDrop, false, lag.some), flick, moveOrDrop)
    case None => null
    }
  }
  private def notifyMove(moveOrDrop: MoveOrDrop, game: Game) {
    val color = moveOrDrop.fold(_.color, _.color)
    bus.publish(MoveEvent(
      gameId = game.id,
      fen = Forsyth exportBoard game.toChess.board,
      move = moveOrDrop.fold(_.toUci.keys, _.toUci.uci),
      mobilePushable = game.mobilePushable,
      alarmable = game.alarmable,
      opponentUserId = game.player(!color).userId,
      simulId = game.simulId
    ), 'moveEvent)
  }
  
  private def notifyFlick(flick: (List[Int], String, Int), color: String, game: Game) {
    bus.publish(MoveEvent(
      gameId = game.id,
      fen = Forsyth exportBoard game.toChess.board,
      move = "flick",
      mobilePushable = game.mobilePushable,
      alarmable = game.alarmable,
      opponentUserId = None,
      simulId = game.simulId
    ), 'moveEvent)
  }
  
  private def moveFinish(game: Game, color: Color)(implicit proxy: GameProxy): Fu[Events] = {
    lazy val winner = game.toChess.situation.winner
    game.status match {
      case Status.Mate                             => finisher.other(game, _.Mate, winner)
      case Status.VariantEnd                       => finisher.other(game, _.VariantEnd, winner)
      case status@(Status.Stalemate | Status.Draw) => finisher.other(game, _ => status)
      case _                                       => fuccess(Nil)
    }
  }

  private def flickFinish(game: Game, color: Color, flick: (List[Int], String, Int))(implicit proxy: GameProxy): Fu[Events] = {
    game.status match {
      case Status.VariantEnd => flick match {
        case (_, _, 1) => finisher.other(game, _.VariantEnd, Some(color))
        case (_, _, -1) => finisher.other(game, _.VariantEnd, Some(!color))

        case _ => fuccess(Nil)
      }
      case Status.Draw => flick match {
        case (_, _, 2) => finisher.other(game, _.Draw)
        case _ => fuccess(Nil)
      }
      case _ => fuccess(Nil)
    }
  }
}
