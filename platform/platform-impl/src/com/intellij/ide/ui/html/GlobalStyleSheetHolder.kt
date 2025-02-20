// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.ui.html

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsScheme
import org.jetbrains.annotations.ApiStatus.Internal
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * Holds a reference to global CSS style sheet that should be used by [HTMLEditorKit] to properly render everything
 * with respect to Look-and-Feel
 *
 * Based on a default swing stylesheet at javax/swing/text/html/default.css
 */
@Internal
object GlobalStyleSheetHolder {

  private val globalStyleSheet = StyleSheet()

  private var swingStyleSheetHandled = false

  private var currentLafStyleSheet: StyleSheet? = null

  /**
   * Returns a global style sheet that is dynamically updated when LAF changes
   */
  @JvmStatic
  fun getGlobalStyleSheet() = StyleSheet().apply {
    // return a linked sheet to avoid mutation of a global variable
    addStyleSheet(globalStyleSheet)
  }

  /**
   * Populate global stylesheet with LAF-based overrides
   */
  @JvmStatic
  private fun updateGlobalStyleSheet() {
    if (!swingStyleSheetHandled) {
      // get the default JRE CSS and ...
      val kit = HTMLEditorKit()
      val defaultSheet = kit.styleSheet
      globalStyleSheet.addStyleSheet(defaultSheet)

      // ... set a new default sheet
      kit.styleSheet = getGlobalStyleSheet()
      swingStyleSheetHandled = true
    }

    val currentSheet = currentLafStyleSheet
    if (currentSheet != null) {
      globalStyleSheet.removeStyleSheet(currentSheet)
    }

    val newStyle = StyleSheet().apply {
      addRule(LafCssProvider.getCssForCurrentLaf())
      addRule(LafCssProvider.getCssForCurrentEditorScheme())
    }
    currentLafStyleSheet = newStyle
    globalStyleSheet.addStyleSheet(newStyle)
  }

  internal class UpdateListener : LafManagerListener, EditorColorsListener {
    override fun globalSchemeChange(scheme: EditorColorsScheme?) = updateGlobalStyleSheet()
    override fun lookAndFeelChanged(source: LafManager) = updateGlobalStyleSheet()
  }
}