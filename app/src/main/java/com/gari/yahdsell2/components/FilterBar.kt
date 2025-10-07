package com.gari.yahdsell2.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun FilterBar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    selectedCondition: String,
    onConditionChange: (String) -> Unit,
    selectedSort: String,
    onSortChange: (String) -> Unit,
    minPrice: String,
    maxPrice: String,
    onApplyPrice: () -> Unit,
    onClearPrice: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // 🔍 Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search products or sellers...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = MaterialTheme.shapes.large,
                singleLine = true
            )

            // 🧮 Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory != "All Categories",
                    onClick = { onCategoryChange("Select") },
                    label = { Text(selectedCategory) }
                )
                FilterChip(
                    selected = selectedCondition != "Any Condition",
                    onClick = { onConditionChange("Select") },
                    label = { Text(selectedCondition) }
                )
                FilterChip(
                    selected = selectedSort != "Recommended",
                    onClick = { onSortChange("Select") },
                    label = { Text(selectedSort) }
                )
            }

            // 💰 Price Inputs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = minPrice,
                    onValueChange = { onSearchChange(it) },
                    label = { Text("Min Price") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = maxPrice,
                    onValueChange = { onSearchChange(it) },
                    label = { Text("Max Price") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            // ✅ Apply & Clear Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onApplyPrice, modifier = Modifier.weight(1f)) {
                    Text("Apply")
                }
                OutlinedButton(onClick = onClearPrice, modifier = Modifier.weight(1f)) {
                    Text("Clear")
                }
            }
        }
    }
}