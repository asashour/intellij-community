/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.vcs.log.ui.table;

import com.google.common.primitives.Ints;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import com.intellij.vcs.log.VcsCommitMetadata;
import com.intellij.vcs.log.data.DataGetter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.util.Arrays;
import java.util.List;

public abstract class CommitSelectionListener<T extends VcsCommitMetadata> implements ListSelectionListener {
  private final static Logger LOG = Logger.getInstance(CommitSelectionListener.class);
  @NotNull protected final VcsLogGraphTable myGraphTable;
  @NotNull private final DataGetter<? extends T> myCommitDetailsGetter;

  @Nullable private ListSelectionEvent myLastEvent;
  @Nullable private ProgressIndicator myLastRequest;

  protected CommitSelectionListener(@NotNull VcsLogGraphTable table,
                                    @NotNull DataGetter<? extends T> dataGetter) {
    myGraphTable = table;
    myCommitDetailsGetter = dataGetter;
  }

  @Override
  public void valueChanged(@Nullable ListSelectionEvent event) {
    if (event != null && event.getValueIsAdjusting()) return;

    myLastEvent = event;
    if (myLastRequest != null) myLastRequest.cancel();
    myLastRequest = null;

    ApplicationManager.getApplication().invokeLater(this::processEvent, o -> myLastEvent != event);
    onLoadingScheduled();
  }

  public void processEvent() {
    int rows = myGraphTable.getSelectedRowCount();
    if (rows < 1) {
      onLoadingStopped();
      onEmptySelection();
    }
    else {
      int[] toLoad = onSelection(myGraphTable.getSelectedRows());
      onLoadingStarted();

      EmptyProgressIndicator indicator = new EmptyProgressIndicator();
      myLastRequest = indicator;

      myCommitDetailsGetter.loadCommitsData(myGraphTable.getModel().convertToCommitIds(Ints.asList(toLoad)), detailsList -> {
        if (myLastRequest == indicator && !(indicator.isCanceled())) {
          if (toLoad.length != detailsList.size()) {
            LOG.error("Loaded incorrect number of details " + detailsList + " for selection " + Arrays.toString(toLoad));
          }
          myLastRequest = null;
          onDetailsLoaded(detailsList);
          onLoadingStopped();
        }
      }, t -> {
        if (myLastRequest == indicator && !(indicator.isCanceled())) {
          myLastRequest = null;
          onError(t);
          onLoadingStopped();
        }
      }, indicator);
    }
  }

  @RequiresEdt
  protected void onLoadingScheduled() {
  }

  @RequiresEdt
  protected abstract void onLoadingStarted();

  @RequiresEdt
  protected abstract void onLoadingStopped();

  @RequiresEdt
  protected abstract void onError(@NotNull Throwable error);

  @RequiresEdt
  protected abstract void onDetailsLoaded(@NotNull List<? extends T> detailsList);

  @RequiresEdt
  protected abstract int @NotNull [] onSelection(int @NotNull [] selection);

  @RequiresEdt
  protected abstract void onEmptySelection();
}