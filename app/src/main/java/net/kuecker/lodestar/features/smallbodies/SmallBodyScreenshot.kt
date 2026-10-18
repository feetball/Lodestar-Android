package net.kuecker.lodestar.features.smallbodies

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.kit.catalog.CelestialObject

/** Debug screenshots only: the target of the screenshot (a comet, say), once the catalog has it. */
suspend fun awaitScreenshotTarget(app: AppModel): CelestialObject = snapshotFlow { app.target }.filterNotNull().first()
