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

import client.TenderImpulseContractAwardClient;

/**
 * Fetches one batch of contract awards and stores the returned fetch id so the
 * next run resumes from where this one stopped.
 *
 * Compatible with Java 17 and above.
 */
public class GetContractAwardsExample {

	/**
	 * Local folder where contract award documents will be stored.
	 */
	private static final String STORE_PATH = "contract-award-documents";

	/**
	 * Access token provided by Tender Impulse.
	 */
	private static final String ACCESS_TOKEN = "your_access_token";

	/**
	 * AES decryption key provided by Tender Impulse.
	 */
	private static final String KEY = "your_key";

	/**
	 * File where the last fetch id is stored between runs.
	 */
	private static final Path STATE_FILE = Paths.get("contract-award-state.json").toAbsolutePath();

	/**
	 * Fetch id to start from the very first time this example is run.
	 */
	private static final long INITIAL_LAST_ID = 261374;

	/**
	 * Entry point.
	 */
	public static void main(String[] args) throws Exception {

		// Contract awards are published worldwide, so names and descriptions are
		// often non-English. Print as UTF-8 instead of the console encoding.
		System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

		TenderImpulseContractAwardClient client =
				new TenderImpulseContractAwardClient(STORE_PATH, ACCESS_TOKEN, KEY);

		long lastId = readLastId();

		System.out.println("Last Id: " + lastId);

		JSONObject result = client.getContractAwards(lastId);

		if ("success".equals(result.getString("status"))) {

			JSONArray contracts = result.getJSONArray("contracts");
			long lastFetchId = result.getLong("last_fetch_id");

			System.out.println("Contract Awards Fetched: " + contracts.length());
			System.out.println("Last Fetch Id: " + lastFetchId);
			System.out.println("Contract Awards: " + contracts.toString(2));

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
