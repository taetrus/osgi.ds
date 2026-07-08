package com.kk.pde.ds.rag.internal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kk.pde.ds.rag.api.DocumentParser;
import com.kk.pde.ds.rag.api.OcrEngine;

/**
 * {@link DocumentParser} that extracts text from the formats the RAG feature
 * needs, choosing a strategy per file type:
 *
 * <ul>
 *   <li><b>.pdf</b> — Apache PDFBox ({@code PDFTextStripper}).</li>
 *   <li><b>.docx / .pptx</b> — read the OOXML zip directly and pull the text out
 *       of the relevant XML parts. OOXML is just a zip of XML, so this needs no
 *       heavyweight office library (and avoids POI's large dependency tree).</li>
 *   <li><b>.html / .htm</b> — strip tags and decode entities.</li>
 *   <li><b>.txt / .md</b> — read as UTF-8.</li>
 *   <li><b>image files</b> (.png/.jpg/.jpeg/.tif/.tiff/.bmp/.gif) — OCR the file
 *       directly (empty result when OCR is unavailable).</li>
 * </ul>
 *
 * <p>PDFBox logs via commons-logging (JCL), not slf4j, so it does not perturb the
 * project's slf4j 1.7 / Logback stack. Everything else here is dependency-free.</p>
 *
 * <p><b>OCR.</b> When an {@link OcrEngine} is available, text rendered as pixels is
 * recovered too: a PDF page with no usable text layer is rasterized and OCR'd as a
 * scan, while images embedded in text PDF pages and in {@code .docx} ({@code word/media/})
 * are OCR'd and appended. OCR is the only step that may shell out to an external
 * process; pure text extraction stays self-contained.</p>
 */
@Component(service = DocumentParser.class)
public class MultiFormatDocumentParser implements DocumentParser {

	private static final Logger LOG = LoggerFactory.getLogger(MultiFormatDocumentParser.class);

	private static final Set<String> SUPPORTED_EXTENSIONS = new HashSet<String>(Arrays.asList(
		"pdf", "docx", "pptx", "txt", "md", "html", "htm"));

	/** Image extensions accepted as standalone documents and OCR'd when embedded in OOXML media folders. */
	private static final Set<String> IMAGE_EXTENSIONS = new HashSet<String>(Arrays.asList(
		"png", "jpg", "jpeg", "bmp", "tif", "tiff", "gif"));

	private static final int DEFAULT_DPI = 300;
	private static final int DEFAULT_MIN_CHARS_PER_PAGE = 16;
	private static final int DEFAULT_MAX_IMAGES_PER_DOC = 200;

	private OcrEngine ocr;
	private int ocrDpi;
	private int minCharsPerPage;
	private int maxImagesPerDoc;

	@Reference
	public void setOcr(OcrEngine ocr) {
		this.ocr = ocr;
	}

	@Activate
	public void activate() {
		this.ocrDpi = intProp("rag.ocr.dpi", DEFAULT_DPI);
		this.minCharsPerPage = intProp("rag.ocr.min.chars.per.page", DEFAULT_MIN_CHARS_PER_PAGE);
		this.maxImagesPerDoc = intProp("rag.ocr.max.images.per.doc", DEFAULT_MAX_IMAGES_PER_DOC);
		LOG.info("MultiFormatDocumentParser activated (PDFBox + direct OOXML/HTML/text; OCR available={})",
			ocr != null && ocr.isAvailable());
	}

	@Override
	public boolean supports(Path file) {
		String ext = extension(file);
		return SUPPORTED_EXTENSIONS.contains(ext) || IMAGE_EXTENSIONS.contains(ext);
	}

	@Override
	public String extractText(Path file) throws IOException {
		String ext = extension(file);
		if ("pdf".equals(ext)) {
			return extractPdf(file);
		}
		if ("docx".equals(ext)) {
			// word/media/ images are OCR'd; pptx media OCR is deferred (mediaPrefix=null).
			return extractOoxml(file, "word/document.xml", null, "word/media/");
		}
		if ("pptx".equals(ext)) {
			return extractOoxml(file, null, "ppt/slides/slide", null);
		}
		if ("html".equals(ext) || "htm".equals(ext)) {
			return htmlToText(readUtf8(file));
		}
		if (IMAGE_EXTENSIONS.contains(ext)) {
			return extractImage(file);
		}
		// txt, md, and anything else supported: plain UTF-8.
		return readUtf8(file);
	}

