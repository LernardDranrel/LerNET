package app.lernet.ui.controls

/** Position is zero at the lower stop and one at the upper stop. */
internal object NetworkLeverTravel {
    fun move(position: Float, deltaY: Float, travelPx: Float): Float {
        if (!deltaY.isFinite() || !travelPx.isFinite() || travelPx <= 0f) return position
        return (position - deltaY / travelPx).coerceIn(0f, 1f)
    }

    /** A small dead band prevents accidental changes when releasing near the centre. */
    fun target(position: Float, checked: Boolean): Boolean = when {
        position >= .6f -> true
        position <= .4f -> false
        else -> checked
    }
}
