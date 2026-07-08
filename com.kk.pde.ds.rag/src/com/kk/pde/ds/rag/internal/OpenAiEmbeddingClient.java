package com.kk.pde.ds.rag.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kk.pde.ds.rag.api.EmbeddingClient;

/**
 * {@link EmbeddingClient} for an OpenAI-compatible {@code /v1/embeddings} endpoint.
 *
 * <p>Mirrors the chat client ({@code OpenRouterAgent}) exactly for config and
 * transport: plain {@link HttpURLConnection}, {@code Authorization: Bearer},
 * and the same credential/base-URL resolution chain
 * ({@code -Dopenrouter.api.key} &rarr; {@code OPENROUTER_API_KEY};
 * {@code -Dopenrouter.base.url}, default OpenRouter). Only the path differs
 * ({@code /embeddings} instead of {@code /chat/completions}).</p>
 *
 * <p>The embedding model defaults to {@code intfloat/multilingual-e5-large} and
 * applies E5 role prefixes ("query:" / "passage:"), both overridable. For a model
 * that does not use prefixes (e.g. qwen3-embedding), blank the prefixes via
 * {@code -Drag.embedding.query.prefix=} / {@code -Drag.embedding.passage.prefix=}.</p>
 */
@Component(service = EmbeddingClient.class)
public class OpenAiEmbeddingClient implements EmbeddingClient {

