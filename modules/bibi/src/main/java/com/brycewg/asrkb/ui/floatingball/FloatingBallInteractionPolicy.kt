// Defines mode-dependent floating-ball recording and movement decisions.
package com.brycewg.asrkb.ui.floatingball

internal enum class FloatingBallRecordingTapAction {
    StartRecording,
    StopRecording,
    None
}

internal enum class FloatingBallHoldPressAction {
    StartRecording,
    StopRecording,
    CancelProcessing,
    RevealEdge,
    None
}

internal enum class FloatingBallHoldMoveAction {
    None,
    OpenMenu,
    MoveBall
}

internal fun resolveFloatingBallRecordingTapAction(
    isRecording: Boolean,
    holdToRecordEnabled: Boolean
): FloatingBallRecordingTapAction = when {
    isRecording -> FloatingBallRecordingTapAction.StopRecording
    holdToRecordEnabled -> FloatingBallRecordingTapAction.None
    else -> FloatingBallRecordingTapAction.StartRecording
}

internal fun shouldScheduleFloatingLongHoldMove(
    holdToRecordEnabled: Boolean,
    directMoveEnabled: Boolean
): Boolean = false

internal fun shouldStartFloatingHoldRecordingOnDown(
    holdToRecordEnabled: Boolean,
    isMoveMode: Boolean
): Boolean = false

internal fun resolveFloatingBallHoldPressAction(
    isRecording: Boolean,
    isProcessing: Boolean,
    isEdgeHandleVisible: Boolean
): FloatingBallHoldPressAction = when {
    isRecording -> FloatingBallHoldPressAction.StopRecording
    isProcessing -> FloatingBallHoldPressAction.CancelProcessing
    isEdgeHandleVisible -> FloatingBallHoldPressAction.RevealEdge
    else -> FloatingBallHoldPressAction.StartRecording
}

internal fun resolveFloatingBallHoldMoveAction(
    movementExceeded: Boolean,
    @Suppress("UNUSED_PARAMETER") menuThresholdExceeded: Boolean,
    @Suppress("UNUSED_PARAMETER") movingTowardScreenCenter: Boolean,
    @Suppress("UNUSED_PARAMETER") directMoveEnabled: Boolean
): FloatingBallHoldMoveAction = FloatingBallHoldMoveAction.None
