package com.sitharaj.myapplication

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll

import myapplication.composeapp.generated.resources.Res
import myapplication.composeapp.generated.resources.compose_multiplatform

@Composable
@Preview
fun App() {
    MaterialTheme {
        var showContent by remember { mutableStateOf(false) }
        // keep a single ApiClient instance for the Compose session (fully-qualified)
        val apiClient = remember { com.sitharaj.myapplication.network.ApiClient() }
        var isLoading by remember { mutableStateOf(false) }
        var post by remember { mutableStateOf<com.sitharaj.myapplication.network.Post?>(null) }
        var error by remember { mutableStateOf<String?>(null) }

        // For duplicate-call test
        val scope = rememberCoroutineScope()
        var testRunning by remember { mutableStateOf(false) }
        // Use a list so we can display an arbitrary number of concurrent results
        var testResults by remember { mutableStateOf<List<String>>(emptyList()) }
        var dedupeLogs by remember { mutableStateOf<List<String>>(emptyList()) }

        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = { showContent = !showContent }) {
                Text("Click me!")
            }

            // New: test duplicate & concurrent calls button
            Button(onClick = {
                 // Launch multiple concurrent deduped requests and collect logs
                 scope.launch {
                     try {
                         testRunning = true
                         testResults = emptyList()
                         dedupeLogs = emptyList()

                         // reset logger for this run
                         DedupeLogger.clear()

                        // Build a list of URLs that includes duplicates for two endpoints
                        // and additional unique endpoints to exercise both dedupe and concurrency.
                        val url1 = "https://jsonplaceholder.typicode.com/posts/1"
                        val url2 = "https://jsonplaceholder.typicode.com/posts/2"
                        val url3 = "https://jsonplaceholder.typicode.com/posts/3"
                        val url4 = "https://jsonplaceholder.typicode.com/posts/4"
                        // Two duplicates for url1, two duplicates for url2, plus url3 and url4.
                        val urls = listOf(url1, url1, url1, url1, url2, url2, url3, url4)

                         // Create Deferreds (CompletableDeferred implements Deferred)
                         val deferred: List<kotlinx.coroutines.Deferred<com.sitharaj.myapplication.network.Post>> =
                             urls.map { u ->
                                 val d = CompletableDeferred<com.sitharaj.myapplication.network.Post>()
                                 scope.launch {
                                     try {
                                         val res = apiClient.fetchDedupe(u)
                                         d.complete(res)
                                     } catch (t: Throwable) {
                                         d.completeExceptionally(t)
                                     }
                                 }
                                 d
                             }

                         // Await all results concurrently
                         val results = deferred.awaitAll()

                        // Attach the originating URL to each result for clarity and store in the list
                        testResults = results.mapIndexed { idx, post ->
                            val src = urls.getOrNull(idx) ?: "(unknown)"
                            "${post.title} ($src)"
                        }
                     } catch (t: Throwable) {
                         error = t.message ?: "Unknown error"
                     } finally {
                         dedupeLogs = DedupeLogger.getLogs()
                         testRunning = false
                     }
                 }
            }) {
                Text("Test duplicate & concurrent calls")
            }

            AnimatedVisibility(showContent) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val greeting = remember { Greeting().greet() }
                    Image(painterResource(Res.drawable.compose_multiplatform), null)
                    Text("Compose: $greeting")

                    // Fetch the JSONPlaceholder post lazily when content becomes visible
                    LaunchedEffect(showContent) {
                        if (showContent && post == null && !isLoading) {
                            isLoading = true
                            error = null
                            try {
                                // JSONPlaceholder sample post using dedupe-enabled fetch (stubbed)
                                post = apiClient.fetchDedupe("https://jsonplaceholder.typicode.com/posts/1")
                            } catch (t: Throwable) {
                                error = t.message ?: "Unknown error"
                            } finally {
                                isLoading = false
                            }
                        }
                    }

                    when {
                        isLoading -> Text("Loading post...")
                        error != null -> Text("Error: $error")
                        post != null -> {
                            Text(text = "Title: ${post!!.title}")
                            Text(text = post!!.body)
                        }
                        else -> Text("No post loaded")
                    }

                    // Show duplicate-test results and logs
                    if (testRunning) {
                        Text("Running duplicate test...")
                    }
                    if (!testRunning && testResults.isNotEmpty()) {
                        Text("Test results:")
                        testResults.forEachIndexed { i, r ->
                            Text("Result ${i + 1}: ${r}")
                        }
                    }

                    if (dedupeLogs.isNotEmpty()) {
                        Text("Dedupe logs:")
                        dedupeLogs.forEach { l -> Text(l) }
                    }
                }
            }
        }
    }
}

// Platform-friendly fetchDedupe implementation that uses the existing ApiClient.fetch<T>
// and records simple start/done messages to DedupeLogger so the UI can display something.
// This uses the shared ApiClient and the DuplicateRequestGuard plugin installed on the client;
// two concurrent identical GETs to the same URL should be coalesced by the plugin.
suspend fun com.sitharaj.myapplication.network.ApiClient.fetchDedupe(url: String): com.sitharaj.myapplication.network.Post {
    DedupeLogger.log("fetchDedupe: start $url")
    try {
        return this.fetch<com.sitharaj.myapplication.network.Post>(url)
    } finally {
        DedupeLogger.log("fetchDedupe: done $url")
    }
}

// Simple in-memory logger for demo purposes.
object DedupeLogger {
    private val _logs = mutableListOf<String>()
    fun log(entry: String) {
        _logs.add(entry)
    }

    fun getLogs(): List<String> = _logs.toList()

    fun clear() { _logs.clear() }
}
