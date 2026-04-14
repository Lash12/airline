package controllers;

import com.patson.data.AirlineSource;
import com.patson.data.AirportSource;
import com.patson.data.AllianceSource;
import com.patson.data.CountrySource;
import com.patson.model.Airline;
import com.patson.model.Airport;
import com.patson.model.Alliance;
import com.patson.model.Country;
import com.patson.util.AirlineCache;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.http.HttpHost;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.index.query.*;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;
import scala.collection.JavaConverters;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

public class SearchUtil {
	private final static Logger logger = LoggerFactory.getLogger(SearchUtil.class);
	private static final Config config = ConfigFactory.load();
	private static final boolean localLite = booleanSetting("AIRLINE_LOCAL_LITE", "airline.local-lite", false);
	private static final boolean elasticSearchEnabled = booleanSetting("AIRLINE_SEARCH_ELASTICSEARCH_ENABLED", "airline.search.elasticsearch.enabled", !localLite);
	private static volatile boolean elasticSearchAvailable = elasticSearchEnabled;
	static {
		if (elasticSearchEnabled) {
			checkInit();
		} else {
			logger.info("Elasticsearch-backed search is disabled. Using in-process fallback search.");
		}
	}

	/**
	 * Initialize the index if it's empty
	 */
	private static void checkInit() {
		try (RestHighLevelClient client = getClient()) {
			if (!isIndexExist(client, "airports")) {
				System.out.println("Initializing ES airports");
				initAirports(client);
			}
			if (!isIndexExist(client, "countries")) {
				System.out.println("Initializing ES countires");
				initCountries(client);
			}
//			if (!isIndexExist(client, "zones")) {
//				System.out.println("Initializing ES zones");
//				initZones(client);
//			}
			if (!isIndexExist(client, "airlines")) {
				System.out.println("Initializing ES airlines");
				initAirlines(client);
			}
			if (!isIndexExist(client, "alliances")) {
				System.out.println("Initializing ES alliances");
				initAlliances(client);
			}
		} catch (IOException e) {
			disableElasticSearch(e);
		}
		System.out.println("ES check finished");
	}

	public static void main(String[] args) throws IOException {
		init();
//		search("new");
	}


	public static void init() throws IOException {
		if (!elasticSearchEnabled) {
			logger.info("Skipping Elasticsearch initialization because search is disabled.");
			return;
		}
		try (RestHighLevelClient client = getClient()) {
			System.out.println("Initializing ES airports");
			initAirports(client);
			System.out.println("Initializing ES countires");
			initCountries(client);
//			System.out.println("Initializing ES zones");
//			initZones(client);
			System.out.println("Initializing ES airlines");
			initAirlines(client);
			System.out.println("Initializing ES alliances");
			initAlliances(client);
		}
		System.out.println("ES DONE");
	}

	private static boolean booleanSetting(String envName, String configPath, boolean defaultValue) {
		String envValue = System.getenv(envName);
		if (envValue != null) {
			String normalized = envValue.trim().toLowerCase(Locale.ROOT);
			if (Arrays.asList("1", "true", "yes", "y", "on").contains(normalized)) {
				return true;
			}
			if (Arrays.asList("0", "false", "no", "n", "off").contains(normalized)) {
				return false;
			}
		}
		return config.hasPath(configPath) ? config.getBoolean(configPath) : defaultValue;
	}

	private static boolean useElasticSearch() {
		return elasticSearchEnabled && elasticSearchAvailable;
	}

	private static void disableElasticSearch(IOException exception) {
		if (elasticSearchAvailable) {
			logger.warn("Elasticsearch is unavailable. Falling back to in-process search.", exception);
		}
		elasticSearchAvailable = false;
	}

	private static boolean isIndexExist(RestHighLevelClient client, String indexName) throws IOException {
		GetIndexRequest request = new GetIndexRequest(indexName);
		return client.indices().exists(request, RequestOptions.DEFAULT);
	}

