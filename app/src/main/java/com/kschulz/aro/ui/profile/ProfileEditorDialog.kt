package com.kschulz.aro.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kschulz.aro.data.model.Profile

/**
 * Full user profile setup and editor dialog. Configures physical attributes (sex, birth year,
 * height, weight), blood pressure calibration baseline (SBP/DBP), and daily activity/sleep
 * goals that are synced to the ring via opcodes 0x0A and 0x21.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorDialog(
    initial: Profile?,
    isOnboarding: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
) {
    val base = initial ?: Profile(
        id = 0,
        name = "Me",
        createdAtEpochMs = System.currentTimeMillis(),
    )

    var name by remember(base) { mutableStateOf(base.name) }
    var gender by remember(base) { mutableIntStateOf(base.gender) }
    var birthYearText by remember(base) { mutableStateOf(base.birthYear.toString()) }
    var heightText by remember(base) { mutableStateOf(base.heightCm.toString()) }
    var weightText by remember(base) { mutableStateOf(base.weightKg.toString()) }
    var sbpText by remember(base) { mutableStateOf(base.sbpBaseline.toString()) }
    var dbpText by remember(base) { mutableStateOf(base.dbpBaseline.toString()) }
    var goalStepsText by remember(base) { mutableStateOf(base.goalSteps.toString()) }
    var goalCaloriesText by remember(base) { mutableStateOf(base.goalCalories.toString()) }
    var goalDistanceText by remember(base) { mutableStateOf(base.goalDistanceMeters.toString()) }
    var goalSleepHoursText by remember(base) {
        mutableStateOf(String.format(java.util.Locale.US, "%.1f", base.goalSleepMinutes / 60f))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    isOnboarding -> "Set up your profile"
                    base.id == 0L -> "New profile"
                    else -> "Edit profile"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Stored locally on this device and synced to your ring for accurate calories, distance, HR zones, and blood pressure calibration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Biological sex", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val options = listOf(Profile.GENDER_MALE to "Male", Profile.GENDER_FEMALE to "Female")
                    options.forEachIndexed { idx, (code, label) ->
                        SegmentedButton(
                            selected = gender == code,
                            onClick = { gender = code },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size),
                        ) {
                            Text(label)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = birthYearText,
                        onValueChange = { birthYearText = it.filter(Char::isDigit).take(4) },
                        label = { Text("Birth year") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Height (cm)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { weightText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Weight (kg)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Blood pressure calibration baseline", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "Reference cuff reading (mmHg) sent to the ring to calibrate PPG blood pressure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = sbpText,
                        onValueChange = { sbpText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Systolic (SBP)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = dbpText,
                        onValueChange = { dbpText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Diastolic (DBP)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Daily activity & sleep goals", style = MaterialTheme.typography.labelLarge)

                OutlinedTextField(
                    value = goalStepsText,
                    onValueChange = { goalStepsText = it.filter(Char::isDigit).take(6) },
                    label = { Text("Daily steps goal") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6000, 8000, 10000, 12000).forEach { preset ->
                        FilterChip(
                            selected = goalStepsText == preset.toString(),
                            onClick = { goalStepsText = preset.toString() },
                            label = { Text("${preset / 1000}k") },
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = goalCaloriesText,
                        onValueChange = { goalCaloriesText = it.filter(Char::isDigit).take(5) },
                        label = { Text("Calories (kcal)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = goalDistanceText,
                        onValueChange = { goalDistanceText = it.filter(Char::isDigit).take(6) },
                        label = { Text("Distance (m)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = goalSleepHoursText,
                    onValueChange = { goalSleepHoursText = it.filter { c -> c.isDigit() || c == '.' }.take(4) },
                    label = { Text("Sleep goal (hours)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    val birthYear = (birthYearText.toIntOrNull() ?: base.birthYear).coerceIn(1915, 2020)
                    val heightCm = (heightText.toIntOrNull() ?: base.heightCm).coerceIn(80, 240)
                    val weightKg = (weightText.toIntOrNull() ?: base.weightKg).coerceIn(25, 240)
                    val sbp = (sbpText.toIntOrNull() ?: base.sbpBaseline).coerceIn(80, 200)
                    val dbp = (dbpText.toIntOrNull() ?: base.dbpBaseline).coerceIn(40, 140)
                    val goalSteps = (goalStepsText.toIntOrNull() ?: base.goalSteps).coerceIn(1000, 100000)
                    val goalCalories = (goalCaloriesText.toIntOrNull() ?: base.goalCalories).coerceIn(50, 10000)
                    val goalDist = (goalDistanceText.toIntOrNull() ?: base.goalDistanceMeters).coerceIn(500, 100000)
                    val sleepHours = (goalSleepHoursText.toFloatOrNull() ?: (base.goalSleepMinutes / 60f)).coerceIn(3f, 14f)
                    val goalSleepMin = (sleepHours * 60f).toInt()

                    onSave(
                        base.copy(
                            name = name.trim(),
                            gender = gender,
                            birthYear = birthYear,
                            heightCm = heightCm,
                            weightKg = weightKg,
                            sbpBaseline = sbp,
                            dbpBaseline = dbp,
                            goalSteps = goalSteps,
                            goalCalories = goalCalories,
                            goalDistanceMeters = goalDist,
                            goalSleepMinutes = goalSleepMin,
                            isConfigured = true,
                        ),
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isOnboarding) "Later" else "Cancel")
            }
        },
    )
}

/**
 * Quick profile switcher & management dialog accessible from the Home header pill.
 */
@Composable
fun ProfileSwitcherDialog(
    profiles: List<Profile>,
    activeProfileId: Long,
    onSwitch: (Long) -> Unit,
    onEdit: (Profile) -> Unit,
    onAddNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch or edit profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "All health records are scoped to the selected profile. Switching is instant.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                profiles.forEach { profile ->
                    val isActive = profile.id == activeProfileId
                    Card(
                        onClick = {
                            onSwitch(profile.id)
                            onDismiss()
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (isActive) Icons.Filled.Check else Icons.Filled.Person,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 10.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = profileSummaryLine(profile),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = { onEdit(profile) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit ${profile.name}")
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = onAddNew,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("New profile")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

fun profileSummaryLine(profile: Profile): String {
    val sexLabel = if (profile.gender == Profile.GENDER_FEMALE) "F" else "M"
    return "$sexLabel • ${profile.heightCm} cm • ${profile.weightKg} kg • ${profile.goalSteps} steps • BP ${profile.sbpBaseline}/${profile.dbpBaseline}"
}
