package dev.erudites.mods.imageviewer.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;

public class ImageWebServer {

    private HttpServer server;
    private int port;

    public void start(int configPort) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(configPort), 0);
        this.port = this.server.getAddress().getPort();
        this.server.createContext("/", new RootHandler());
        this.server.createContext("/images/", new ImageHandler());
        this.server.setExecutor(null);
        this.server.start();
    }

    public void stop() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    public int getPort() {
        return this.port;
    }

    public String getUrl() {
        return "http://localhost:" + this.port + "/";
    }

    private static int extractNumber(String name) {
        String number = name.replaceAll("[^0-9]", "");
        return number.isEmpty() ? 0 : Integer.parseInt(number);
    }

    /**
     * Serves the HTML image viewer page.
     * "/"        → images from "imageviewer/images/"
     * "/{name}/" → images from "imageviewer/images/{name}/"
     */
    static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            String folderName;
            String imagePrefix;

            String trimmed = path.replaceAll("^/|/$", "");
            if (trimmed.isEmpty()) {
                folderName = "imageviewer/images";
                imagePrefix = "/images/";
            } else if (!trimmed.contains("/")) {
                folderName = "imageviewer/images/" + trimmed;
                imagePrefix = "/images/" + trimmed + "/";
            } else {
                folderName = "imageviewer/images";
                imagePrefix = "/images/";
            }

            File imagesDir = new File(folderName);
            if (!imagesDir.exists()) {
                imagesDir.mkdirs();
            }

            File[] files = imagesDir.listFiles((_, name) -> isImage(name));
            if (files == null) {
                files = new File[0];
            }

            Arrays.sort(files, Comparator.comparingInt(f -> extractNumber(f.getName())));

            String imageList = Arrays.stream(files)
                .map(f -> "\"" + f.getName() + "\"")
                .collect(Collectors.joining(","));

            String response = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "<meta charset='UTF-8'>\n" +
                "<style>\n" +
                "html, body { margin: 0; padding: 0; overflow: hidden; background: black; }\n" +
                "body { width: 100vw; height: 100vh; }\n" +
                "img {\n" +
                "  position: fixed; top: 0; left: 0;\n" +
                "  width: 100vw; height: 100vh;\n" +
                "  display: block;\n" +
                "  image-rendering: -webkit-optimize-contrast;\n" +
                "}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<img id='img' src='' />\n" +
                "<script>\n" +
                "var images = [" + imageList + "];\n" +
                "var index = 0;\n" +
                "var img = document.getElementById('img');\n" +
                "function update() { if(images.length > 0) img.src = '" + imagePrefix + "' + images[index]; }\n" +
                "update();\n" +
                "window.onmousedown = function(e) {\n" +
                "  if (e.button === 0) {\n" +
                "    if (index < images.length - 1) {\n" +
                "      index++; update();\n" +
                "    } else {\n" +
                "      window.location.href = 'imageviewer://close';\n" +
                "    }\n" +
                "  } else if (e.button === 2) {\n" +
                "    if (index > 0) { index--; update(); }\n" +
                "  }\n" +
                "};\n" +
                "window.oncontextmenu = function(e) { e.preventDefault(); return false; };\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";

            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().add("Connection", "close");
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream stream = exchange.getResponseBody();
            stream.write(bytes);
            stream.flush();
            stream.close();
        }

        private static boolean isImage(String name) {
            String lower = name.toLowerCase();
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        }
    }

    /**
     * Serves image files.
     * "/images/{filename}"           → "imageviewer/images/{filename}"
     * "/images/{subdir}/{filename}"  → "imageviewer/images/{subdir}/{filename}"
     */
    static class ImageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String withoutPrefix = path.substring("/images/".length());

            if (withoutPrefix.contains("..")) {
                byte[] response = "403 Forbidden\n".getBytes();
                exchange.sendResponseHeaders(403, response.length);
                exchange.getResponseBody().write(response);
                exchange.getResponseBody().close();
                return;
            }

            File file;
            int slashIdx = withoutPrefix.indexOf('/');
            if (slashIdx > 0) {
                String subdir = withoutPrefix.substring(0, slashIdx);
                String filename = withoutPrefix.substring(slashIdx + 1);
                file = new File(new File("imageviewer/images", subdir), filename);
            } else {
                file = new File("imageviewer/images", withoutPrefix);
            }

            if (file.exists() && file.isFile()) {
                String contentType = getContentType(file.getName());
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(200, file.length());
                OutputStream stream = exchange.getResponseBody();
                Files.copy(file.toPath(), stream);
                stream.close();
            } else {
                byte[] response = "404 Not Found\n".getBytes();
                exchange.sendResponseHeaders(404, response.length);
                exchange.getResponseBody().write(response);
                exchange.getResponseBody().close();
            }
        }

        private static String getContentType(String filename) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            return "application/octet-stream";
        }
    }
}
