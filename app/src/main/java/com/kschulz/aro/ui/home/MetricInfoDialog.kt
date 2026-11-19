package com.kschulz.aro.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class MetricInfoTopic(
    val title: String,
    val summary: String,
    val howCalculated: String,
    val normalRanges: List<String>,
    val note: String? = null,
) {
    ACTIVITY(
        title = "Activity & Steps",
        summary = "Tracks your daily step count, estimated distance, and active calories burned.",
        howCalculated = "Counted continuously by the ring's 3-axis accelerometer. Distance and calories scale from your profile's height, weight, and gender.",
        normalRanges = listOf(
            "General wellness target: 7,000–10,000 steps/day",
            "Active lifestyle: 10,000+ steps/day",
            "WHO guidance: 150+ min/week of moderate activity",
        ),
    ),
    HEART_RATE(
        title = "Heart Rate (bpm)",
        summary = "Your heart beats per minute, measured optically via the ring's PPG sensor.",
        howCalculated = "Sampled automatically on your configured interval in Settings and on-demand when you tap Measure.",
        normalRanges = listOf(
            "Resting (18–39 yrs): 60–80 bpm (athletic: 45–59)",
            "Resting (40–59 yrs): 60–82 bpm",
            "Resting (60+ yrs): 60–85 bpm",
            "Estimated max HR: ~220 − your age",
        ),
        note = "Lower resting heart rate generally reflects stronger cardiovascular fitness.",
    ),
    BLOOD_PRESSURE(
        title = "Blood Pressure (mmHg)",
        summary = "Systolic (heart contracting) over Diastolic (heart resting) pressure.",
        howCalculated = "Estimated optically from the ring's PPG pulse wave and calibrated against the baseline BP in your profile.",
        normalRanges = listOf(
            "Normal: < 120 / < 80 mmHg",
            "Elevated: 120–129 / < 80 mmHg",
            "High (Stage 1): 130–139 / 80–89 mmHg",
            "High (Stage 2): ≥ 140 / ≥ 90 mmHg",
            "Typical age averages: 20–39y ~117/76 • 40–59y ~124/79 • 60+y ~133/76",
        ),
        note = "Optical ring BP is a wellness trend estimate, not a replacement for a medical cuff.",
    ),
    SPO2(
        title = "Blood Oxygen (SpO2 %)",
        summary = "Percentage of oxygen-carrying hemoglobin in your blood.",
        howCalculated = "Measured by shining red and infrared LEDs through your finger and comparing light absorption.",
        normalRanges = listOf(
            "Normal (healthy adults): 95–100%",
            "Slightly low (or high altitude): 90–94%",
            "Low: < 90% (re-check with a snug ring fit)",
        ),
        note = "Keep your hand still and warm during measurement for the most accurate reading.",
    ),
    SLEEP(
        title = "Sleep & Stages",
        summary = "Overnight sleep duration broken down into Deep, Light, REM, and Awake periods.",
        howCalculated = "Classified by the ring from movement and overnight heart-rate patterns, then synced to Aro.",
        normalRanges = listOf(
            "Adults (18–64 yrs): 7–9 hours/night",
            "Older adults (65+ yrs): 7–8 hours/night",
            "Typical healthy mix: 15–25% Deep • 20–25% REM • 50–60% Light",
        ),
        note = "Deep sleep drives physical recovery; REM supports memory and mood.",
    ),
    BODY_BATTERY(
        title = "Body Battery (5–100)",
        summary = "A derived wellness score estimating your current energy reserve throughout the day.",
        howCalculated = "Computed locally on your phone: recharges during sleep (faster with lower resting HR) and drains while awake based on heart-rate strain above 65 bpm and step activity.",
        normalRanges = listOf(
            "Morning after good sleep: 75–100",
            "Midday: 50–75",
            "Evening before bed: 20–45",
        ),
        note = "This is a derived wellness indicator, not a clinical vital.",
    ),
}

@Composable
fun MetricInfoDialog(
    topic: MetricInfoTopic,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(topic.title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(topic.summary, style = MaterialTheme.typography.bodyMedium)

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "How it's calculated",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(topic.howCalculated, style = MaterialTheme.typography.bodySmall)
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Reference ranges",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    topic.normalRanges.forEach { line ->
                        Text("• $line", style = MaterialTheme.typography.bodySmall)
                    }
                }

                topic.note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        },
    )
}
