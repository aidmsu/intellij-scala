package org.jetbrains.sbt.shell

import java.awt.event.KeyEvent
import java.util
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon

import com.intellij.execution.console._
import com.intellij.execution.filters.UrlFilter.UrlFilterProvider
import com.intellij.execution.filters._
import com.intellij.execution.process.{OSProcessHandler, ProcessHandler}
import com.intellij.execution.runners.AbstractConsoleRunnerWithHistory
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.actions.CloseAction
import com.intellij.execution.{ExecutionManager, Executor}
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem._
import com.intellij.openapi.project.{DumbAwareAction, Project}
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ui.UIUtil

import scala.collection.JavaConverters._

/**
  * Created by jast on 2016-5-29.
  */
class SbtShellRunner(project: Project, consoleTitle: String)
  extends AbstractConsoleRunnerWithHistory[LanguageConsoleImpl](project, consoleTitle, project.getBaseDir.getCanonicalPath) {

  private val myConsoleView: LanguageConsoleImpl = {
    val cv = new LanguageConsoleImpl(project, SbtShellFileType.getName, SbtShellLanguage)
    cv.getConsoleEditor.setOneLineMode(true)

    // exception file links
    cv.addMessageFilter(new ExceptionFilter(GlobalSearchScope.allScope(project)))

    // url links
    new UrlFilterProvider().getDefaultFilters(project).foreach(cv.addMessageFilter)

    // file links
    val patternMacro = s"${RegexpFilter.FILE_PATH_MACROS}:${RegexpFilter.LINE_MACROS}:\\s"
    val pattern = new RegexpFilter(project, patternMacro).getPattern
    import PatternHyperlinkPart._
    // FILE_PATH_MACROS includes a capturing group at the beginning that the format only can handle if the first linkPart is null
    val format = new PatternHyperlinkFormat(pattern, false, false, null, PATH, LINE)
    val dataFinder = new PatternBasedFileHyperlinkRawDataFinder(Array(format))
    val fileFilter = new PatternBasedFileHyperlinkFilter(project, null, dataFinder)
    cv.addMessageFilter(fileFilter)

    cv
  }

  private lazy val processManager = SbtProcessManager.forProject(project)

  // lazy so that getProcessHandler will return something initialized when this is first accessed
  private lazy val myConsoleExecuteActionHandler: SbtShellExecuteActionHandler =
    new SbtShellExecuteActionHandler(getProcessHandler)

  // the process handler should only be used to access the running process!
  // SbtProcessComponent is solely responsible for destroying/respawning
  private lazy val myProcessHandler = processManager.acquireShellProcessHandler

  override def createProcessHandler(process: Process): OSProcessHandler = myProcessHandler

  override def createConsoleView(): LanguageConsoleImpl = myConsoleView

  override def createProcess(): Process = myProcessHandler.getProcess

  override def initAndRun(): Unit = {
    super.initAndRun()
    UIUtil.invokeLaterIfNeeded(new Runnable {
      override def run(): Unit = {
        // assume initial state is Working
        // FIXME this is not correct when shell process was started without view
        myConsoleView.setPrompt("X")

        // TODO update icon with ready/working state
        val shellPromptChanger = new SbtShellReadyListener(
          whenReady = myConsoleView.setPrompt(">"),
          whenWorking = myConsoleView.setPrompt("X")
        )
        SbtShellCommunication.forProject(project).attachListener(shellPromptChanger)
      }
    })
  }


  object SbtShellRootType extends ConsoleRootType("sbt.shell", getConsoleTitle)

  override def createExecuteActionHandler(): SbtShellExecuteActionHandler = {
    val historyController = new ConsoleHistoryController(SbtShellRootType, null, getConsoleView)
    historyController.install()

    myConsoleExecuteActionHandler
  }

  override def fillToolBarActions(toolbarActions: DefaultActionGroup,
                                  defaultExecutor: Executor,
                                  contentDescriptor: RunContentDescriptor): util.List[AnAction] = {

    val myToolbarActions = List(
      new RestartAction(this, defaultExecutor, contentDescriptor),
      new CloseAction(defaultExecutor, contentDescriptor, project),
      new ExecuteTaskAction("products", Option(AllIcons.Actions.Compile))
    )

    val allActions = List(
      createAutoCompleteAction(),
      createConsoleExecAction(myConsoleExecuteActionHandler)
    ) ++ myToolbarActions

    toolbarActions.addAll(myToolbarActions.asJava)
    allActions.asJava
  }

  override def getConsoleIcon: Icon = SbtShellRunner.ICON

  def focusShell(): Unit = {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(getExecutor.getToolWindowId)
    toolWindow.activate(null, true)
    val content = toolWindow.getContentManager.findContent(consoleTitle)
    if (content != null)
      toolWindow.getContentManager.setSelectedContent(content, true)
  }


  def createAutoCompleteAction(): AnAction = {
    val action = new AutoCompleteAction
    action.registerCustomShortcutSet(KeyEvent.VK_TAB, 0, null)
    action.getTemplatePresentation.setVisible(false)
    action
  }

  /** A new instance of the runner with the same constructor params as this one, but fresh state. */
  def respawn: SbtShellRunner = {
    processManager.restartProcess()
    new SbtShellRunner(project, consoleTitle)
  }

}

object SbtShellRunner {
  // TODO migrate sbt icons to where all the other icons are
  val ICON: Icon = IconLoader.getIcon("/sbt.png")
}

class AutoCompleteAction extends DumbAwareAction {
  override def actionPerformed(e: AnActionEvent): Unit = {
    // TODO call code completion (ctrl+space by default)
  }
}

class RestartAction(runner: SbtShellRunner, executor: Executor, contentDescriptor: RunContentDescriptor) extends DumbAwareAction {
  copyFrom(ActionManager.getInstance.getAction(IdeActions.ACTION_RERUN))

  val templatePresentation: Presentation = getTemplatePresentation
  templatePresentation.setIcon(AllIcons.Actions.Restart)
  templatePresentation.setText("Restart SBT Shell") // TODO i18n / language-bundle
  templatePresentation.setDescription(null)

  def actionPerformed(e: AnActionEvent): Unit = {
    val removed = ExecutionManager.getInstance(runner.getProject)
      .getContentManager
      .removeRunContent(executor, contentDescriptor)

    if (removed) runner.respawn.initAndRun()
  }

  override def update(e: AnActionEvent) {}
}

class SbtShellExecuteActionHandler(processHandler: ProcessHandler)
  extends ProcessBackedConsoleExecuteActionHandler(processHandler, true)


class ExecuteTaskAction(task: String, icon: Option[Icon]) extends DumbAwareAction {

  getTemplatePresentation.setIcon(icon.orNull)
  getTemplatePresentation.setText(s"Execute $task")

  override def actionPerformed(e: AnActionEvent): Unit = {
    // TODO execute with indicator
    SbtShellCommunication.forProject(e.getProject).command(task)
  }
}