	private static void initAirports(RestHighLevelClient client) throws IOException {
		if (isIndexExist(client, "airports")) {
			client.indices().delete(new DeleteIndexRequest("airports"), RequestOptions.DEFAULT);
		}

		List<Airport> airports = JavaConverters.asJava(AirportSource.loadAllAirports(false, false));
		System.out.println("loaded " + airports.size() + " airports");

		//RestHighLevelClient client = getClient();
		int count = 0;
		for (Airport airport : airports) {
			Map<String, Object> jsonMap = new HashMap<>();
			jsonMap.put("airportId", airport.id());
			jsonMap.put("airportIata", airport.iata());
			jsonMap.put("airportCity", airport.city());
			jsonMap.put("airportPower", airport.power());
			jsonMap.put("countryCode", airport.countryCode());
			jsonMap.put("airportName", airport.name());
			IndexRequest indexRequest = new IndexRequest("airports").source(jsonMap);
			client.index(indexRequest, RequestOptions.DEFAULT);

			if ((++ count) % 100 == 0) {
				System.out.println("indexed " + count + " airports");
			}

		}

	}

	private static void initCountries(RestHighLevelClient client) throws IOException {
		if (isIndexExist(client, "countries")) {
			client.indices().delete(new DeleteIndexRequest("countries"), RequestOptions.DEFAULT);
		}

		List<Country> countries = JavaConverters.asJava(CountrySource.loadAllCountries());
		System.out.println("loaded " + countries.size() + " countries");

		for (Country country : countries) {
			Map<String, Object> jsonMap = new HashMap<>();
			jsonMap.put("countryName", country.name());
			jsonMap.put("countryCode", country.countryCode());
			jsonMap.put("population", country.airportPopulation());
			IndexRequest indexRequest = new IndexRequest("countries").source(jsonMap);
			client.index(indexRequest, RequestOptions.DEFAULT);
		}

	}

	private static void initZones(RestHighLevelClient client) throws IOException {
		if (isIndexExist(client, "zones")) {
			client.indices().delete(new DeleteIndexRequest("zones"), RequestOptions.DEFAULT);
		}

		Map<String, String> zones = new HashMap<>();
		zones.put("AS", "Asia");
		zones.put("OC", "Oceania");
		zones.put("AF", "Africa");
		zones.put("EU", "Europe");
		zones.put("NA", "North America");
		zones.put("SA", "South America");

		for (Map.Entry<String, String> entry : zones.entrySet()) {
			String zone = entry.getKey();
			String zoneName = entry.getValue();
			Map<String, Object> jsonMap = new HashMap<>();
			jsonMap.put("zone", zone);
			jsonMap.put("zoneName", zoneName);
			IndexRequest indexRequest = new IndexRequest("zones").source(jsonMap);
			client.index(indexRequest, RequestOptions.DEFAULT);
		}
	}

	private static void initAirlines(RestHighLevelClient client) throws IOException {
		if (isIndexExist(client, "airlines")) {
			client.indices().delete(new DeleteIndexRequest("airlines"), RequestOptions.DEFAULT);
		}

		List<Airline> airlines = JavaConverters.asJava(AirlineSource.loadAllAirlines(false));
		System.out.println("loaded " + airlines.size() + " airlines");


		for (Airline airline : airlines) {
			Map<String, Object> jsonMap = new HashMap<>();
			jsonMap.put("airlineId", airline.id());
			jsonMap.put("airlineName", airline.name());
			jsonMap.put("airlineCode", airline.getAirlineCode());
			jsonMap.put("previousNames", JavaConverters.asJava(airline.previousNames()));
			IndexRequest indexRequest = new IndexRequest("airlines").source(jsonMap);
			client.index(indexRequest, RequestOptions.DEFAULT);
		}
	}

