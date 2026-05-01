package com.fitform.app.ui.benchmark

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitform.app.pose.BackendResult
import com.fitform.app.pose.PerformanceRepository
import com.fitform.app.pose.PowerTier
import com.fitform.app.ui.components.GhostButton
import com.fitform.app.ui.components.PrimaryButton
import com.fitform.app.ui.components.SectionEyebrow
import com.fitform.app.ui.components.TickerRule
import com.fitform.app.ui.theme.FitFormColors
import com.fitform.app.ui.theme.FitFormType
import com.fitform.app.util.DeviceInfo
import kotlinx.coroutines.launch

@Composable
fun BenchmarkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by PerformanceRepository.state.collectAsState()
    val isEmulator = state.isEmulator
    val results = state.results

    LaunchedEffect(Unit) {
        DeviceInfo.logDeviceInfo()
        if (state.results == null && !state.isRunning) {
            PerformanceRepository.runBenchmark(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FitFormColors.Ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 56.dp, bottom = 32.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("NPU BENCHMARK", style = FitFormType.Eyebrow, color = FitFormColors.Acid)
            Text("LITERT 1.0.1", style = FitFormType.Stat, color = FitFormColors.Faint)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "LiteHRNet | 256x192 | ${if (results == null) "running..." else "50 inferences"}",
            style = FitFormType.Caption,
            color = FitFormColors.Mute,
        )

        Spacer(Modifier.height(12.dp))
        // Device chip
        val chipColor = if (isEmulator) FitFormColors.StatusAmber else FitFormColors.Acid
        Row(
            modifier = Modifier
                .background(FitFormColors.SurfaceHigh)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(4.dp).background(chipColor))
            Text(
                DeviceInfo.deviceChipLabel(),
                style = FitFormType.Caption,
                color = chipColor,
            )
        }

        if (isEmulator) {
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FitFormColors.Surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "EMULATOR · UI TEST MODE",
                    style = FitFormType.Eyebrow,
                    color = FitFormColors.StatusAmber,
                )
                Text(
                    "Emulator results are for UI/build testing only and do not " +
                        "represent Snapdragon Hexagon NPU performance.",
                    style = FitFormType.Caption,
                    color = FitFormColors.Mute,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        TickerRule()
        Spacer(Modifier.height(28.dp))

        if (results == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = FitFormColors.Acid,
                    strokeWidth = 2.dp,
                )
                Text(
                    state.statusText ?: "Warming up NPU execution plan…",
                    style = FitFormType.Body,
                    color = FitFormColors.Mute,
                )
            }
        } else {
            val list = results!!
            val maxMs = list.filter { it.available }.maxOfOrNull { it.avgMs }?.toFloat() ?: 1f

            SectionEyebrow("BACKEND COMPARISON")
            Spacer(Modifier.height(16.dp))

            list.forEach { result ->
                BackendBar(result = result, maxMs = maxMs)
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                label = if (state.isRunning) "Running Benchmark…" else "Run Benchmark Again",
                eyebrow = "PERFORMANCE LAB",
                enabled = !state.isRunning,
                onClick = {
                    scope.launch { PerformanceRepository.runBenchmark(context) }
                },
            )
            val statusText = state.statusText
            if (statusText != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = FitFormColors.Acid,
                            strokeWidth = 1.5.dp,
                        )
                    }
                    Text(
                        statusText,
                        style = FitFormType.Caption,
                        color = FitFormColors.Mute,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            // Latency explanation
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FitFormColors.Surface)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Live camera target: 30 FPS",
                    style = FitFormType.LabelLg,
                    color = FitFormColors.Acid,
                )
                Text(
                    "For this small INT8 pose model, CPU and NPU latency can look similar in a short benchmark. The advantage of the Snapdragon Hexagon NPU is sustained low-power inference during continuous camera use.",
                    style = FitFormType.Body,
                    color = FitFormColors.Mute,
                )
            }

            Spacer(Modifier.height(24.dp))
            TickerRule()
            Spacer(Modifier.height(24.dp))

            // Why NPU Wins card
            WhyNpuWinsCard()

            Spacer(Modifier.height(32.dp))
            TickerRule()
            Spacer(Modifier.height(24.dp))

            SectionEyebrow("HOW IT WORKS")
            Spacer(Modifier.height(16.dp))
            CodeCard()

            Spacer(Modifier.height(12.dp))
            Text(
                "One call to addDelegate(NnApiDelegate()) routes all supported ops " +
                    "to the Snapdragon Hexagon DSP. No driver changes, no model recompilation - " +
                    "LiteRT handles dispatch automatically.",
                style = FitFormType.Body,
                color = FitFormColors.Mute,
            )

            Spacer(Modifier.height(28.dp))
            TickerRule()
            Spacer(Modifier.height(24.dp))

            SectionEyebrow("ACTIVE IN THIS APP")
            Spacer(Modifier.height(12.dp))
            PipelineCard()
        }

        Spacer(Modifier.height(36.dp))
        GhostButton(label = "Back to Home", onClick = onBack)
    }
}

