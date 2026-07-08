package com.kk.pde.ds.chatbot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kk.pde.ds.mcp.api.Json;

/**
 * Queries OpenAI-compatible /v1/models endpoint to list available models.
 * Works with OpenRouter, LM Studio, and any OpenAI-compatible API.
 */
public final class ModelFetcher {

	private static final Logger LOG = LoggerFactory.getLogger(ModelFetcher.class);

	private ModelFetcher() {
	}

	/**
	 * Fetch tool-capable model IDs from the given base URL.
	 * Only returns models whose supported_parameters include "tools".
	 * If the API doesn't provide supported_parameters (e.g. LM Studio),
	 * all models are included.
	 *
	 * @param baseUrl  API base URL (e.g. "https://openrouter.ai/api/v1")
	 * @param apiKey   bearer token (may be empty for local APIs like LM Studio)
	 * @return sorted list of model ID strings, empty list on error
	 */
	public static List<String> fetchModels(String baseUrl, String apiKey) {
		String modelsUrl = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";

		HttpURLConnection conn = null;
		try {
			URL url = new URL(modelsUrl);
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("Accept", "application/json");
			if (apiKey != null && !apiKey.isEmpty()) {
				conn.setRequestProperty("Authorization", "Bearer " + apiKey);
			}
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(30000);

			int status = conn.getResponseCode();
			if (status < 200 || status >= 300) {
				LOG.warn("Models endpoint returned status {}", status);
				return Collections.emptyList();
			}

			BufferedReader reader = new BufferedReader(
				new InputStreamReader(conn.getInputStream(), "UTF-8"));
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) sb.append(line);
			reader.close();

			String json = sb.toString();
			List<Object> data = Json.asList(Json.get(Json.parseObject(json), "data"));
			if (data == null) {
				LOG.warn("No 'data' array in models response");
				return Collections.emptyList();
			}

			List<String> modelIds = new ArrayList<String>();
			for (Object element : data) {
				String id = Json.getString(element, "id");
				if (id == null || id.isEmpty()) {
					continue;
				}
				// Filter: only include models that support tool calls.
				// If supported_parameters is absent (e.g. LM Studio), include the model.
				List<Object> params = Json.asList(Json.get(element, "supported_parameters"));
				if (params != null && !params.contains("tools")) {
					continue;
				}
				modelIds.add(id);
			}

			Collections.sort(modelIds);
			LOG.info("Fetched {} tool-capable models from {} (total: {})",
				modelIds.size(), modelsUrl, data.size());
			return modelIds;

		} catch (IOException e) {
			LOG.error("Failed to fetch models from {}: {}", modelsUrl, e.getMessage());
			return Collections.emptyList();
		} finally {
			if (conn != null) conn.disconnect();
		}
	}
}