	// ---- standalone images -------------------------------------------------

	private String extractImage(Path file) throws IOException {
		if (ocr == null || !ocr.isAvailable()) {
			LOG.warn("Image '{}' skipped: OCR is unavailable, so its content cannot be indexed.",
				file.getFileName());
			return "";
		}
		return ocr.ocr(Files.readAllBytes(file)).trim();
	}

	// ---- PDF ---------------------------------------------------------------

	private String extractPdf(Path file) throws IOException {
		// Stream scratch data to temp files rather than holding the whole document in
		// heap, so a very large PDF can't OOM the process.
		PDDocument doc = PDDocument.load(file.toFile(), MemoryUsageSetting.setupTempFileOnly());
		try {
			boolean useOcr = ocr != null && ocr.isAvailable();
			if (!useOcr) {
				// Original fast path: text layer only.
				PDFTextStripper stripper = new PDFTextStripper();
				stripper.setSortByPosition(true);
				return stripper.getText(doc);
			}
			return extractPdfWithOcr(doc);
		} finally {
			doc.close();
		}
	}

	/**
	 * Per-page extraction with OCR. For each page: if its text layer is empty/sparse the
	 * page is treated as a scan — rasterized and OCR'd whole; otherwise its text is kept
	 * and every embedded raster image on the page is OCR'd and appended. The scan branch
	 * deliberately does <i>not</i> also OCR embedded images, since the full-page raster
	 * already captures them (a scanned page is typically one page-sized image).
	 */
	private String extractPdfWithOcr(PDDocument doc) throws IOException {
		PDFRenderer renderer = new PDFRenderer(doc);
		StringBuilder out = new StringBuilder();
		int[] imageBudget = { maxImagesPerDoc };
		int pages = doc.getNumberOfPages();
		for (int i = 0; i < pages; i++) {
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			stripper.setStartPage(i + 1); // PDFTextStripper page numbers are 1-based.
			stripper.setEndPage(i + 1);
			String pageText = stripper.getText(doc);

			if (pageText.trim().length() < minCharsPerPage) {
				// Scanned / image-only page: rasterize and OCR the whole page.
				try {
					BufferedImage img = renderer.renderImageWithDPI(i, ocrDpi, ImageType.RGB);
					String ocrText = ocr.ocr(pngBytes(img)).trim();
					if (!ocrText.isEmpty()) {
						out.append(ocrText).append("\n\n");
					}
				} catch (IOException e) {
					LOG.warn("OCR of scanned page {} failed: {}", i + 1, e.getMessage());
				} catch (RuntimeException e) {
					// PDFBox rendering can throw unchecked on malformed content.
					LOG.warn("Rendering page {} for OCR failed: {}", i + 1, e.getMessage());
				}
			} else {
				out.append(pageText);
				ocrPageImages(doc.getPage(i), out, imageBudget);
			}
		}
		return collapseBlankLines(out.toString());
	}

	/** OCR each embedded raster image on a page, appending recognized text. */
	private void ocrPageImages(PDPage page, StringBuilder out, int[] imageBudget) {
		PDResources res = page.getResources();
		if (res == null) {
			return;
		}
		try {
			for (COSName name : res.getXObjectNames()) {
				if (imageBudget[0] <= 0) {
					return;
				}
				PDXObject xobj;
				try {
					xobj = res.getXObject(name);
				} catch (IOException e) {
					continue; // skip an unreadable XObject
				}
				if (xobj instanceof PDImageXObject) {
					imageBudget[0]--;
					ocrImage(((PDImageXObject) xobj), out);
				} else if (xobj instanceof PDFormXObject) {
					// Forms can nest further images (e.g. logos inside headers).
					PDResources formRes = ((PDFormXObject) xobj).getResources();
					if (formRes != null) {
						ocrPageImagesIn(formRes, out, imageBudget);
					}
				}
			}
		} catch (RuntimeException e) {
			LOG.warn("Walking page images failed: {}", e.getMessage());
		}
	}