	public static void addAirline(Airline airline) {
		if (!useElasticSearch()) {
			return;
		}
		try (RestHighLevelClient client = getClient()) {
			Map<String, Object> jsonMap = new HashMap<>();
			jsonMap.put("airlineId", airline.id());
			jsonMap.put("airlineName", airline.name());
			jsonMap.put("airlineCode", airline.getAirlineCode());
			jsonMap.put("previousNames", JavaConverters.asJava(airline.previousNames()));
			IndexRequest indexRequest = new IndexRequest("airlines").source(jsonMap);
			logger.info("Indexing new doc " + jsonMap);
			client.index(indexRequest, RequestOptions.DEFAULT);
		} catch (IOException e) {
			disableElasticSearch(e);
		}
		System.out.println("Added airline " + airline + " to ES");
	}

	public static void updateAirline(Airline airline) {
		if (!useElasticSearch()) {
			return;
		}
		try (RestHighLevelClient client = getClient()) {
			SearchRequest searchRequest = new SearchRequest("airlines");
			SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
			searchSourceBuilder.query(new TermQueryBuilder("airlineId", airline.id()));
			searchRequest.source(searchSourceBuilder);
			SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);
			for (SearchHit hit : response.getHits()) {
				if (Integer.valueOf(airline.id()).equals(hit.getSourceAsMap().get("airlineId"))) { //double check
					logger.info("Updating airline " + hit);
					UpdateRequest request = new UpdateRequest("airlines", hit.getId());
					Map<String, Object> jsonMap = new HashMap<>();
					jsonMap.put("airlineId", airline.id());
					jsonMap.put("airlineName", airline.name());
					jsonMap.put("airlineCode", airline.getAirlineCode());
					jsonMap.put("previousNames", JavaConverters.asJava(airline.previousNames()));
					request.doc(jsonMap);
					client.update(request, RequestOptions.DEFAULT);
					logger.info("Updated to " + jsonMap);
				} else {
					logger.warn("Hit " + hit.getSourceAsMap() + " is not a match to airline ID " + airline.id());
				}
			}
		} catch (IOException e) {
			disableElasticSearch(e);
		}
		System.out.println("Updated airline " + airline + " to ES");
	}



	public static void initAlliances(RestHighLevelClient client) throws IOException {
		if (isIndexExist(client, "alliances")) {
			client.indices().delete(new DeleteIndexRequest("alliances"), RequestOptions.DEFAULT);
		}

		List<Alliance> alliances = JavaConverters.asJava(AllianceSource.loadAllAlliances(false));
		System.out.println("loaded " + alliances.size() + " alliances");


		for (Alliance alliance : alliances) {
			Map<String, Object> jsonMap = new HashMap<>();
			jsonMap.put("allianceId", alliance.id());
			jsonMap.put("allianceName", alliance.name());

			IndexRequest indexRequest = new IndexRequest("alliances").source(jsonMap);
			client.index(indexRequest, RequestOptions.DEFAULT);
		}
	}

	public static void addAlliance(Alliance alliance) {
		if (!useElasticSearch()) {
			return;
		}
		try (RestHighLevelClient client = getClient()) {
			Map<String, Object> jsonMap = new HashMap<>();
			jsonMap.put("allianceId", alliance.id());
			jsonMap.put("allianceName", alliance.name());

			IndexRequest indexRequest = new IndexRequest("alliances").source(jsonMap);
			client.index(indexRequest, RequestOptions.DEFAULT);
		} catch (IOException e) {
			disableElasticSearch(e);
		}
		System.out.println("Added alliance " + alliance + " to ES");

	}

	public static void removeAlliance(int allianceId) {
		if (!useElasticSearch()) {
			return;
		}
		try (RestHighLevelClient client = getClient()) {
			DeleteByQueryRequest request =	new DeleteByQueryRequest("alliances");
			request.setQuery(new TermQueryBuilder("allianceId", allianceId));
			request.setRefresh(true);

			client.deleteByQuery(request, RequestOptions.DEFAULT);
		} catch (IOException exception) {
			disableElasticSearch(exception);
		}
		System.out.println("Removed alliance with id " + allianceId + " from ES");
	}

	public static void refreshAlliances() {
		if (!useElasticSearch()) {
			return;
		}
		try (RestHighLevelClient client = getClient()) {
			System.out.println("Refreshing ES alliances");
			initAlliances(client);
		} catch (IOException exception) {
			disableElasticSearch(exception);
		}
		System.out.println("Refreshed ES alliances");
	}


	private static final Pattern letterSpaceOnlyPattern = Pattern.compile("^[ A-Za-z]+$");

	public static List<AirportSearchResult> searchAirport(String input) {
		if (!letterSpaceOnlyPattern.matcher(input).matches()) {
			return Collections.emptyList();
		}

		if (!useElasticSearch()) {
			return fallbackAirportSearch(input);
		}

		try (RestHighLevelClient client = getClient()) {
			SearchRequest searchRequest = new SearchRequest("airports");
			SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();


			QueryStringQueryBuilder multiMatchQueryBuilder = QueryBuilders.queryStringQuery(input + "*");
			multiMatchQueryBuilder.field("airportIata",5);
			multiMatchQueryBuilder.field("airportName",1);
			multiMatchQueryBuilder.field("airportCity",2);
			multiMatchQueryBuilder.defaultOperator(Operator.AND);
//			multiMatchQueryBuilder.fuzziness(Fuzziness.TWO);
//			multiMatchQueryBuilder.maxExpansions(100);
//			multiMatchQueryBuilder.prefixLength(10);
//			multiMatchQueryBuilder.tieBreaker(20);

			multiMatchQueryBuilder.type(MultiMatchQueryBuilder.Type.BEST_FIELDS);


			searchSourceBuilder.query(multiMatchQueryBuilder).size(100);

			searchRequest.source(searchSourceBuilder);
			SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);

			List<AirportSearchResult> result = new ArrayList<>();
			for (SearchHit hit : response.getHits()) {
				Map<String, Object> values = hit.getSourceAsMap();

				Object powerObject = values.get("airportPower");
				long power = powerObject instanceof Integer ? (long) ((Integer)powerObject) : (Long) powerObject;
				AirportSearchResult searchResult = new AirportSearchResult((int) values.get("airportId"), (String) values.get("airportIata"), (String) values.get("airportName"), (String) values.get("airportCity"), (String) values.get("countryCode"), power, hit.getScore());
				result.add(searchResult);
			}

			Collections.sort(result);
			Collections.reverse(result);

			//System.out.println("done");
			return result;
		} catch (IOException e) {
			disableElasticSearch(e);
			return fallbackAirportSearch(input);
		}

	}


	public static List<CountrySearchResult> searchCountry(String input) {
		if (!letterSpaceOnlyPattern.matcher(input).matches()) {
			return Collections.emptyList();
		}

		if (!useElasticSearch()) {
			return fallbackCountrySearch(input);
		}

		try (RestHighLevelClient client = getClient()) {
			SearchRequest searchRequest = new SearchRequest("countries");
			SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();


			QueryStringQueryBuilder multiMatchQueryBuilder = QueryBuilders.queryStringQuery(input + "*");
			multiMatchQueryBuilder.field("countryCode",5);
			multiMatchQueryBuilder.field("countryName",1);
			multiMatchQueryBuilder.defaultOperator(Operator.AND);
//			multiMatchQueryBuilder.fuzziness(Fuzziness.TWO);
//			multiMatchQueryBuilder.maxExpansions(100);
//			multiMatchQueryBuilder.prefixLength(10);
//			multiMatchQueryBuilder.tieBreaker(20);

			multiMatchQueryBuilder.type(MultiMatchQueryBuilder.Type.BEST_FIELDS);


			searchSourceBuilder.query(multiMatchQueryBuilder).size(100);

			searchRequest.source(searchSourceBuilder);
			SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);

			List<CountrySearchResult> result = new ArrayList<>();
			for (SearchHit hit : response.getHits()) {
				Map<String, Object> values = hit.getSourceAsMap();

				CountrySearchResult searchResult = new CountrySearchResult((String) values.get("countryName"), (String) values.get("countryCode"), (int) values.get("population"), hit.getScore());
				result.add(searchResult);
			}

			Collections.sort(result);
			Collections.reverse(result);

			//System.out.println("done");
			return result;
		} catch (IOException e) {
			disableElasticSearch(e);
			return fallbackCountrySearch(input);
		}
	}

	public static List<ZoneSearchResult> searchZone(String input) {
		if (!letterSpaceOnlyPattern.matcher(input).matches()) {
			return Collections.emptyList();
		}

		try (RestHighLevelClient client = getClient()) {
			SearchRequest searchRequest = new SearchRequest("zones");
			SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();


			QueryStringQueryBuilder multiMatchQueryBuilder = QueryBuilders.queryStringQuery(input + "*");
			multiMatchQueryBuilder.field("zone",10);
			multiMatchQueryBuilder.field("zoneName",2);
			multiMatchQueryBuilder.defaultOperator(Operator.AND);
//			multiMatchQueryBuilder.fuzziness(Fuzziness.TWO);
//			multiMatchQueryBuilder.maxExpansions(100);
//			multiMatchQueryBuilder.prefixLength(10);
//			multiMatchQueryBuilder.tieBreaker(20);

			multiMatchQueryBuilder.type(MultiMatchQueryBuilder.Type.BEST_FIELDS);


			searchSourceBuilder.query(multiMatchQueryBuilder).size(10);

			searchRequest.source(searchSourceBuilder);
			SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);

			List<ZoneSearchResult> result = new ArrayList<>();
			for (SearchHit hit : response.getHits()) {
				Map<String, Object> values = hit.getSourceAsMap();

				ZoneSearchResult searchResult = new ZoneSearchResult((String) values.get("zoneName"), (String) values.get("zone"), hit.getScore());
				result.add(searchResult);
			}

			Collections.sort(result);
			Collections.reverse(result);

			//System.out.println("done");
			return result;
		} catch (IOException e) {
			e.printStackTrace();
			return Collections.EMPTY_LIST;
		}
	}

	public static List<AirlineSearchResult> searchAirline(String input) {
		if (!letterSpaceOnlyPattern.matcher(input).matches()) {
			return Collections.emptyList();
		}

		if (!useElasticSearch()) {
			return fallbackAirlineSearch(input);
		}

		try (RestHighLevelClient client = getClient()) {
			SearchRequest searchRequest = new SearchRequest("airlines");
			SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();


			QueryStringQueryBuilder multiMatchQueryBuilder = QueryBuilders.queryStringQuery(input + "*");
			multiMatchQueryBuilder.field("airlineName",5);
			multiMatchQueryBuilder.field("airlineCode",1);
			multiMatchQueryBuilder.field("previousNames",4);

			multiMatchQueryBuilder.defaultOperator(Operator.AND);
//			multiMatchQueryBuilder.fuzziness(Fuzziness.TWO);
//			multiMatchQueryBuilder.maxExpansions(100);
//			multiMatchQueryBuilder.prefixLength(10);
//			multiMatchQueryBuilder.tieBreaker(20);

			multiMatchQueryBuilder.type(MultiMatchQueryBuilder.Type.BEST_FIELDS);

			searchSourceBuilder.highlighter(new HighlightBuilder().field("previousNames"));
			searchSourceBuilder.query(multiMatchQueryBuilder).size(10);

			searchRequest.source(searchSourceBuilder);
			SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);

			List<AirlineSearchResult> result = new ArrayList<>();
			for (SearchHit hit : response.getHits()) {
				Map<String, Object> values = hit.getSourceAsMap();
				Option<Airline> airlineOption = AirlineCache.getAirline((int) values.get("airlineId"), false);
				if (airlineOption.isDefined()) {
					AirlineSearchResult searchResult = new AirlineSearchResult(airlineOption.get(), hit.getScore(), hit.getHighlightFields().containsKey("previousNames"));
					result.add(searchResult);
				}
			}

			Collections.sort(result);
			Collections.reverse(result);

			//System.out.println("done");
			return result;
		} catch (IOException e) {
			disableElasticSearch(e);
			return fallbackAirlineSearch(input);
		}

	}

	public static List<AllianceSearchResult> searchAlliance(String input) {
		if (!letterSpaceOnlyPattern.matcher(input).matches()) {
			return Collections.emptyList();
		}

		if (!useElasticSearch()) {
			return fallbackAllianceSearch(input);
		}

		try (RestHighLevelClient client = getClient()) {
			SearchRequest searchRequest = new SearchRequest("alliances");
			SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();


			QueryStringQueryBuilder multiMatchQueryBuilder = QueryBuilders.queryStringQuery(input + "*");
			multiMatchQueryBuilder.field("allianceName",10);
			multiMatchQueryBuilder.defaultOperator(Operator.AND);
//			multiMatchQueryBuilder.fuzziness(Fuzziness.TWO);
//			multiMatchQueryBuilder.maxExpansions(100);
//			multiMatchQueryBuilder.prefixLength(10);
//			multiMatchQueryBuilder.tieBreaker(20);

			multiMatchQueryBuilder.type(MultiMatchQueryBuilder.Type.BEST_FIELDS);


			searchSourceBuilder.query(multiMatchQueryBuilder).size(10);

			searchRequest.source(searchSourceBuilder);
			SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);

			List<AllianceSearchResult> result = new ArrayList<>();
			for (SearchHit hit : response.getHits()) {
				Map<String, Object> values = hit.getSourceAsMap();

				AllianceSearchResult searchResult = new AllianceSearchResult((int) values.get("allianceId"), (String) values.get("allianceName"), hit.getScore());
				result.add(searchResult);
			}

			Collections.sort(result);
			Collections.reverse(result);

			//System.out.println("done");
			return result;
		} catch (IOException e) {
			disableElasticSearch(e);
			return fallbackAllianceSearch(input);
		}
	}

	private static List<String> toTerms(String input) {
		String normalized = input.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			return Collections.emptyList();
		}
		return Arrays.asList(normalized.split("\\s+"));
	}

	private static boolean matchesAllTerms(List<String> terms, String... fields) {
		for (String term : terms) {
			boolean matched = false;
			for (String field : fields) {
				if (field != null && field.toLowerCase(Locale.ROOT).contains(term)) {
					matched = true;
					break;
				}
			}
			if (!matched) {
				return false;
			}
		}
		return true;
	}

	private static double matchScore(String normalizedInput, String... fields) {
		double score = 0;
		for (String field : fields) {
			if (field == null || field.isEmpty()) {
				continue;
			}
			String normalizedField = field.toLowerCase(Locale.ROOT);
			if (normalizedField.equals(normalizedInput)) {
				score = Math.max(score, 100d);
			} else if (normalizedField.startsWith(normalizedInput)) {
				score = Math.max(score, 75d);
			} else if (normalizedField.contains(normalizedInput)) {
				score = Math.max(score, 50d);
			}
		}
		return score;
	}

	private static List<AirportSearchResult> fallbackAirportSearch(String input) {
		List<String> terms = toTerms(input);
		String normalizedInput = input.trim().toLowerCase(Locale.ROOT);
		List<AirportSearchResult> result = new ArrayList<>();
		for (Airport airport : JavaConverters.asJava(AirportSource.loadAllAirports(false, false))) {
			if (matchesAllTerms(terms, airport.iata(), airport.name(), airport.city(), airport.countryCode())) {
				double score = matchScore(normalizedInput, airport.iata(), airport.name(), airport.city()) + Math.min(airport.power() / 1_000_000_000d, 25d);
				result.add(new AirportSearchResult(airport.id(), airport.iata(), airport.name(), airport.city(), airport.countryCode(), airport.power(), score));
			}
		}
		Collections.sort(result);
		Collections.reverse(result);
		return result.size() > 100 ? new ArrayList<>(result.subList(0, 100)) : result;
	}

	private static List<CountrySearchResult> fallbackCountrySearch(String input) {
		List<String> terms = toTerms(input);
		String normalizedInput = input.trim().toLowerCase(Locale.ROOT);
		List<CountrySearchResult> result = new ArrayList<>();
		for (Country country : JavaConverters.asJava(CountrySource.loadAllCountries())) {
			if (matchesAllTerms(terms, country.name(), country.countryCode())) {
				double score = matchScore(normalizedInput, country.name(), country.countryCode()) + Math.min(country.airportPopulation() / 10_000_000d, 25d);
				result.add(new CountrySearchResult(country.name(), country.countryCode(), country.airportPopulation(), score));
			}
		}
		Collections.sort(result);
		Collections.reverse(result);
		return result.size() > 100 ? new ArrayList<>(result.subList(0, 100)) : result;
	}

	private static List<AirlineSearchResult> fallbackAirlineSearch(String input) {
		List<String> terms = toTerms(input);
		String normalizedInput = input.trim().toLowerCase(Locale.ROOT);
		List<AirlineSearchResult> result = new ArrayList<>();
		for (Airline airline : JavaConverters.asJava(AirlineSource.loadAllAirlines(false))) {
			List<String> previousNames = JavaConverters.asJava(airline.previousNames());
			boolean previousNameMatch = false;
			for (String previousName : previousNames) {
				if (previousName != null && previousName.toLowerCase(Locale.ROOT).contains(normalizedInput)) {
					previousNameMatch = true;
					break;
				}
			}
			List<String> searchableFields = new ArrayList<>(previousNames);
			searchableFields.add(airline.name());
			searchableFields.add(airline.getAirlineCode());
			if (matchesAllTerms(terms, searchableFields.toArray(new String[0]))) {
				double score = matchScore(normalizedInput, airline.name(), airline.getAirlineCode()) + (previousNameMatch ? 10d : 0d);
				result.add(new AirlineSearchResult(airline, score, previousNameMatch));
			}
		}
		Collections.sort(result);
		Collections.reverse(result);
		return result.size() > 10 ? new ArrayList<>(result.subList(0, 10)) : result;
	}

	private static List<AllianceSearchResult> fallbackAllianceSearch(String input) {
		List<String> terms = toTerms(input);
		String normalizedInput = input.trim().toLowerCase(Locale.ROOT);
		List<AllianceSearchResult> result = new ArrayList<>();
		for (Alliance alliance : JavaConverters.asJava(AllianceSource.loadAllAlliances(false))) {
			if (matchesAllTerms(terms, alliance.name())) {
				double score = matchScore(normalizedInput, alliance.name());
				result.add(new AllianceSearchResult(alliance.id(), alliance.name(), score));
			}
		}
		Collections.sort(result);
		Collections.reverse(result);
		return result.size() > 10 ? new ArrayList<>(result.subList(0, 10)) : result;
	}


	private static RestHighLevelClient getClient() {
		String esHost = System.getenv().getOrDefault("ES_HOST", "localhost");
		System.out.println("!!!!!!!!!!!!!!!ES HOST IS " + esHost);
		RestHighLevelClient client = new RestHighLevelClient(
				RestClient.builder(
						new HttpHost(esHost, 9200, "http"),
						new HttpHost(esHost, 9201, "http")));
		return client;
	}
}

