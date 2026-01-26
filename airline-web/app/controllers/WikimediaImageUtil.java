package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.patson.data.WikimediaResourceSource;
import com.patson.model.Airport;
import com.patson.model.media.ResourceType;
import com.patson.model.media.WikimediaResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import scala.Option;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class WikimediaImageUtil {
	private final static Logger logger = LoggerFactory.getLogger(WikimediaImageUtil.class);
	private final static int MAX_PHOTO_WIDTH = 1000;

	private static final String WIKIMEDIA_ENDPOINT = "https://commons.wikimedia.org/w/api.php";
	private static final String USER_AGENT = "airline-wiki-image-fetcher/1.0";

	private static LoadingCache<CityKey, Optional<URL>> cityCache = CacheBuilder.newBuilder().maximumSize(100000).expireAfterWrite(1, TimeUnit.DAYS).build(new ResourceCacheLoader<>(key -> loadCityImageUrl(key.cityName), ResourceType.CITY_IMAGE().id()));
	private static LoadingCache<AirportKey, Optional<URL>> airportCache = CacheBuilder.newBuilder().maximumSize(100000).expireAfterWrite(1, TimeUnit.DAYS).build(new ResourceCacheLoader<>(key -> 	loadAirportImageUrl(key.airportName), ResourceType.AIRPORT_IMAGE().id()));

	private interface LoadFunction<T, R> {
		R apply(T t) throws NoLongerValidException;
	}

	private static class ResourceCacheLoader<KeyType extends Key> extends CacheLoader<KeyType, Optional<URL>> {
		private static final int DEFAULT_MAX_AGE = 24 * 60 * 60; //in sec
		private final LoadFunction<KeyType, UrlResult> loadFunction;
		private final int resourceTypeValue;

		private ResourceCacheLoader(LoadFunction<KeyType, UrlResult> loadFunction, int resourceTypeValue) {
			this.loadFunction = loadFunction;
			this.resourceTypeValue = resourceTypeValue;
		}

		public Optional<URL> load(KeyType key) {
			logger.info("Loading wikimedia resource on " + key);
			//try from db first
			Option<WikimediaResource> resourceOption = WikimediaResourceSource.loadResource(key.getId(), ResourceType.apply(resourceTypeValue));

			if (resourceOption.isDefined()) {
				logger.info("Found previous wikimedia resource on " + key + " resource " + resourceOption.get());
				WikimediaResource resource = resourceOption.get();
				if (resource.url() == null) { //previous successful query returns no result, do not proceed
					return Optional.empty();
				}
				if (!resource.maxAgeDeadline().isEmpty() && System.currentTimeMillis() <= (Long) resource.maxAgeDeadline().get()) {
					try {
						return Optional.of(new URL(resource.url()));
					} catch (MalformedURLException e) {
						logger.warn("Stored URL is malformed: " + e.getMessage(), e);
					}
				} else { //max deadline expired, try and see if the url still works
					Optional<Long> newDeadline = isUrlValid(resource.url());
					if (newDeadline != null) {
						WikimediaResourceSource.insertResource().apply(WikimediaResource.apply(resource.resourceId(), resource.resourceType(), resource.url(), newDeadline.isPresent() ? Option.apply(newDeadline.get()) : Option.empty()));
						try {
							return Optional.of(new URL(resource.url()));
						} catch (MalformedURLException e) {
							logger.warn("Stored URL is malformed: " + e.getMessage(), e);
						}
					}
				}
			} else {
				logger.info("No previous wikimedia resource on " + key);
			}

			//no previous successful query done, or the result is no longer valid
			try {
				UrlResult result = loadFunction.apply(key);
				logger.info("loaded " + ResourceType.apply(resourceTypeValue) + " image for  " + key + " " + result);
				if (result != null) {
					Long deadline = System.currentTimeMillis() + (result.maxAge != null ? result.maxAge * 1000 : DEFAULT_MAX_AGE * 1000);
					WikimediaResourceSource.insertResource().apply(WikimediaResource.apply(key.getId(), ResourceType.apply(resourceTypeValue), result.url.toString(), deadline != null ? Option.apply(deadline) : Option.empty()));

					return Optional.of(result.url);
				} else { //There is no result, save to DB, as we do not want to retry this at all
					WikimediaResourceSource.insertResource().apply(WikimediaResource.apply(key.getId(), ResourceType.apply(resourceTypeValue), null, Option.empty()));
					return Optional.empty();
				}
			} catch (NoLongerValidException e) {
				//purge the old record since it's no longer valid
				logger.info("Wikimedia resource on " + key + " is no longer valid");
				WikimediaResourceSource.deleteResource(key.getId(), ResourceType.apply(resourceTypeValue));
				return Optional.empty();
			} catch (Throwable t) {
				logger.warn("Unexpected failure for wikimedia resource loading on " + key + " : " + t.getMessage(), t);
				return Optional.empty();
			}
		}
	}

	public static void invalidate(Key key) {
		if (key instanceof CityKey) {
			cityCache.invalidate(key);
			WikimediaResourceSource.deleteResource(key.getId(), ResourceType.CITY_IMAGE());
		} else if (key instanceof AirportKey) {
			airportCache.invalidate(key);
			WikimediaResourceSource.deleteResource(key.getId(), ResourceType.AIRPORT_IMAGE());
		}
	}

	/**
	 *
	 * @param urlString
	 * @return	null if not valid, a new maxAge Option if valid
	 */
	private static Optional<Long> isUrlValid(String urlString) {
		URL url;
		try {
			url = new URL(urlString);
		} catch (MalformedURLException e) {
			logger.warn("URL " + urlString + " is not valid : " + e.getMessage(), e);
			return null;
		}

		HttpURLConnection conn = null;

		try {
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("User-Agent", USER_AGENT);
			if (conn.getResponseCode() == 200) {
				Long maxAge = getMaxAge(conn);
				if (maxAge != null) {
					long newDeadline = System.currentTimeMillis() + maxAge * 1000;
					logger.debug(urlString + " is still valid, new max age deadline: " + newDeadline) ;
					return Optional.of(newDeadline);
				} else {
					logger.debug(urlString + " is still valid, no max age deadline");
					return Optional.empty();
				}
			} else {
				logger.info(urlString + " is no longer valid " + conn.getResponseCode());
				return null;
			}
		} catch (IOException e) {
			logger.warn(urlString + " failed with valid check : " + e.getMessage());
			return null;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}


	}

	private static Long getMaxAge(HttpURLConnection conn) {
		String cacheControl = conn.getHeaderField("Cache-Control");
		if (cacheControl != null) {
			for (String entry : cacheControl.split(",")) {
				entry = entry.toLowerCase().trim();
				if (entry.startsWith("max-age=")) {
					try {
						return Long.valueOf(entry.substring("max-age=".length()).trim());
					} catch (NumberFormatException e) {
						logger.warn("Invalid max-age : " + entry);
					}
				}
			}
		}
		return null;
	}

	public static class CityKey extends Key {
		private final int id;
		private String cityName;

		public CityKey(int id, String cityName) {
			this.id = id;
			this.cityName = cityName;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			CityKey cityKey = (CityKey) o;

			if (id != cityKey.id) return false;
			return cityName != null ? cityName.equals(cityKey.cityName) : cityKey.cityName == null;

		}

		@Override
		public int hashCode() {
			int result;
			result = id;
			result = 31 * result + (cityName != null ? cityName.hashCode() : 0);
			return result;
		}

		@Override
		public String toString() {
			return "CityKey{" +
					"id=" + id +
					", cityName='" + cityName + '\'' +
					'}';
		}

		@Override
		public int getId() {
			return id;
		}
	}

	public static class AirportKey extends Key{
		private final int id;
		private String airportName;

		public AirportKey(int id, String airportName) {
			this.id = id;
			this.airportName = airportName;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			AirportKey that = (AirportKey) o;

			if (id != that.id) return false;
			return airportName != null ? airportName.equals(that.airportName) : that.airportName == null;

		}

		@Override
		public int hashCode() {
			int result;
			result = id;
			result = 31 * result + (airportName != null ? airportName.hashCode() : 0);
			return result;
		}

		@Override
		public String toString() {
			return "AirportKey{" +
					"id=" + id +
					", airportName='" + airportName + '\'' +
					'}';
		}

		@Override
		public int getId() {
			return id;
		}
	}

	public static abstract class Key {
		abstract int getId();
	}

	public static URL getCityImageUrl(Airport airport) {
		return getCityImageUrl(airport.id(), airport.city());
	}

	static URL getCityImageUrl(int airportId, String cityName) {
		try {
			Optional<URL> result = cityCache.get(new CityKey(airportId, cityName));
			return result.orElse(null);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	static URL getAirportImageUrl(Airport airport) {
		return getAirportImageUrl(airport.id(), airport.name());
	}
	static URL getAirportImageUrl(int airportId, String airportName) {
		try {
			Optional<URL> result = airportCache.get(new AirportKey(airportId, airportName));
			return result.orElse(null);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}


	public static UrlResult loadCityImageUrl(String cityName) throws NoLongerValidException {
		if (cityName == null) {
			return null;
		}
		List<String> phrases = Arrays.asList(cityName + " skyline", cityName + " city", cityName);
		return loadImageUrl(phrases);
	}

	public static UrlResult loadAirportImageUrl(String airportName) throws NoLongerValidException {
		if (airportName == null) {
			return null;
		}
		List<String> phrases = Arrays.asList(airportName + " airport", airportName);
		return loadImageUrl(phrases);
	}

	/**
	 * Executes actual Wikimedia query
	 * @param phrases
	 * @return
	 */
	public static UrlResult loadImageUrl(List<String> phrases) throws NoLongerValidException {
		if (phrases.isEmpty()) {
			return null;
		}

		for (String phrase : phrases) {
			UrlResult result = loadImageUrlFromWikimedia(phrase);
			if (result != null) {
				return result;
			}
		}

		return null;
	}

	private static UrlResult loadImageUrlFromWikimedia(String phrase) throws NoLongerValidException {
		StringBuilder searchQuery = new StringBuilder(WIKIMEDIA_ENDPOINT + "?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=1&prop=imageinfo&iiprop=url&iiurlwidth=" + MAX_PHOTO_WIDTH + "&gsrsearch=");

		try {
			searchQuery.append(URLEncoder.encode(phrase, StandardCharsets.UTF_8.toString()));
		} catch (UnsupportedEncodingException e) {
			logger.warn("Failed to encode search phrase: " + phrase, e);
			return null;
		}

		URL url;
		try {
			url = new URL(searchQuery.toString());
		} catch (MalformedURLException e) {
			logger.warn("Wikimedia URL is malformed: " + e.getMessage(), e);
			return null;
		}

		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("Accept", "application/json");
			conn.setRequestProperty("User-Agent", USER_AGENT);

			if (conn.getResponseCode() != 200) {
				logger.info("Failed to find image for " + phrase + " response code " + conn.getResponseCode());
				return null;
			}

			JsonNode result = Json.parse(conn.getInputStream());
			JsonNode pagesNode = result.path("query").path("pages");
			if (pagesNode.isMissingNode() || !pagesNode.fields().hasNext()) {
				logger.info("Failed to find image for " + phrase + " no pages. Response: " + result);
				return null;
			}

			JsonNode imageInfo = pagesNode.fields().next().getValue().path("imageinfo");
			if (imageInfo.isMissingNode() || imageInfo.size() == 0) {
				logger.info("Failed to find image for " + phrase + " no imageinfo. Response: " + result);
				return null;
			}

			JsonNode info = imageInfo.get(0);
			String imageUrl = info.hasNonNull("thumburl") ? info.get("thumburl").asText() : info.path("url").asText(null);
			if (imageUrl == null || imageUrl.isEmpty()) {
				logger.info("Failed to find image for " + phrase + " empty URL. Response: " + result);
				return null;
			}

			Long maxAge = getMaxAge(conn);
			return new UrlResult(new URL(imageUrl), maxAge);
		} catch (IOException e) {
			logger.warn("Failed to use Wikimedia API : " + e.getMessage(), e);
			return null;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private static class UrlResult {
		private URL url;
		private Long maxAge;

		private UrlResult(URL url, Long maxAge) {
			this.url = url;
			this.maxAge = maxAge;
		}

		@Override
		public String toString() {
			return "UrlResult{" +
					"url=" + url +
					", maxAge=" + maxAge +
					'}';
		}
	}

	public static void main(String[] args) {
		System.out.println(getCityImageUrl(0, "Hong Kong"));

		System.out.println("==============");
		System.out.println(getAirportImageUrl(0, "Hong Kong International Airport"));
	}

	private static class NoLongerValidException extends Exception{

	}
}
