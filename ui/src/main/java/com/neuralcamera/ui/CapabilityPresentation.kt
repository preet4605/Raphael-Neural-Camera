package com.neuralcamera.ui

/**
 * What the camera screen may show, derived only from what the device or pipeline reported. Controls for a capability
 * nobody has reported are hidden, not shown as available.
 */
object CapabilityPresentation {
    /** Zoom ratios the screen can offer as shortcuts. They are zoom ratios, not claims about physical lenses. */
    val CANDIDATE_ZOOMS = listOf(0.6f, 1.0f, 2.0f, 3.0f, 6.0f)

    /**
     * Shortcuts inside the zoom range the active camera reported (CONTROL_ZOOM_RATIO_RANGE or the crop-zoom maximum).
     * Until a range is known only 1x is offered, because 1x is the one ratio every camera accepts.
     */
    fun zoomButtons(supported: ClosedFloatingPointRange<Float>?): List<Float> {
        if (supported == null) return listOf(1.0f)
        return CANDIDATE_ZOOMS.filter { it >= supported.start - 1e-3f && it <= supported.endInclusive + 1e-3f }
    }

    fun zoomLabel(zoom: Float): String = when {
        zoom < 1.0f -> ".${Math.round(zoom * 10)}"
        zoom == zoom.toInt().toFloat() -> "${zoom.toInt()}x"
        else -> "${zoom}x"
    }

    /** Spoken description for a zoom shortcut (TalkBack). */
    fun zoomDescription(zoom: Float, selected: Boolean): String =
        "Zoom ${if (zoom == zoom.toInt().toFloat()) zoom.toInt().toString() else zoom.toString()} times" + if (selected) ", selected" else ""

    /** Top-bar badge: names a neural backend only when a neural stage actually ran; otherwise the pipeline is classical. */
    fun processingBadge(isNeuralActive: Boolean, backendName: String): String =
        if (isNeuralActive) "NEURAL · $backendName" else "CLASSICAL"
}