class AirportSearchResult implements Comparable {
	private final long power;
	private int id;
	private String iata, name, city, countryCode;
	private double score;

	public AirportSearchResult(int id, String iata, String name, String city, String countryCode, long power, double score) {
		this.id = id;
		this.iata = iata;
		this.name = name;
		this.city = city;
		this.countryCode = countryCode;
		this.power = power;
		this.score = score;
	}

	public int getId() {
		return id;
	}

	public String getIata() {
		return iata;
	}

	public String getName() {
		return name;
	}

	public String getCity() {
		return city;
	}

	public String getCountryCode() {
		return countryCode;
	}

	public double getScore() {
		return score;
	}

	public long getPower() {
		return power;
	}

	@Override
	public int compareTo(Object o) {
		if (!(o instanceof AirportSearchResult)) {
			throw new IllegalArgumentException(o + " is not a " + AirportSearchResult.class.getSimpleName());
		}

		AirportSearchResult that = (AirportSearchResult) o;

		if (this.score != that.score) {
			return this.score < that.score ? -1 : 1;
		} else if (this.power != that.power){
			return this.power < that.power ? -1 : 1;
		} else {
			return this.iata.compareTo(that.iata);
		}
	}
}

class CountrySearchResult implements Comparable {
	private final String name, countryCode;
	private final double score;
	private final int population;

