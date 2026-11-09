package com.shihab.diplay.orchestration

/** A control-loop terminal is actionable only while this controller still owns the wired phase. */
internal fun shouldIgnoreWiredControlTerminal(
    closed: Boolean,
    aborted: Boolean,
    phaseIsWired: Boolean,
): Boolean = closed || aborted || !phaseIsWired
