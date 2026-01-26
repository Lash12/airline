package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class WikimediaPhotoUtil {
    private static final Logger logger = LoggerFactory.getLogger(WikimediaPhotoUtil.class);

    private static final String WIKIMEDIA_ENDPOINT = "https://commons.wikimedia.org/w/api.php";
    private static final String USER_AGENT = "airline-wiki-banner-fetcher/1.0";

    private static final String DEFAULT_BANNER_SEARCH = "airline banner";

    private static final List<String> bannerUrls = new ArrayList<>();

    static {
        refreshBanners();
    }

    private static Random random = new Random();

    public static void refreshBanners() {
        synchronized (bannerUrls) {
            bannerUrls.clear();
            bannerUrls.addAll(loadBannerUrls());
        }
    }

    public static String drawBannerUrl() {
        synchronized (bannerUrls) {
            if (bannerUrls.isEmpty()) {
                return null;
            } else {
                return bannerUrls.get(random.nextInt(bannerUrls.size()));
            }
        }
    }

    private static List<String> loadBannerUrls() {
        Config configFactory = ConfigFactory.load();
        String searchTerm = configFactory.hasPath("wikimedia.bannerSearch") ? configFactory.getString("wikimedia.bannerSearch") : DEFAULT_BANNER_SEARCH;

        StringBuilder searchQuery = new StringBuilder(WIKIMEDIA_ENDPOINT + "?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=50&prop=imageinfo&iiprop=url&iiurlwidth=1600&gsrsearch=");

        try {
            searchQuery.append(URLEncoder.encode(searchTerm, StandardCharsets.UTF_8.toString()));
        } catch (Exception e) {
            logger.warn("Failed to encode banner search term: " + searchTerm, e);
            return Collections.emptyList();
        }

        URL url;
        try {
            url = new URL(searchQuery.toString());
        } catch (MalformedURLException e) {
            logger.warn("Wikimedia URL is malformed: " + e.getMessage(), e);
            return Collections.emptyList();
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);

            if (conn.getResponseCode() != 200) {
                logger.info("Failed to load banners from Wikimedia, response code " + conn.getResponseCode());
                return Collections.emptyList();
            }

            JsonNode result = Json.parse(conn.getInputStream());
            JsonNode pagesNode = result.path("query").path("pages");
            if (pagesNode.isMissingNode()) {
                logger.info("No banner pages found from Wikimedia. Response: " + result);
                return Collections.emptyList();
            }

            List<String> urls = new ArrayList<>();
            pagesNode.fields().forEachRemaining(entry -> {
                JsonNode imageInfo = entry.getValue().path("imageinfo");
                if (!imageInfo.isMissingNode() && imageInfo.size() > 0) {
                    JsonNode info = imageInfo.get(0);
                    String imageUrl = info.hasNonNull("thumburl") ? info.get("thumburl").asText() : info.path("url").asText(null);
                    if (imageUrl != null && !imageUrl.isEmpty()) {
                        urls.add(imageUrl);
                    }
                }
            });

            return urls;
        } catch (IOException e) {
            logger.warn("Failed to use Wikimedia API for banners: " + e.getMessage(), e);
            return Collections.emptyList();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