@Composable
private fun BackendBar(result: BackendResult, maxMs: Float) {
    val tierColor = when (result.powerTier) {
        PowerTier.LOW -> FitFormColors.Acid
        PowerTier.MEDIUM -> FitFormColors.StatusAmber
        PowerTier.HIGH -> FitFormColors.StatusRed
    }
    val fraction = if (result.available && maxMs > 0) result.avgMs / maxMs else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 800),
        label = "bar_${result.name}",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FitFormColors.Surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(if (result.available) tierColor else FitFormColors.Faint),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(result.name, style = FitFormType.LabelLg, color = FitFormColors.Bone)
                    if (result.subtitle != null) {
                        Text(
                            result.subtitle,
                            style = FitFormType.Caption,
                            color = FitFormColors.Faint,
                        )
                    }
                }
            }
            if (result.available) {
                Text(
                    "${result.avgMs}ms avg | ${result.minMs}ms min",
                    style = FitFormType.Eyebrow,
                    color = tierColor,
                )
            } else {
                Text("UNAVAILABLE", style = FitFormType.Eyebrow, color = FitFormColors.Faint)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(FitFormColors.SurfaceHigh),
        ) {
            val barColor = if (result.available) tierColor else FitFormColors.Faint
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .fillMaxHeight()
                    .background(barColor),
            )
        }

        if (result.available) {
            val fps = if (result.avgMs > 0) (1000f / result.avgMs).toInt() else 0
            val powerLabel = when (result.powerTier) {
                PowerTier.LOW    -> "EST. LOW POWER"
                PowerTier.MEDIUM -> "EST. MED POWER"
                PowerTier.HIGH   -> "EST. HIGH POWER"
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "~$fps inferences/sec synthetic benchmark",
                    style = FitFormType.Caption,
                    color = FitFormColors.Faint,
                )
                Text(
                    "$powerLabel",
                    style = FitFormType.Caption,
                    color = FitFormColors.Faint,
                )
            }
        } else if (result.note != null) {
            Text(
                result.note,
                style = FitFormType.Caption,
                color = FitFormColors.Faint,
            )
        }
    }
}

@Composable
private fun CodeCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FitFormColors.Surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("CPU - default", style = FitFormType.Caption, color = FitFormColors.Faint)
        MonoBlock("""Interpreter.Options()""", highlight = false)
        Text("NPU - one line added", style = FitFormType.Caption, color = FitFormColors.Acid)
        MonoBlock(
            "Interpreter.Options().apply {\n    addDelegate(NnApiDelegate())\n}",
            highlight = true,
        )
    }
}

@Composable
private fun MonoBlock(code: String, highlight: Boolean) {
    val bg = if (highlight) FitFormColors.AcidGlow else FitFormColors.SurfaceHigh
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = code,
            style = FitFormType.Caption.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = if (highlight) FitFormColors.Acid else FitFormColors.Bone,
        )
    }
}

@Composable
private fun PipelineCard() {
    val models = listOf(
        Triple("01", "LiteHRNet", "17-keypoint pose estimation | Qualcomm export"),
        Triple("02", "FormClassifier", "Angle-to-score network | INT8 quant"),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FitFormColors.Surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(6.dp).background(FitFormColors.Acid))
            Text(
                "BOTH MODELS USE NNAPI DELEGATE -> HEXAGON DSP",
                style = FitFormType.Eyebrow,
                color = FitFormColors.Acid,
            )
        }
        models.forEach { (num, name, desc) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(num, style = FitFormType.Stat, color = FitFormColors.Acid)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = FitFormType.LabelLg, color = FitFormColors.Bone)
                    Text(desc, style = FitFormType.Caption, color = FitFormColors.Mute)
                }
            }
        }
    }
}

@Composable
private fun WhyNpuWinsCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FitFormColors.Surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(6.dp).background(FitFormColors.Acid))
            Text(
                "WHY NPU WINS",
                style = FitFormType.Eyebrow,
                color = FitFormColors.Acid,
            )
        }
        
        val items = listOf(
            Pair("CPU", "Fast fallback, higher power under sustained load"),
            Pair("GPU", "Unavailable on this build/device path"),
            Pair("NPU", "Optimized for efficient continuous AI inference"),
        )
        
        items.forEach { (label, desc) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = FitFormType.LabelLg, color = FitFormColors.Bone)
                Text(desc, style = FitFormType.Caption, color = FitFormColors.Mute)
            }
        }
    }
}
