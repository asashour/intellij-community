package com.intellij.codeInsight.intention.impl;

import com.intellij.codeInsight.CodeInsightActionHandler;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.quickfix.QuickFixAction;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.IntentionManager;
import com.intellij.codeInsight.template.impl.TemplateManagerImpl;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiCodeFragment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.lang.annotation.HighlightSeverity;

import java.util.ArrayList;
import java.util.List;

/**
 * @author mike
 */
public class ShowIntentionActionsHandler implements CodeInsightActionHandler {
  public void invoke(Project project, Editor editor, PsiFile file) {
    PsiDocumentManager.getInstance(project).commitAllDocuments();

    if (HintManager.getInstance().performCurrentQuestionAction()) return;

    if (!file.isWritable()) return;
    if (file instanceof PsiCodeFragment) return;

    TemplateState state = TemplateManagerImpl.getTemplateState(editor);
    if (state != null && !state.isFinished()) return;

    final IntentionAction[] intentionActions = IntentionManager.getInstance(project).getIntentionActions();

    ArrayList<HighlightInfo.IntentionActionDescriptor> intentionsToShow = new ArrayList<HighlightInfo.IntentionActionDescriptor>();
    ArrayList<HighlightInfo.IntentionActionDescriptor> fixesToShow = new ArrayList<HighlightInfo.IntentionActionDescriptor>();
    DaemonCodeAnalyzer codeAnalyzer = DaemonCodeAnalyzer.getInstance(project);
    for (IntentionAction action : intentionActions) {
      if (action instanceof IntentionActionComposite) {
        if (action instanceof QuickFixAction) {
          List<HighlightInfo.IntentionActionDescriptor> actions = ((IntentionActionComposite)action).getAvailableActions(editor, file, -1);
          for (HighlightInfo.IntentionActionDescriptor descriptor : actions) {
            HighlightInfo info = codeAnalyzer.findHighlightByOffset(editor.getDocument(), editor.getCaretModel().getOffset(), true);
            if (info == null || info.getSeverity() == HighlightSeverity.ERROR) {
              fixesToShow.add(descriptor);
            }
            else {
              intentionsToShow.add(descriptor);
            }
          }
        }
        else {
          intentionsToShow.addAll(((IntentionActionComposite)action).getAvailableActions(editor, file, -1));
        }
      }
      else if (action.isAvailable(project, editor, file)) {
        List<IntentionAction> enableDisableIntentionAction = new ArrayList<IntentionAction>();
        enableDisableIntentionAction.add(new IntentionHintComponent.EnableDisableIntentionAction(action));
        intentionsToShow.add(new HighlightInfo.IntentionActionDescriptor(action,enableDisableIntentionAction, null));
      }
    }

    if (!intentionsToShow.isEmpty() || !fixesToShow.isEmpty()) {
      IntentionHintComponent.showIntentionHint(project, file, editor, intentionsToShow, fixesToShow, true);
    }
    else {
//      Toolkit.getDefaultToolkit().beep();
    }
  }

  public boolean startInWriteAction() {
    return false;
  }
}
