package mesosphere.marathon.upgrade

import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.{ AppStopCanceledException, SchedulerActions }
import akka.event.EventStream
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.Promise
import akka.actor._
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.tasks.TaskTracker
import scala.collection.immutable.Set
import mesosphere.mesos.protos.TaskID
import mesosphere.mesos.protos.Implicits._

class AppStopActor(
    driver: SchedulerDriver,
    scheduler: SchedulerActions,
    taskTracker: TaskTracker,
    eventBus: EventStream,
    app: AppDefinition,
    promise: Promise[Unit]) extends Actor with ActorLogging {

  var idsToKill = taskTracker.fetchApp(app.id).tasks.map(_.getId).to[Set]

  override def preStart(): Unit = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    idsToKill.foreach(x => driver.killTask(TaskID(x)))
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(new AppStopCanceledException("The app stop has been cancelled"))
  }

  def receive = {
    case MesosStatusUpdateEvent(_, taskId, "TASK_KILLED", _, _, _, _, _, _) if idsToKill(taskId) =>
      idsToKill -= taskId
      log.info(s"Task $taskId has been killed. Waiting for ${idsToKill.size} more tasks to be killed.")
      checkFinished()

    case MesosStatusUpdateEvent(_, taskId, "TASK_LOST", _, _, _, _, _, _) if idsToKill(taskId) =>
      idsToKill -= taskId
      log.warning(s"Task $taskId should have been killed but was lost, removing it from the list. Waiting for ${idsToKill.size} more tasks to be killed.")
      checkFinished()

    case x: MesosStatusUpdateEvent => log.debug(s"Received $x")
  }

  def checkFinished(): Unit = {
    if (idsToKill.isEmpty) {
      scheduler.stopApp(driver, app)
      promise.success(())
      context.stop(self)
    }
  }
}