	/** Recurse into a form XObject's resources for nested images. */
	private void ocrPageImagesIn(PDResources res, StringBuilder out, int[] imageBudget) {
		for (COSName name : res.getXObjectNames()) {
			if (imageBudget[0] <= 0) {
				return;
			}
			PDXObject xobj;
			try {
				xobj = res.getXObject(name);
			} catch (IOException e) {
				continue;
			}
			if (xobj instanceof PDImageXObject) {
				imageBudget[0]--;
				ocrImage(((PDImageXObject) xobj), out);
			} else if (xobj instanceof PDFormXObject) {
				PDResources nested = ((PDFormXObject) xobj).getResources();
				if (nested != null) {
					ocrPageImagesIn(nested, out, imageBudget);
				}
			}
		}
	}

	private void ocrImage(PDImageXObject image, StringBuilder out) {
		try {
			BufferedImage bi = image.getImage();
			if (bi == null) {
				return;
			}
			String text = ocr.ocr(pngBytes(bi)).trim();
			if (!text.isEmpty()) {
				out.append('\n').append(text).append('\n');
			}
		} catch (IOException e) {
			LOG.warn("OCR of embedded image failed: {}", e.getMessage());
		} catch (RuntimeException e) {
			LOG.warn("Decoding embedded image for OCR failed: {}", e.getMessage());
		}
	}

