package com.trecko.application.trecko;

import javafx.scene.web.WebView;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NoteRenderer {
    private static final String ASSET_ROOT = "/META-INF/resources/webjars/katex/0.16.22/dist/";
    private static final Pattern MATH = Pattern.compile("(?<!\\\\)\\$\\$([\\s\\S]+?)\\$\\$|(?<!\\\\)\\$([^$\\n]+?)\\$");
    private static final Pattern FONT = Pattern.compile("url\\((?:\\.\\./)?fonts/([^)'\\\"]+)");

    private NoteRenderer() {}

    static void show(WebView webView, String markdown) throws IOException {
        Path assets = installAssets();
        String html = render(markdown == null ? "" : markdown, assets);
        webView.getEngine().loadContent(html, "text/html");
    }

    private static Path installAssets() throws IOException {
        Path directory = Path.of(System.getProperty("user.home"), ".treckko", "katex", "0.16.22");
        Files.createDirectories(directory.resolve("fonts"));
        copyAsset("katex.min.js", directory.resolve("katex.min.js"));
        String css;
        try (InputStream input = resource("katex.min.css")) {
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher matcher = FONT.matcher(css);
        List<String> fonts = new ArrayList<>();
        while (matcher.find()) fonts.add(matcher.group(1));
        for (String font : fonts.stream().distinct().toList()) {
            copyAsset("fonts/" + font, directory.resolve("fonts").resolve(Path.of(font).getFileName()));
        }
        Files.writeString(directory.resolve("katex.min.css"), css, StandardCharsets.UTF_8);
        return directory;
    }

    static String render(String markdown, Path assets) throws IOException {
        String nonce = "TRECKOMATH" + UUID.randomUUID().toString().replace("-", "");
        List<String> expressions = new ArrayList<>();
        Matcher matcher = MATH.matcher(markdown);
        StringBuilder prepared = new StringBuilder();
        while (matcher.find()) {
            boolean display = matcher.group(1) != null;
            expressions.add((display ? "D:" : "I:") + (display ? matcher.group(1) : matcher.group(2)));
            matcher.appendReplacement(prepared, Matcher.quoteReplacement(nonce + expressions.size() + "TOKEN"));
        }
        matcher.appendTail(prepared);
        String body = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build()
                .render(Parser.builder().build().parse(prepared.toString()));
        for (int i = 0; i < expressions.size(); i++) {
            String encoded = expressions.get(i);
            boolean display = encoded.startsWith("D:");
            String expression = escapeAttribute(encoded.substring(2));
            String token = nonce + (i + 1) + "TOKEN";
            body = body.replace(token, "<span class=\"math\" data-display=\"" + display + "\" data-expr=\"" + expression + "\"></span>");
        }
        String base = assets.toUri().toString() + "/";
        return """
                <!doctype html><html><head><meta charset="utf-8">
                <base href="%s">
                <link rel="stylesheet" href="katex.min.css">
                <style>
                  body { margin: 0; padding: 18px 22px; background: #F8FAFC; color: #1E293B;
                    font: 14px/1.65 "Segoe UI", sans-serif; overflow-wrap: anywhere; }
                  a { color: #4338CA; } code, pre { font-family: Consolas, monospace; }
                  pre { padding: 12px; background: #F1F5F9; overflow-x: auto; }
                  blockquote { margin-left: 0; padding-left: 14px; border-left: 2px solid #4F46E5; color: #475569; }
                  .math { color: #1E293B; } .katex-display { overflow-x: auto; overflow-y: hidden; }
                </style><script src="katex.min.js"></script></head><body>%s
                <script>
                  document.querySelectorAll('.math').forEach(function(node) {
                    try { katex.render(node.dataset.expr, node, {
                      displayMode: node.dataset.display === 'true', throwOnError: false, strict: 'ignore'
                    }); } catch (error) { node.textContent = node.dataset.expr; }
                  });
                </script></body></html>
                """.formatted(base, body);
    }

    private static void copyAsset(String name, Path destination) throws IOException {
        try (InputStream input = resource(name)) {
            Files.copy(input, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static InputStream resource(String name) throws IOException {
        InputStream input = NoteRenderer.class.getResourceAsStream(ASSET_ROOT + name);
        if (input == null) throw new IOException("Bundled KaTeX asset is missing: " + name);
        return input;
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
