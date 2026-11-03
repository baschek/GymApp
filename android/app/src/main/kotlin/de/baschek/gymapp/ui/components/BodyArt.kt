package de.baschek.gymapp.ui.components

import de.baschek.gymapp.core.model.MuscleGroup

enum class BodySide { FRONT, BACK }

/** A muscle shape of the body drawing: the muscle group it belongs to and SVG path data (viewBox 220 x 380). */
data class BodyShape(val group: String, val d: String)

/**
 * TODO(artwork): these are the sketch shapes of the design prototype, not final artwork. Only the left half of
 * the body is described; it is mirrored around x = 110 for the right half. Replace this file with a properly
 * licensed illustration (see the candidates listed in the project notes) keeping the [BodyShape.group] mapping;
 * nothing else depends on the geometry.
 */
object BodyArt {
    const val WIDTH = 220f
    const val HEIGHT = 380f
    const val HEAD_CX = 110f
    const val HEAD_CY = 34f
    const val HEAD_R = 20f

    private const val DELT = "M80 68 C68 64 52 68 46 82 C43 92 47 100 55 102 C63 96 72 90 82 86 Z"
    private const val PEC = "M84 72 C96 70 106 74 108 80 L108 104 C98 110 86 106 80 98 C78 88 80 78 84 72 Z"
    private const val ARM = "M52 106 C58 100 66 98 70 102 C72 114 70 128 66 138 C60 142 52 140 48 134 C46 124 48 114 52 106 Z"
    private const val FORE = "M48 140 C54 144 62 144 66 140 C70 156 68 176 62 192 C56 194 50 192 46 188 C44 172 44 154 48 140 Z"
    private const val ABS1 = "M92 112 H108 V129 H92 Z"
    private const val ABS2 = "M92 133 H108 V150 H92 Z"
    private const val ABS3 = "M94 154 H108 V172 H94 Z"
    private const val OBL = "M80 108 C86 112 88 130 88 150 C88 160 86 168 82 172 C78 160 76 140 76 120 Z"
    private const val QUAD = "M82 198 C94 196 106 200 108 208 L106 270 C104 284 98 292 92 292 C84 288 80 262 78 238 C77 222 78 208 82 198 Z"
    private const val CALF = "M88 304 C96 302 102 306 102 314 C102 336 98 356 94 366 C90 368 86 364 85 356 C82 338 84 316 88 304 Z"
    private const val BACK = "M84 62 C96 64 106 66 108 70 L108 118 C100 120 90 116 82 106 C80 90 82 74 84 62 Z"
    private const val LOW = "M94 122 H108 V156 H94 Z"
    private const val GLUTE = "M84 160 C94 156 106 160 108 168 L108 196 C100 202 88 200 82 192 C78 180 80 168 84 160 Z"
    private const val HAM = "M82 204 C94 202 106 206 108 212 L106 274 C104 286 98 292 92 292 C84 288 80 264 78 240 C77 226 78 212 82 204 Z"

    val front: List<BodyShape> = listOf(
        BodyShape(MuscleGroup.SCHULTERN, DELT), BodyShape(MuscleGroup.BRUST, PEC), BodyShape(MuscleGroup.BIZEPS, ARM),
        BodyShape(MuscleGroup.UNTERARME, FORE), BodyShape(MuscleGroup.BAUCH, ABS1), BodyShape(MuscleGroup.BAUCH, ABS2),
        BodyShape(MuscleGroup.BAUCH, ABS3), BodyShape(MuscleGroup.BAUCH, OBL), BodyShape(MuscleGroup.QUADRIZEPS, QUAD),
        BodyShape(MuscleGroup.WADEN, CALF),
    )

    val back: List<BodyShape> = listOf(
        BodyShape(MuscleGroup.SCHULTERN, DELT), BodyShape(MuscleGroup.RUECKEN, BACK), BodyShape(MuscleGroup.UNTERER_RUECKEN, LOW),
        BodyShape(MuscleGroup.TRIZEPS, ARM), BodyShape(MuscleGroup.UNTERARME, FORE), BodyShape(MuscleGroup.GESAESS, GLUTE),
        BodyShape(MuscleGroup.BEINBEUGER, HAM), BodyShape(MuscleGroup.WADEN, CALF),
    )

    fun shapes(side: BodySide): List<BodyShape> = if (side == BodySide.FRONT) front else back
}