	private static final Logger LOG = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);

	private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
	private static final String DEFAULT_MODEL = "intfloat/multilingual-e5-large";
	private static final String DEFAULT_QUERY_PREFIX = "query: ";
	private static final String DEFAULT_PASSAGE_PREFIX = "passage: ";

	/** Keep request bodies modest; embed passages in batches of this size. */
	private static final int BATCH_SIZE = 16;

	private String model;
	private String queryPrefix;
	private String passagePrefix;

	@Activate
	public void activate() {
		this.model = resolve("openrouter.embeddings.model", "OPENROUTER_EMBEDDINGS_MODEL", DEFAULT_MODEL);
		this.queryPrefix = System.getProperty("rag.embedding.query.prefix", DEFAULT_QUERY_PREFIX);
		this.passagePrefix = System.getProperty("rag.embedding.passage.prefix", DEFAULT_PASSAGE_PREFIX);
		LOG.info("OpenAiEmbeddingClient activated (model={})", model);
	}

	@Override
	public float[] embedQuery(String query) {
		if (query == null) {
			return null;
		}
		List<float[]> result = embed(Collections.singletonList(queryPrefix + query));
		return result.isEmpty() ? null : result.get(0);
	}

	@Override
	public List<float[]> embedPassages(List<String> passages) {
		List<float[]> out = new ArrayList<float[]>();
		if (passages == null || passages.isEmpty()) {
			return out;
		}
		for (int start = 0; start < passages.size(); start += BATCH_SIZE) {
			int end = Math.min(start + BATCH_SIZE, passages.size());
			List<String> batch = new ArrayList<String>(end - start);
			for (int i = start; i < end; i++) {
				batch.add(passagePrefix + passages.get(i));
			}
			List<float[]> embedded = embed(batch);
			// On a failed batch, embedded is empty: pad with nulls to keep alignment.
			for (int i = 0; i < batch.size(); i++) {
				out.add(i < embedded.size() ? embedded.get(i) : null);
			}
		}
		return out;
	}

	// ---- core --------------------------------------------------------------

	private List<float[]> embed(List<String> inputs) {
		String apiKey = resolveApiKey();
		if (apiKey.isEmpty()) {
			LOG.error("No API key found. Set OPENROUTER_API_KEY or -Dopenrouter.api.key");
			return Collections.emptyList();
		}

		String body = buildRequest(inputs);
		String response = post(apiKey, body);
		if (response == null) {
			return Collections.emptyList();
		}
		try {
			return parseEmbeddings(response, inputs.size());
		} catch (RuntimeException e) {
			LOG.error("Failed to parse embeddings response: {}", e.getMessage());
			return Collections.emptyList();
		}
	}

	private String buildRequest(List<String> inputs) {
		StringBuilder sb = new StringBuilder("{");
		sb.append("\"model\":\"").append(Json.escape(model)).append("\",");
		sb.append("\"input\":[");
		for (int i = 0; i < inputs.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('"').append(Json.escape(inputs.get(i))).append('"');
		}
		sb.append("]}");
		return sb.toString();
	}

	private List<float[]> parseEmbeddings(String response, int expected) {
		Object root = Json.parse(response);
		Map<String, Object> obj = Json.asObject(root);
		if (obj == null) {
			throw new IllegalStateException("response is not a JSON object");
		}
		List<Object> data = Json.asArray(obj.get("data"));
		if (data == null) {
			Map<String, Object> error = Json.asObject(obj.get("error"));
			String msg = error != null ? String.valueOf(error.get("message")) : "no 'data' field";
			throw new IllegalStateException("embeddings error: " + msg);
		}
		// Place each vector at its reported "index" rather than trusting array order.
		// The OpenAI embeddings contract guarantees an index per entry; honoring it
		// keeps chunks aligned with their vectors even if the server reorders or drops
		// entries. Any gap or out-of-range index fails the whole batch (returned as
		// empty upstream, which pads with nulls) rather than silently mis-storing.
		float[][] slots = new float[expected][];
		for (Object item : data) {
			Map<String, Object> entry = Json.asObject(item);
			if (entry == null) {
				throw new IllegalStateException("data entry is not an object");
			}
			Object idxVal = entry.get("index");
			if (!(idxVal instanceof Number)) {
				throw new IllegalStateException("data entry missing numeric 'index'");
			}
			int index = ((Number) idxVal).intValue();
			if (index < 0 || index >= expected) {
				throw new IllegalStateException("embedding index " + index + " out of range [0," + expected + ")");
			}
			if (slots[index] != null) {
				throw new IllegalStateException("duplicate embedding index " + index);
			}
			List<Object> vec = Json.asArray(entry.get("embedding"));
			if (vec == null) {
				throw new IllegalStateException("missing 'embedding' in data entry");
			}
			float[] arr = new float[vec.size()];
			for (int k = 0; k < vec.size(); k++) {
				arr[k] = ((Number) vec.get(k)).floatValue();
			}
			slots[index] = arr;
		}
		List<float[]> result = new ArrayList<float[]>(expected);
		for (int i = 0; i < expected; i++) {
			if (slots[i] == null) {
				throw new IllegalStateException("missing embedding for index " + i
					+ " (got " + data.size() + " of " + expected + ")");
			}
			result.add(slots[i]);
		}
		return result;
	}

	private String post(String apiKey, String jsonBody) {
		HttpURLConnection conn = null;
		try {
			URL url = new URL(resolveBaseUrl() + "/embeddings");
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/json");
			conn.setRequestProperty("Authorization", "Bearer " + apiKey);
			conn.setRequestProperty("HTTP-Referer", "http://localhost:8080");
			conn.setRequestProperty("X-Title", "OSGi RAG");
			conn.setDoOutput(true);
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(60000);

			OutputStream os = conn.getOutputStream();
			os.write(jsonBody.getBytes("UTF-8"));
			os.flush();
			os.close();

			int status = conn.getResponseCode();
			java.io.InputStream stream = (status >= 200 && status < 300)
				? conn.getInputStream() : conn.getErrorStream();
			if (stream == null) {
				// Some failure modes (e.g. certain 4xx, proxy errors) carry no body;
				// getErrorStream() then returns null. Guard against the NPE.
				LOG.error("Embeddings endpoint returned HTTP {} with no body", status);
				return null;
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
			reader.close();
			if (status < 200 || status >= 300) {
				LOG.error("Embeddings endpoint returned HTTP {}: {}", status, sb);
				return null;
			}
			return sb.toString();
		} catch (IOException e) {
			LOG.error("HTTP POST to embeddings endpoint failed: {}", e.getMessage());
			return null;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	// ---- config resolution (mirrors OpenRouterAgent) -----------------------

	private String resolveApiKey() {
		String key = System.getProperty("openrouter.api.key", "");
		if (key.isEmpty()) {
			String env = System.getenv("OPENROUTER_API_KEY");
			if (env != null && !env.isEmpty()) {
				key = env;
			}
		}
		return key;
	}

	private String resolveBaseUrl() {
		String base = System.getProperty("openrouter.base.url", DEFAULT_BASE_URL);
		// Tolerate a trailing slash in the configured base URL.
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	private static String resolve(String sysProp, String envVar, String def) {
		String v = System.getProperty(sysProp, "");
		if (!v.isEmpty()) {
			return v;
		}
		String env = System.getenv(envVar);
		return (env != null && !env.isEmpty()) ? env : def;
	}
}
