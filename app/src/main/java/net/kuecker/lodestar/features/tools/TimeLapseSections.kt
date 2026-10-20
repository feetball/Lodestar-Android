package net.kuecker.lodestar.features.tools

import androidx.compose.runtime.Composable
import net.kuecker.lodestar.kit.phototools.TimeLapsePlanner
import net.kuecker.lodestar.kit.phototools.TimeLapseTarget
import net.kuecker.lodestar.shared.SettingsNumberField
import net.kuecker.lodestar.shared.SettingsOptionalNumberField

/** The time-lapse half of the planner: timing, playback, camera and storage, and the plan. */
@Composable
internal fun TimeLapseForm(inputs: TrailsInputs) {
    TimingSection(inputs)
    PlaybackSection(inputs)
    StorageSection(inputs)
    PlanSection(inputs)
}

@Composable
private fun TimingSection(inputs: TrailsInputs) {
    FormSection(
        title = "Timing",
        footer = "The interval is the time from the start of one picture to the start of the next. What is left " +
            "after the exposure is the time the camera has to save the picture.",
    ) {
        control {
            SettingsNumberField(
                title = "Interval",
                value = inputs.interval,
                onValueChange = { inputs.interval = it },
                range = 0.1..TimeLapsePlanner.maximumIntervalSeconds,
                unit = "s",
                fractionDigits = 1,
            )
        }
        control {
            SettingsNumberField(
                title = "Exposure",
                value = inputs.exposure,
                onValueChange = { inputs.exposure = it },
                range = 0.1..TimeLapsePlanner.maximumIntervalSeconds,
                unit = "s",
                fractionDigits = 1,
            )
        }
        info("Gap between pictures", TrailsText.seconds(inputs.interval - inputs.exposure, 1))
    }
}

@Composable
private fun PlaybackSection(inputs: TrailsInputs) {
    FormSection(
        title = "Playback",
        footer = "Give the length of the finished clip, the time you will shoot, or the number of pictures; the " +
            "other two follow.",
    ) {
        control {
            SettingsNumberField(
                title = "Frame rate",
                value = inputs.frameRate,
                onValueChange = { inputs.frameRate = it },
                range = 1.0..TimeLapsePlanner.maximumFrameRate,
                unit = "fps",
                fractionDigits = 0,
            )
        }
        row {
            ToolsSegmentedRow(TimeLapseBasis.entries, inputs.basis, { it.title }, "What the length is given by") {
                inputs.basis = it
            }
        }
        control {
            when (inputs.basis) {
                TimeLapseBasis.CLIP_LENGTH -> SettingsNumberField(
                    title = "Clip length",
                    value = inputs.clipSeconds,
                    onValueChange = { inputs.clipSeconds = it },
                    range = 0.1..86_400.0,
                    unit = "s",
                    fractionDigits = 1,
                )
                TimeLapseBasis.SHOOTING_TIME -> SettingsNumberField(
                    title = "Shooting time",
                    value = inputs.shootingMinutes,
                    onValueChange = { inputs.shootingMinutes = it },
                    range = 0.1..14_400.0,
                    unit = "min",
                    fractionDigits = 1,
                )
                TimeLapseBasis.FRAMES -> SettingsNumberField(
                    title = "Pictures",
                    value = inputs.frameCount,
                    onValueChange = { inputs.frameCount = it },
                    range = 1.0..TimeLapsePlanner.maximumFrameCount.toDouble(),
                    fractionDigits = 0,
                )
            }
        }
    }
}

@Composable
private fun StorageSection(inputs: TrailsInputs) {
    FormSection(
        title = "Camera & Storage",
        footer = "A raw picture from a 24\u00A0MP camera is about 25\u00A0MB, from a 45\u00A0MP camera about 50\u00A0MB. " +
            "Leave the size empty if you do not need the storage.",
    ) {
        control {
            SettingsOptionalNumberField(
                title = "Size of a picture",
                value = inputs.frameSizeMegabytes,
                onValueChange = { inputs.frameSizeMegabytes = it },
                range = 0.1..TimeLapsePlanner.maximumFrameSizeMegabytes,
                unit = "MB",
                fractionDigits = 1,
            )
        }
        control {
            SettingsNumberField(
                title = "Time to save a picture",
                value = inputs.writeSeconds,
                onValueChange = { inputs.writeSeconds = it },
                range = 0.0..TimeLapsePlanner.maximumMinimumGapSeconds,
                unit = "s",
                fractionDigits = 1,
            )
        }
    }
}

@Composable
private fun PlanSection(inputs: TrailsInputs) {
    val target = when (inputs.basis) {
        TimeLapseBasis.CLIP_LENGTH -> TimeLapseTarget.ClipLength(inputs.clipSeconds)
        TimeLapseBasis.SHOOTING_TIME -> TimeLapseTarget.ShootingTime(inputs.shootingMinutes * 60.0)
        TimeLapseBasis.FRAMES -> TimeLapseTarget.FrameCount(inputs.frameCount.toInt())
    }
    val plan = TrailsText.timeLapse {
        TimeLapsePlanner.plan(
            interval = inputs.interval,
            exposure = inputs.exposure,
            frameRate = inputs.frameRate,
            target = target,
            frameSizeMegabytes = inputs.frameSizeMegabytes,
            minimumGapSeconds = inputs.writeSeconds,
        )
    }
    FormSection(title = "Time-Lapse") {
        when (plan) {
            is Planned.Ok -> {
                val result = plan.value
                headline(
                    "${TrailsText.count(result.frameCount)} pictures",
                    "one every ${TrailsText.seconds(result.interval)}, " +
                        "each ${TrailsText.seconds(result.exposure)} long",
                )
                info("Shooting time", TrailsText.duration(result.shootingTime))
                val fps = decimal(result.frameRate, 0, 1)
                info("Clip length", "${TrailsText.duration(result.clipLength)} at $fps fps")
                info("Speed-up", "${decimal(result.speedUp, 0, 1)}×")
                info("Gap between pictures", TrailsText.seconds(result.gap, 2))
                val storage = result.storageMegabytes
                if (storage != null) {
                    info("Storage", TrailsText.storage(storage))
                } else {
                    caption("Enter the size of a picture to see how much storage the pictures need.")
                }
                info("Battery", TrailsText.battery(result.batteryDemand))
                caption(result.batteryNote)
            }
            is Planned.Failed -> {
                problem(plan.failure.message)
                plan.failure.suggestedInterval?.let { interval ->
                    action("Use an interval of ${TrailsText.seconds(interval, 2)}") {
                        inputs.interval = interval.coerceAtMost(TimeLapsePlanner.maximumIntervalSeconds)
                    }
                }
            }
        }
    }
}
