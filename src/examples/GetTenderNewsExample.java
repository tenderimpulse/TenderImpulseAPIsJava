package examples;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.json.JSONArray;
import org.json.JSONObject;

import client.TenderImpulseTenderNewsClient;

/**
 * Fetches one batch of tender news articles and stores the returned fetch id
 * so the next run resumes from where this one stopped.
 *
 * Compatible with Java 17 and above.
 */
public class GetTenderNewsExample {

	/**
	 * Access token provided by Tender Impulse.
	 */
	private static final String ACCESS_TOKEN = "m7a9re4f2r8d510a6n359d1v7a2cae85";

	/**
	 * AES decryption key provided by Tender Impulse.
	 */
	private static final String KEY = "v1t3y2b9n7mN1T8f";

	/**
	 * File where the last fetch id is stored between runs.
	 */
	private static final Path STATE_FILE = Paths.get("tender-news-state.json").toAbsolutePath();

	/**
	 * Fetch id to start from the very first time this example is run.
	 */
	private static final long INITIAL_LAST_ID = 18016;

	/**
	 * Entry point.
	 */
	public static void main(String[] args) throws Exception {

		// Tender news covers procurement worldwide, so headlines are often
		// non-English. Print as UTF-8 instead of the console encoding.
		System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

		TenderImpulseTenderNewsClient client = new TenderImpulseTenderNewsClient(ACCESS_TOKEN, KEY);

		long lastId = readLastId();

		System.out.println("Last Id: " + lastId);

		JSONObject result = client.getTenderNews(lastId);

		if ("success".equals(result.getString("status"))) {

			JSONArray tenderNews = result.getJSONArray("news");
			long lastFetchId = result.getLong("last_fetch_id");

			System.out.println("Tender News Fetched: " + tenderNews.length());
			System.out.println("Last Fetch Id: " + lastFetchId);

			// Only the headline fields are printed here. Every article also
			// carries longdescription, which holds the full article HTML.
			for (int i = 0; i < tenderNews.length(); i++) {

				JSONObject article = tenderNews.getJSONObject(i);

				System.out.println("- [" + article.getString("blogid") + "] "
						+ article.getString("blogtitle")
						+ " (" + article.getString("publishdate") + ")");
				System.out.println("  countries: " + article.getString("countries"));
				System.out.println("  sectors: " + article.getString("sectors"));
			}

			// Store the fetch id only after the batch has been handled,
			// so nothing is skipped if the run fails midway.
			writeLastId(lastFetchId);

		} else {

			System.out.println("Error: " + result.getString("msg"));

		}
	}

	/**
	 * Reads the stored fetch id, or falls back to the initial one.
	 */
	private static long readLastId() throws Exception {

		if (!Files.exists(STATE_FILE)) {
			return INITIAL_LAST_ID;
		}

		JSONObject state = new JSONObject(Files.readString(STATE_FILE, StandardCharsets.UTF_8));

		return state.getLong("fetchid");
	}

	/**
	 * Stores the fetch id so the next call resumes from here.
	 */
	private static void writeLastId(long fetchId) throws Exception {

		JSONObject state = new JSONObject();
		state.put("fetchid", fetchId);

		Files.writeString(STATE_FILE, state.toString(2), StandardCharsets.UTF_8);
	}
}
