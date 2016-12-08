/*
 * SonarLint for IntelliJ IDEA
 * Copyright (C) 2015 SonarSource
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonarlint.intellij.analysis;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.MessageBus;

import java.util.Collection;

import org.sonarlint.intellij.issue.IssueProcessor;
import org.sonarlint.intellij.messages.TaskListener;
import org.sonarlint.intellij.trigger.TriggerType;
import org.sonarlint.intellij.ui.SonarLintConsole;

public class SonarLintJobManager extends AbstractProjectComponent {
  private final IssueProcessor processor;
  private final MessageBus messageBus;
  private final SonarLintStatus status;
  private final SonarLintConsole console;

  public SonarLintJobManager(Project project, IssueProcessor processor) {
    super(project);
    this.processor = processor;
    this.messageBus = project.getMessageBus();
    this.status = SonarLintStatus.get(this.myProject);
    this.console = SonarLintConsole.get(myProject);
  }

  /**
   * Runs SonarLint analysis asynchronously, as a background task, in the application's thread pool.
   * It might queue the submission of the job in the thread pool.
   * It won't block the current thread (in most cases, the event dispatch thread), but the contents of the file being analyzed
   * might be changed with the editor at the same time, resulting in a bad or failed placement of the issues in the editor.
   * @see #submitManual(Module, Collection, TriggerType, boolean)
   */
  public void submitBackground(Module m, Collection<VirtualFile> files, TriggerType trigger) {
    console.debug(String.format("[%s] %d file(s) submitted", trigger.getName(), files.size()));
    SonarLintJob newJob = new SonarLintJob(m, files, trigger);
    SonarLintTask task = new SonarLintTask(processor, newJob, true);
    runInEDT(task);
  }

  /**
   * Runs SonarLint analysis synchronously, if no manual (foreground) analysis is already on going.
   * If a foreground analysis is already on going, this method simply returns an empty AnalysisResult.
   * Once it starts, it will display a ProgressWindow with the EDT and run the analysis in a pooled thread.
   * @see #submitBackground(Module, Collection, TriggerType)
   */
  public void submitManual(Module m, Collection<VirtualFile> files, TriggerType trigger, boolean modal) {
    console.debug(String.format("[%s] %d file(s) submitted", trigger.getName(), files.size()));
    if (myProject.isDisposed() || !status.tryRun()) {
      return;
    }
    SonarLintJob newJob = new SonarLintJob(m, files, trigger);
    SonarLintUserTask task = new SonarLintUserTask(processor, newJob, status, modal);
    runInEDT(task);
  }

  private void runInEDT(SonarLintTask task) {
    final Application app = ApplicationManager.getApplication();
    // task needs to be submitted in the EDT because progress manager will create the related UI
    if (!app.isDispatchThread()) {
      app.invokeLater(() -> runTask(task));
    } else {
      runTask(task);
    }
  }

  /**
   * Runs task through the ProgressManager. Needs to be called from EDT.
   * Depending on the type of task (Modal or Backgroundable), it will prepare related UI and execute the task in the current thread
   * or in the Application thread pool.
   */
  private void runTask(SonarLintTask task) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    notifyStart(task.getJob());
    ProgressManager.getInstance().run(task);
  }

  private void notifyStart(SonarLintJob job) {
    messageBus.syncPublisher(TaskListener.SONARLINT_TASK_TOPIC).started(job);
  }
}
