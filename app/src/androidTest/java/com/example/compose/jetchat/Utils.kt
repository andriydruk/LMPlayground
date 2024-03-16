package com.druk.lmplayground

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog

/**
 * Used to debug the semantic tree.
 */
fun ComposeTestRule.dumpSemanticNodes() {
    this.onRoot().printToLog(tag = "LMPlayground")
}
