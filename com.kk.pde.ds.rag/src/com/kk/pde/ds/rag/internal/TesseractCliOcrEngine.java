package com.kk.pde.ds.rag.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kk.pde.ds.rag.api.OcrEngine;

/**
 * {@link OcrEngine} backed by the Tesseract command-line binary, invoked as a
 * subprocess via {@link ProcessBuilder}.
 *
 * <p>The CLI is used deliberately instead of a JNI/JNA binding (Tess4J): it adds
 * <b>zero</b> Java/OSGi dependencies and carries no slf4j transitive — keeping the
 * RAG bundle free of the dependency entanglement that ruled out Apache Tika. The
 * cost is an external runtime requirement: the {@code tesseract} binary must be on
 * the host ({@code brew install tesseract} / {@code apt install tesseract-ocr}).</p>
 *
 * <p>Each call writes the image to a temp file and runs
 * {@code tesseract <file> stdout -l <lang>}, capturing recognized text from stdout.
 * Availability is probed once at activation via {@code tesseract --version}; if the
 * binary is missing or OCR is disabled, the engine no-ops (returns "") so the parser
 * falls back to text-only extraction.</p>
 *
 * <p>Configuration (system property, then env var, then default):</p>
 * <ul>
 *   <li>{@code rag.ocr.enabled} / {@code RAG_OCR_ENABLED} — master switch (default true)</li>
 *   <li>{@code rag.ocr.binary} — binary name or path (default {@code tesseract})</li>
 *   <li>{@code rag.ocr.lang} — tesseract {@code -l} value, e.g. {@code eng+tur} (default {@code eng})</li>
 *   <li>{@code rag.ocr.timeout.seconds} — per-image subprocess timeout (default 60)</li>
 * </ul>
 */
@Component(service = OcrEngine.class)
public class TesseractCliOcrEngine implements OcrEngine {

	private static final Logger LOG = LoggerFactory.getLogger(TesseractCliOcrEngine.class);

	private static final String DEFAULT_BINARY = "tesseract";
	private static final String DEFAULT_LANG = "eng";
	private static final int DEFAULT_TIMEOUT_SECONDS = 60;

	private boolean available;
	private String binary;
	private String lang;
	private int timeoutSeconds;

	@Activate
	public void activate() {
		this.binary = System.getProperty("rag.ocr.binary", DEFAULT_BINARY);
		this.lang = System.getProperty("rag.ocr.lang", DEFAULT_LANG);
		this.timeoutSeconds = intProp("rag.ocr.timeout.seconds", DEFAULT_TIMEOUT_SECONDS);

		boolean enabled = Boolean.parseBoolean(resolve("rag.ocr.enabled", "RAG_OCR_ENABLED", "true"));
		if (!enabled) {
			this.available = false;
			LOG.info("TesseractCliOcrEngine disabled via rag.ocr.enabled=false");
			return;
		}
		this.available = probe();
		if (available) {
			LOG.info("TesseractCliOcrEngine activated (binary={}, lang={})", binary, lang);
		} else {
			LOG.warn("TesseractCliOcrEngine: '{}' not runnable; OCR disabled (documents will be "
				+ "parsed text-only). Install Tesseract or set -Drag.ocr.binary to enable.", binary);
		}
	}

	@Override
	public boolean isAvailable() {
		return available;
	}

	@Override
	public String ocr(byte[] encodedImage) throws IOException {
		if (!available || encodedImage == null || encodedImage.length == 0) {
			return "";
		}
		Path tmp = Files.createTempFile("rag-ocr", ".img");
		try {
			Files.write(tmp, encodedImage);
			// tesseract <image> stdout -l <lang>  -> recognized text on stdout.
			List<String> cmd = new ArrayList<String>();
			cmd.add(binary);
			cmd.add(tmp.toString());
			cmd.add("stdout");
			cmd.add("-l");
			cmd.add(lang);
			return run(cmd);
		} catch (IOException e) {
			LOG.warn("OCR failed for an image ({}): {}", e.getClass().getSimpleName(), e.getMessage());
			return "";
		} finally {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignore) {
				// best-effort cleanup
			}
		}
	}

	/** Run {@code tesseract --version} to decide whether the binary is usable. */
	private boolean probe() {
		try {
			List<String> cmd = new ArrayList<String>();
			cmd.add(binary);
			cmd.add("--version");
			run(cmd);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Run a subprocess, returning its stdout as UTF-8 text. stderr is drained on a
	 * separate thread (tesseract writes progress/warnings there) so neither pipe's
	 * buffer can fill and deadlock the process.
	 */
	private String run(List<String> cmd) throws IOException {
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectErrorStream(false);
		final Process proc = pb.start();

		// Drain stderr concurrently to avoid a full-buffer deadlock.
		final Thread errDrain = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					drain(proc.getErrorStream());
				} catch (IOException ignore) {
					// stderr content is non-essential
				}
			}
		}, "rag-ocr-stderr");
		errDrain.setDaemon(true);
		errDrain.start();

		// Drain stdout on its OWN thread too, so the main thread can enforce the
		// timeout via waitFor() first. Reading stdout inline would block until the
		// pipe reaches EOF — a wedged tesseract that keeps stdout open would then
		// hang this thread forever and the timeout would never fire.
		final byte[][] stdoutHolder = new byte[1][];
		final IOException[] stdoutError = new IOException[1];
		final Thread outDrain = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					stdoutHolder[0] = drain(proc.getInputStream());
				} catch (IOException e) {
					stdoutError[0] = e;
				}
			}
		}, "rag-ocr-stdout");
		outDrain.setDaemon(true);
		outDrain.start();

		try {
			boolean done = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
			if (!done) {
				throw new IOException("tesseract timed out after " + timeoutSeconds + "s");
			}
			// Process has exited; let the drain thread finish reading buffered stdout.
			outDrain.join(TimeUnit.SECONDS.toMillis(5));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("OCR interrupted", e);
		} finally {
			// Always reap the process — covers timeout, interruption, and a drain
			// thread that threw before EOF.
			if (proc.isAlive()) {
				proc.destroyForcibly();
			}
		}

		if (stdoutError[0] != null) {
			throw stdoutError[0];
		}
		int exit = proc.exitValue();
		if (exit != 0) {
			throw new IOException("tesseract exited " + exit);
		}
		byte[] stdout = stdoutHolder[0] != null ? stdoutHolder[0] : new byte[0];
		return new String(stdout, StandardCharsets.UTF_8);
	}

	private static byte[] drain(InputStream in) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1) {
			bos.write(buf, 0, n);
		}
		return bos.toByteArray();
	}

	// ---- config helpers (match the module's existing convention) -----------

	private static String resolve(String sysProp, String envVar, String def) {
		String v = System.getProperty(sysProp, "");
		if (!v.isEmpty()) {
			return v;
		}
		String env = System.getenv(envVar);
		return (env != null && !env.isEmpty()) ? env : def;
	}

	private static int intProp(String key, int def) {
		try {
			String v = System.getProperty(key);
			return (v == null || v.isEmpty()) ? def : Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
