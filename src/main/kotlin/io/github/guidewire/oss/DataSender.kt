package io.github.guidewire.oss

import io.github.guidewire.oss.auth.OAuthClient
import io.github.guidewire.oss.auth.OAuthConfig
import io.github.guidewire.oss.models.TestRun
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@OptIn(ExperimentalSerializationApi::class)
fun sendTestRun(
  testRun: TestRun,
  fernUrl: String,
  verbose: Boolean,
  oauthConfig: OAuthConfig? = null,
  apiEndpointPath: String? = null
): Result<Unit> {
  return runCatching {
    var response: HttpResponse<String>? = null

    // Create OAuth client if config is provided
    val oauthClient = if (oauthConfig != null) {
      if (verbose) {
        println("OAuth authentication is enabled, initializing OAuth client...")
      }
      OAuthClient.fromConfig(oauthConfig)
    } else {
      // Also check environment variables for OAuth configuration
      try {
        val envClient = OAuthClient.fromEnvironment()
        if (envClient != null && verbose) {
          println("OAuth authentication enabled from environment variables")
        }
        envClient
      } catch (e: Exception) {
        // OAuth configuration error from environment
        throw RuntimeException("OAuth configuration error: ${e.message}", e)
      }
    }

    for (i in 0..2) {
      val json = Json {
        prettyPrint = true
        encodeDefaults = true
        namingStrategy = JsonNamingStrategy.SnakeCase
      }

      val payload = json.encodeToString(testRun)

      // Use the provided API endpoint path, or check environment variable, or use default
      val effectiveApiPath = apiEndpointPath
        ?: System.getenv("FERN_API_ENDPOINT_PATH")
        ?: "api/v1/test-runs"

      val endpoint = "${fernUrl.removeSuffix("/")}/$effectiveApiPath"

      if (verbose) {
        println("Sending POST request to $endpoint...")
        if (oauthClient != null) {
          println("Using OAuth authentication")
        } else {
          println("OAuth authentication is not configured, proceeding without authentication")
        }
      }

      response = postTestRun(endpoint, fernUrl, payload, oauthClient, verbose)
      if (response.statusCode() < 300) {
        break
      } else {
        println("Failed to send POST request...")
        if (verbose) {
          println("Status Code: ${response.statusCode()} with body: ${response.body()}")
        }
      }
    }
    if (response?.statusCode()!! >= 300) {
      throw RuntimeException("Unexpected response code: ${response.statusCode()}")
    }
  }
}

private fun postTestRun(
  endpoint: String,
  fernUrl: String,
  payload: String,
  oauthClient: OAuthClient?,
  verbose: Boolean
): HttpResponse<String> {
  val client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(30))
    .build()

  val requestBuilder = HttpRequest.newBuilder()
    .uri(URI(endpoint).normalize())
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(payload))
    .timeout(Duration.ofSeconds(30))

  // Add OAuth authentication if enabled
  if (oauthClient != null) {
    try {
      oauthClient.addAuthHeader(requestBuilder)
      if (verbose) {
        println("Added OAuth bearer token to request")
      }
    } catch (e: Exception) {
      throw RuntimeException("Failed to add OAuth authentication: ${e.message}", e)
    }
  }

  val request = requestBuilder.build()

  var response = client.send(request, HttpResponse.BodyHandlers.ofString())
  if (response.statusCode() == 307) {
    val locationHeader = response.headers().firstValue("location").orElseThrow {
      RuntimeException("Location header not found in 307 response")
    }
    response = postTestRun(fernUrl + locationHeader, fernUrl, payload, oauthClient, verbose)
  }
  return response
}