	private static byte[] pngBytes(BufferedImage img) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(img, "png", bos);
		return bos.toByteArray();
	}

	// ---- OOXML (.docx / .pptx) --------------------------------------------

	/**
	 * Extract text from an OOXML package.
	 *
	 * @param exactEntry   a single zip entry to read (docx body), or null
	 * @param entryPrefix  read every entry whose name starts with this prefix
	 *                     and ends in .xml (pptx slides), or null
	 * @param mediaPrefix  OCR every image entry under this prefix (docx {@code word/media/}),
	 *                     appending recognized text after the body, or null to skip
	 */
	private String extractOoxml(Path file, String exactEntry, String entryPrefix, String mediaPrefix)
			throws IOException {
		StringBuilder out = new StringBuilder();
		boolean useOcr = mediaPrefix != null && ocr != null && ocr.isAvailable();
		List<byte[]> mediaImages = new ArrayList<byte[]>();
		int imagesLeft = maxImagesPerDoc;
		InputStream fis = Files.newInputStream(file);
		try {
			ZipInputStream zip = new ZipInputStream(fis);
			ZipEntry entry;
			// Collect matching slide entries so they can be ordered (slide1, slide2…).
			List<String[]> slideParts = new ArrayList<String[]>(); // [name, xml]
			while ((entry = zip.getNextEntry()) != null) {
				String name = entry.getName();
				if (exactEntry != null && name.equals(exactEntry)) {
					out.append(ooxmlXmlToText(readEntry(zip)));
				} else if (entryPrefix != null && name.startsWith(entryPrefix) && name.endsWith(".xml")) {
					slideParts.add(new String[] { name, readEntry(zip) });
				} else if (useOcr && imagesLeft > 0 && name.startsWith(mediaPrefix)
						&& IMAGE_EXTENSIONS.contains(extension(name))) {
					mediaImages.add(readEntryBytes(zip));
					imagesLeft--;
				}
				zip.closeEntry();
			}
			if (!slideParts.isEmpty()) {
				slideParts.sort((a, b) -> compareSlideNames(a[0], b[0]));
				for (String[] part : slideParts) {
					String text = ooxmlXmlToText(part[1]).trim();
					if (!text.isEmpty()) {
						out.append(text).append("\n\n");
					}
				}
			}
			zip.close();
		} finally {
			fis.close();
		}
		// OCR media images after the body so document text stays first.
		for (byte[] img : mediaImages) {
			try {
				String text = ocr.ocr(img).trim();
				if (!text.isEmpty()) {
					out.append('\n').append(text).append('\n');
				}
			} catch (IOException e) {
				LOG.warn("OCR of an embedded docx image failed: {}", e.getMessage());
			}
		}
		return collapseBlankLines(out.toString());
	}

	/**
	 * Turn an OOXML part's XML into text: paragraph elements ({@code w:p}/{@code a:p})
	 * become line breaks, every other tag is dropped, and entities are decoded.
	 * The visible text lives in {@code w:t}/{@code a:t} runs, which become bare text
	 * once the surrounding tags are stripped.
	 */
	private String ooxmlXmlToText(String xml) {
		if (xml == null || xml.isEmpty()) {
			return "";
		}
		// Paragraph boundaries -> newlines (handle both Word and PowerPoint namespaces).
		String s = xml.replaceAll("</w:p>", "\n").replaceAll("</a:p>", "\n");
		// Explicit line breaks and tabs.
		s = s.replaceAll("<w:br\\b[^>]*/>", "\n").replaceAll("<w:tab\\b[^>]*/>", "\t");
		// Drop all remaining tags.
		s = s.replaceAll("<[^>]+>", "");
		return collapseBlankLines(decodeXmlEntities(s));
	}

	private static int compareSlideNames(String a, String b) {
		return Integer.compare(slideNumber(a), slideNumber(b));
	}

	private static int slideNumber(String name) {
		// Pull the trailing integer out of ".../slideN.xml".
		int end = name.lastIndexOf(".xml");
		int i = end - 1;
		while (i >= 0 && Character.isDigit(name.charAt(i))) {
			i--;
		}
		try {
			return (i + 1 < end) ? Integer.parseInt(name.substring(i + 1, end)) : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// ---- HTML --------------------------------------------------------------

	private String htmlToText(String html) {
		if (html == null) {
			return "";
		}
		// Remove script/style blocks wholesale (case-insensitive, across newlines).
		String s = html.replaceAll("(?is)<script\\b.*?</script>", " ")
			.replaceAll("(?is)<style\\b.*?</style>", " ");
		// Block-ish tags -> newlines so structure survives stripping.
		s = s.replaceAll("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)\\b[^>]*>", "\n");
		// Drop remaining tags + decode entities.
		s = s.replaceAll("<[^>]+>", "");
		return collapseBlankLines(decodeXmlEntities(s));
	}

	// ---- shared helpers ----------------------------------------------------

	private static String readUtf8(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	private static String readEntry(ZipInputStream zip) throws IOException {
		return new String(readEntryBytes(zip), StandardCharsets.UTF_8);
	}

	/** Cap on a single decompressed OOXML entry — bounds heap against a zip bomb. */
	private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;

	private static byte[] readEntryBytes(ZipInputStream zip) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		long total = 0;
		int n;
		while ((n = zip.read(buf)) != -1) {
			total += n;
			if (total > MAX_ENTRY_BYTES) {
				throw new IOException("OOXML entry exceeds " + (MAX_ENTRY_BYTES / (1024 * 1024))
					+ " MB cap (possible zip bomb)");
			}
			bos.write(buf, 0, n);
		}
		return bos.toByteArray();
	}

	/** Decode the small set of XML/HTML entities that appear in document text. */
	private static String decodeXmlEntities(String s) {
		if (s.indexOf('&') < 0) {
			return s;
		}
		StringBuilder sb = new StringBuilder(s.length());
		int i = 0;
		while (i < s.length()) {
			char c = s.charAt(i);
			if (c == '&') {
				int semi = s.indexOf(';', i + 1);
				if (semi > i && semi - i <= 10) {
					String ent = s.substring(i + 1, semi);
					String rep = entity(ent);
					if (rep != null) {
						sb.append(rep);
						i = semi + 1;
						continue;
					}
				}
			}
			sb.append(c);
			i++;
		}
		return sb.toString();
	}

	private static String entity(String ent) {
		if ("amp".equals(ent)) return "&";
		if ("lt".equals(ent)) return "<";
		if ("gt".equals(ent)) return ">";
		if ("quot".equals(ent)) return "\"";
		if ("apos".equals(ent)) return "'";
		if ("nbsp".equals(ent)) return " ";
		if (ent.length() > 1 && ent.charAt(0) == '#') {
			try {
				int code = (ent.charAt(1) == 'x' || ent.charAt(1) == 'X')
					? Integer.parseInt(ent.substring(2), 16)
					: Integer.parseInt(ent.substring(1));
				return String.valueOf((char) code);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	/** Collapse 3+ consecutive newlines to a single blank line; trim trailing spaces. */
	private static String collapseBlankLines(String s) {
		return s.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
	}

	private static String extension(Path file) {
		return extension(file.getFileName().toString());
	}

	private static String extension(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		int dot = lower.lastIndexOf('.');
		return (dot >= 0 && dot < lower.length() - 1) ? lower.substring(dot + 1) : "";
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
