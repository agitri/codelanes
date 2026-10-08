package com.readcodelikeahuman.canvas

import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.BuildResult

data class CanvasUpdate(val model: BlockModel?, val notice: String?)

/** Decides what the canvas shows after a rebuild: never drop good blocks because of a half-typed edit. */
object RebuildPolicy {
    const val SYNTAX_NOTICE = "Fix the syntax errors to update the blocks"

    fun next(previous: BlockModel?, result: BuildResult, hasSyntaxErrors: Boolean): CanvasUpdate = when {
        hasSyntaxErrors && previous != null -> CanvasUpdate(previous, SYNTAX_NOTICE)
        result is BuildResult.Supported -> CanvasUpdate(result.model, null)
        previous != null -> CanvasUpdate(previous, "Blocks paused: ${(result as BuildResult.Unsupported).reason}")
        else -> CanvasUpdate(null, (result as BuildResult.Unsupported).reason)
    }
}
