package com.essentialsync.s3;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Pure Java 21 streaming S3 client using java.net.http.HttpClient and AWS SigV4.
 * Zero external dependencies so it can be shared with CrashWatchdogMain.
 */
public final class S3Client {
    private static final String EMPTY_SHA256 = S3Signer.sha256Hex(new byte[0]);

    private final HttpClient http;
    private final String endpointUrl;
    private final String region;
    private final String bucketName;
    private final String worldFolderName;
    private final String accessKeyId;
    private final String secretAccessKey;

    public S3Client(
            String endpointUrl,
            String region,
            String bucketName,
            String worldFolderName,
            String accessKeyId,
            String secretAccessKey
    ) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_2)   // HTTP/2 multiplexing — many requests on 1 TLS connection
                .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) // virtual threads = zero blocking
                .build();
        this.endpointUrl = stripTrailingSlash(endpointUrl.trim());
        this.region = (region == null || region.isBlank()) ? "auto" : region.trim();
        this.bucketName = bucketName.trim();
        this.worldFolderName = worldFolderName.trim();
        this.accessKeyId = accessKeyId.trim();
        this.secretAccessKey = secretAccessKey.trim();
    }

    public String getWorldPrefix() {
        return worldFolderName + "/";
    }

    private URI buildObjectUri(String relativeKey) {
        String fullKey = worldFolderName + "/" + relativeKey;
        return URI.create(endpointUrl + "/" + S3Signer.urlEncode(bucketName) + "/" + S3Signer.encodeKeyPath(fullKey));
    }

    public Optional<String> getString(String relativeKey) throws IOException, InterruptedException {
        URI uri = buildObjectUri(relativeKey);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .GET();
        S3Signer.sign(builder, "GET", uri, region, accessKeyId, secretAccessKey, EMPTY_SHA256, null);

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("S3 GET " + relativeKey + " failed with HTTP " + response.statusCode() + ": " + response.body());
        }
        return Optional.of(response.body());
    }

    public boolean downloadFile(String relativeKey, Path dest) throws IOException, InterruptedException {
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        URI uri = buildObjectUri(relativeKey);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .GET();
        S3Signer.sign(builder, "GET", uri, region, accessKeyId, secretAccessKey, EMPTY_SHA256, null);

        HttpResponse<Path> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofFile(dest));
        if (response.statusCode() == 404) {
            Files.deleteIfExists(dest);
            return false;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(dest);
            throw new IOException("S3 download " + relativeKey + " failed with HTTP " + response.statusCode());
        }
        return true;
    }

    public void putString(String relativeKey, String content, String contentType) throws IOException, InterruptedException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String payloadHash = S3Signer.sha256Hex(bytes);
        URI uri = buildObjectUri(relativeKey);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        S3Signer.sign(builder, "PUT", uri, region, accessKeyId, secretAccessKey, payloadHash, contentType);

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("S3 PUT " + relativeKey + " failed with HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    public void putFile(String relativeKey, Path file, String sha256Hex) throws IOException, InterruptedException {
        URI uri = buildObjectUri(relativeKey);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .PUT(HttpRequest.BodyPublishers.ofFile(file));
        S3Signer.sign(builder, "PUT", uri, region, accessKeyId, secretAccessKey, sha256Hex, "application/octet-stream");

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("S3 PUT file " + relativeKey + " failed with HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    public boolean headObject(String relativeKey) throws IOException, InterruptedException {
        URI uri = buildObjectUri(relativeKey);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .method("HEAD", HttpRequest.BodyPublishers.noBody());
        S3Signer.sign(builder, "HEAD", uri, region, accessKeyId, secretAccessKey, EMPTY_SHA256, null);

        HttpResponse<Void> response = http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 404) {
            return false;
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return true;
        }
        throw new IOException("S3 HEAD " + relativeKey + " failed with HTTP " + response.statusCode());
    }

    public void deleteObject(String relativeKey) throws IOException, InterruptedException {
        URI uri = buildObjectUri(relativeKey);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .DELETE();
        S3Signer.sign(builder, "DELETE", uri, region, accessKeyId, secretAccessKey, EMPTY_SHA256, null);

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            return;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("S3 DELETE " + relativeKey + " failed with HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    /**
     * Lists relative keys under `<worldFolderName>/<relativePrefix>` using ListObjectsV2.
     */
    public List<String> listObjects(String relativePrefix) throws IOException, InterruptedException {
        List<String> keys = new ArrayList<>();
        String fullPrefix = worldFolderName + "/" + relativePrefix;
        String continuationToken = null;

        do {
            StringBuilder query = new StringBuilder();
            query.append("list-type=2&prefix=").append(S3Signer.urlEncode(fullPrefix));
            if (continuationToken != null && !continuationToken.isEmpty()) {
                query.append("&continuation-token=").append(S3Signer.urlEncode(continuationToken));
            }
            URI uri = URI.create(endpointUrl + "/" + S3Signer.urlEncode(bucketName) + "?" + query);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            S3Signer.sign(builder, "GET", uri, region, accessKeyId, secretAccessKey, EMPTY_SHA256, null);

            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return keys;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("S3 ListObjectsV2 failed with HTTP " + response.statusCode() + ": " + response.body());
            }

            ListPage page = parseListXml(response.body(), worldFolderName + "/");
            keys.addAll(page.keys);
            continuationToken = page.nextToken;
        } while (continuationToken != null && !continuationToken.isEmpty());

        return keys;
    }

    public CompletableFuture<Void> postDiscordWebhookAsync(String webhookUrl, String message) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            String json = MiniJson.stringify(Map.of("content", message));
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl.trim()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            return http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).thenApply(r -> null);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    public void postDiscordWebhookBlocking(String webhookUrl, String message) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        try {
            String json = MiniJson.stringify(Map.of("content", message));
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl.trim()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    private static ListPage parseListXml(String xml, String stripPrefix) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            List<String> keys = new ArrayList<>();
            NodeList keyNodes = doc.getElementsByTagName("Key");
            for (int i = 0; i < keyNodes.getLength(); i++) {
                String fullKey = keyNodes.item(i).getTextContent();
                if (fullKey.startsWith(stripPrefix)) {
                    keys.add(fullKey.substring(stripPrefix.length()));
                } else {
                    keys.add(fullKey);
                }
            }

            boolean truncated = false;
            NodeList truncNodes = doc.getElementsByTagName("IsTruncated");
            if (truncNodes.getLength() > 0) {
                truncated = "true".equalsIgnoreCase(truncNodes.item(0).getTextContent().trim());
            }

            String nextToken = null;
            if (truncated) {
                NodeList tokenNodes = doc.getElementsByTagName("NextContinuationToken");
                if (tokenNodes.getLength() > 0) {
                    nextToken = tokenNodes.item(0).getTextContent().trim();
                }
            }
            return new ListPage(keys, nextToken);
        } catch (Exception e) {
            throw new IOException("Failed to parse S3 ListObjectsV2 XML response", e);
        }
    }

    private static String stripTrailingSlash(String url) {
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private record ListPage(List<String> keys, String nextToken) {
    }
}
