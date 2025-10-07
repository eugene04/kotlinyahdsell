package com.gari.yahdsell2.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.gari.yahdsell2.MainViewModel
import com.gari.yahdsell2.model.SavedSearch
import com.gari.yahdsell2.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedSearchesScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel()
) {
    val savedSearches by viewModel.savedSearches.collectAsState()
    val isLoading by viewModel.isLoadingSavedSearches.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.fetchSavedSearches()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Saved Searches") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (savedSearches.isEmpty()) {
                Text("You have no saved searches yet.", modifier = Modifier.align(Alignment.Center).padding(16.dp))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(savedSearches) { search ->
                        SavedSearchItem(
                            search = search,
                            onDelete = {
                                viewModel.deleteSavedSearch(search.id)
                                Toast.makeText(context, "Search deleted", Toast.LENGTH_SHORT).show()
                            },
                            onRun = {
                                // This part is complex as it requires passing filter data back.
                                // For now, we navigate home. A more advanced implementation
                                // would use a shared ViewModel or navigation result API.
                                navController.navigate(Screen.Main.route) {
                                    popUpTo(navController.graph.startDestinationId) { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SavedSearchItem(
    search: SavedSearch,
    onDelete: () -> Unit,
    onRun: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = search.query ?: "All ${search.category ?: "Items"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildFilterString(search),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onRun) {
                Icon(Icons.Default.Search, contentDescription = "Run Search")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Search", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun buildFilterString(search: SavedSearch): String {
    val parts = mutableListOf<String>()
    if (search.category != null && search.category != "All") parts.add(search.category)
    if (search.minPrice != null || search.maxPrice != null) {
        val min = search.minPrice?.toInt()?.toString() ?: ""
        val max = search.maxPrice?.toInt()?.toString() ?: ""
        parts.add("$$min - $$max")
    }
    if (search.condition != null && search.condition != "Any Condition") parts.add(search.condition)
    return if (parts.isEmpty()) "No additional filters" else parts.joinToString(" • ")
}