	public CountrySearchResult(String name, String countryCode, int population, double score) {
		this.name = name;
		this.countryCode = countryCode;
		this.population = population;
		this.score = score;
	}

	public String getName() {
		return name;
	}

	public String getCountryCode() {
		return countryCode;
	}

	public double getScore() {
		return score;
	}

	public int getPopulation() {
		return population;
	}

	@Override
	public int compareTo(Object o) {
		if (!(o instanceof CountrySearchResult)) {
			throw new IllegalArgumentException(o + " is not a " + CountrySearchResult.class.getSimpleName());
		}

		CountrySearchResult that = (CountrySearchResult) o;

		if (this.score != that.score) {
			return this.score < that.score ? -1 : 1;
		} else {
			return this.population - that.population;
		}
	}
}


class ZoneSearchResult implements Comparable {
	private final String name, zone;
	private final double score;

	public ZoneSearchResult(String name, String zone,  double score) {
		this.name = name;
		this.zone = zone;
		this.score = score;
	}

	public String getName() {
		return name;
	}

	public String getZone() {
		return zone;
	}

	public double getScore() {
		return score;
	}


	@Override
	public int compareTo(Object o) {
		if (!(o instanceof ZoneSearchResult)) {
			throw new IllegalArgumentException(o + " is not a " + ZoneSearchResult.class.getSimpleName());
		}

		ZoneSearchResult that = (ZoneSearchResult) o;

		if (this.score != that.score) {
			return this.score < that.score ? -1 : 1;
		} else {
			return this.name.compareTo(that.name);
		}
	}
}


