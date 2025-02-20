// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.codeInspection;

import com.intellij.lang.LangBundle;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.properties.charset.Native2AsciiCharset;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.util.io.IOUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class NonAsciiCharactersInspection extends LocalInspectionTool {
  public boolean CHECK_FOR_NOT_ASCII_IDENTIFIER_NAME = true;
  public boolean CHECK_FOR_NOT_ASCII_STRING_LITERAL;
  public boolean CHECK_FOR_NOT_ASCII_COMMENT;
  
  public boolean CHECK_FOR_DIFFERENT_LANGUAGES_IN_IDENTIFIER_NAME = true;
  public boolean CHECK_FOR_DIFFERENT_LANGUAGES_IN_STRING;
  public boolean CHECK_FOR_FILES_CONTAINING_BOM;

  @Override
  @Nls
  @NotNull
  public String getGroupDisplayName() {
    return InspectionsBundle.message("group.names.internationalization.issues");
  }

  @Override
  @NonNls
  @NotNull
  public String getShortName() {
    return "NonAsciiCharacters";
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly, @NotNull LocalInspectionToolSession session) {
    if (!isFileWorthIt(session.getFile())) return PsiElementVisitor.EMPTY_VISITOR;
    return new PsiElementVisitor() {
      @Override
      public void visitElement(@NotNull PsiElement element) {
        if (CHECK_FOR_NOT_ASCII_IDENTIFIER_NAME || CHECK_FOR_DIFFERENT_LANGUAGES_IN_IDENTIFIER_NAME) {
          PsiElement parent = element.getParent();
          PsiElement identifier;
          if (parent instanceof PsiNameIdentifierOwner &&
              (identifier = ((PsiNameIdentifierOwner)parent).getNameIdentifier()) != null) {
            // Groovy has this twisted PSI where method.geNameIdentifier() is some random light element
            String text = element.getText();
            if (identifier == element || text.equals(identifier.getText())) {
              if (CHECK_FOR_NOT_ASCII_IDENTIFIER_NAME) {
                checkAscii(element, text, holder);
              }
              if (CHECK_FOR_DIFFERENT_LANGUAGES_IN_IDENTIFIER_NAME) {
                checkSameLanguage(element, text, holder);
              }
            }
          }
        }
        if (CHECK_FOR_NOT_ASCII_COMMENT) {
          if (element instanceof PsiComment) {
            checkAsciiRange(element, element.getText(), holder, Kind.COMMENT);
          }
        }
        if (element instanceof PsiLiteralValue) {
          String text = element.getText();
          if (CHECK_FOR_NOT_ASCII_STRING_LITERAL) {
            checkAsciiRange(element, text, holder, Kind.LITERAL);
          }
          if (CHECK_FOR_DIFFERENT_LANGUAGES_IN_STRING) {
            checkSameLanguage(element, text, holder);
          }
        }
      }

      @Override
      public void visitFile(@NotNull PsiFile file) {
        super.visitFile(file);
        if (CHECK_FOR_FILES_CONTAINING_BOM) {
          if (file.getViewProvider().getBaseLanguage() != file.getLanguage()) {
            // don't warn multiple times on files which have multiple views like PHP and JSP
            return;
          }
          VirtualFile virtualFile = file.getVirtualFile();
          byte[] bom = virtualFile == null ? null : virtualFile.getBOM();
          if (bom != null) {
            String hex = IntStream.range(0, bom.length)
              .map(i -> bom[i])
              .mapToObj(b -> StringUtil.toUpperCase(Integer.toString(b & 0x00ff, 16)))
              .collect(Collectors.joining());
            Charset charsetFromBOM = CharsetToolkit.guessFromBOM(bom);
            final String signature = charsetFromBOM == null
                                     ? ""
                                     : LangBundle.message("inspection.message.charset.signature", charsetFromBOM.displayName());
            holder.registerProblem(file, LangBundle.message("inspection.message.file.contains.bom", hex, signature));
          }
        }
      }
    };
  }

  private static boolean isFileWorthIt(@NotNull PsiFile file) {
    if (InjectedLanguageManager.getInstance(file.getProject()).isInjectedFragment(file)) return false;
    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) return false;
    CharSequence text = file.getViewProvider().getContents();

    Charset charset = LoadTextUtil.extractCharsetFromFileContent(file.getProject(), virtualFile, text);

    // no sense in checking transparently decoded file: all characters there are already safely encoded
    return !(charset instanceof Native2AsciiCharset);
  }


  private static void checkSameLanguage(@NotNull PsiElement element, @NotNull String text, @NotNull ProblemsHolder holder) {
    Set<Character.UnicodeScript> scripts = text.codePoints()
      .mapToObj(Character.UnicodeScript::of)
      .filter(script -> !script.equals(Character.UnicodeScript.COMMON))
      .collect(Collectors.toSet());
    if (scripts.size() > 1) {
      List<Character.UnicodeScript> list = new ArrayList<>(scripts);
      Collections.sort(list); // a little bit of stability
      holder.registerProblem(element, LangBundle.message("inspection.message.symbols.from.different.languages.found", list),
                             ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
    }
  }

  private static void checkAscii(@NotNull PsiElement element, @NotNull String text, @NotNull ProblemsHolder holder) {
    if (!IOUtil.isAscii(text)) {
      holder.registerProblem(element, LangBundle.message("inspection.message.non.ascii.characters.in", Kind.IDENTIFIER.ordinal()));
    }
  }

  private static void checkAsciiRange(@NotNull PsiElement element,
                                      @NotNull String text,
                                      @NotNull ProblemsHolder holder,
                                      @NotNull Kind kind) {
    int errorCount = 0;
    int start = -1;
    for (int i = 0; i <= text.length(); i++) {
      char c = i >= text.length() ? 0 : text.charAt(i);
      if (i == text.length() || c<128) {
        if (start != -1) {
          TextRange range = new TextRange(start, i);
          holder.registerProblem(element, range, LangBundle.message("inspection.message.non.ascii.characters.in", kind.ordinal()));
          start = -1;
          //do not report too many errors
          if (errorCount++ > 200) break;
        }
      }
      else if (start == -1) {
        start = i;
      }
    }
  }

  @NotNull
  @Override
  public JComponent createOptionsPanel() {
    return new NonAsciiCharactersInspectionFormUi(this).getPanel();
  }

  private enum Kind {
    IDENTIFIER, COMMENT, LITERAL
  }
}