class AirlineSearchResult implements Comparable {
	private final Airline airline;
	private final double score;
	private final boolean previousNameMatch;
	//private final int status;

	public AirlineSearchResult(Airline airline, double score, boolean previousNameMatch) {
		this.airline = airline;
		this.score = score;
		this.previousNameMatch = previousNameMatch;
		//this.status = status;
	}

	public Airline getAirline() {
		return airline;
	}

	public double getScore() {
		return score;
	}

	public boolean isPreviousNameMatch() {
		return previousNameMatch;
	}

	//	public int getStatus() {
//		return status;
//	}

	@Override
	public int compareTo(Object o) {
		if (!(o instanceof AirlineSearchResult)) {
			throw new IllegalArgumentException(o + " is not a " + AirlineSearchResult.class.getSimpleName());
		}

		AirlineSearchResult that = (AirlineSearchResult) o;

		if (this.score != that.score) {
			return this.score < that.score ? -1 : 1;
		} else {
			return that.airline.id() - this.airline.id();
		}
	}

	@Override
	public String toString() {
		return "AirlineSearchResult{" +
				"airline=" + airline +
				", score=" + score +
				'}';
	}
}

class AllianceSearchResult implements Comparable {
	private final String allianceName;
	private final double score;
	private final int allianceId;

	public AllianceSearchResult(int id, String name, double score) {
		this.allianceId = id;
		this.allianceName = name;
		this.score = score;
	}

	public String getAllianceName() {
		return allianceName;
	}

	public double getScore() {
		return score;
	}

	public int getAllianceId() {
		return allianceId;
	}

	@Override
	public int compareTo(Object o) {
		if (!(o instanceof AllianceSearchResult)) {
			throw new IllegalArgumentException(o + " is not a " + AllianceSearchResult.class.getSimpleName());
		}

		AllianceSearchResult that = (AllianceSearchResult) o;

		if (this.score != that.score) {
			return this.score < that.score ? -1 : 1;
		} else {
			return that.allianceId - this.allianceId;
		}
	}
}
