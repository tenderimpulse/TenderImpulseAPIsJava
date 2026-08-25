package client;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tender Impulse tender news API client for Java.
 *
 * Provides functionality to:
 * - Retrieve tender news articles
 * - Authenticate using Bearer tokens
 * - Decrypt API responses
 * - Validate response integrity using CRC/MD5 checksums
 *
 * Tender news articles carry their full body in the payload, so unlike the
 * tender and contract award clients this one downloads nothing and needs no
 * store path.
 *
 * Compatible with Java 17 and above.
 */
public class TenderImpulseTenderNewsClient {

	/**
	 * Endpoint that serves tender news articles.
	 */
	private static final String API_URL = "https://tenderimpulse.com/web-api/news/v2/uat.php";

	/**
	 * Time allowed for the API call, in milliseconds.
	 */
	private static final int API_TIMEOUT = 90000;

	/**
	 * Access token provided by Tender Impulse.
	 */
	private final String accessToken;

	/**
	 * AES decryption key provided by Tender Impulse.
	 */
	private final String key;

	public TenderImpulseTenderNewsClient(String accessToken, String key) {
		this.accessToken = accessToken;
		this.key = key;
	}

	/**
	 * Calls the Tender Impulse API and retrieves the batch of tender news
	 * articles that follows the given fetch id.
	 *
	 * @param lastId Fetch id of the last article already processed.
	 * @return On success: {@code {status, news, last_fetch_id}}.
	 *         On failure: {@code {status, msg}}.
	 */
	public JSONObject getTenderNews(long lastId) {

		JSONObject jsonResponse = new JSONObject();

		try {
			JSONObject apiResponse = call(lastId);

			// Decrypt API payload
			String decrypted = decrypt(apiResponse.getString("data"));

			// Validate integrity using CRC hash
			String calculatedCrc = md5(decrypted);

			if (!calculatedCrc.equalsIgnoreCase(apiResponse.getString("crc"))) {
				throw new Exception("Message transmission error");
			}

			JSONObject details = new JSONObject(decrypted);

			if (!"success".equals(details.optString("status"))) {
				throw new Exception(details.optString("msg"));
			}

			// Process tender news articles. The payload names this array "news",
			// and the returned object keeps that key so the response shape
			// matches the other Tender Impulse client libraries.
			JSONArray tenderNews = details.getJSONArray("news");
			JSONArray responseTenderNews = new JSONArray();

			for (int i = 0; i < tenderNews.length(); i++) {

				JSONObject article = tenderNews.getJSONObject(i);
				JSONObject responseArticle = new JSONObject();

				// Copy article metadata
				responseArticle.put("blogid", article.optString("blogid"));
				responseArticle.put("blogtitle", article.optString("blogtitle"));
				responseArticle.put("shortdescription", article.optString("shortdescription"));
				responseArticle.put("longdescription", article.optString("longdescription"));
				responseArticle.put("seourl", article.optString("seourl"));
				responseArticle.put("thumbnail_image", article.optString("thumbnail_image"));
				responseArticle.put("publishstatus", article.optString("publishstatus"));
				responseArticle.put("publishdate", article.optString("publishdate"));
				responseArticle.put("metatitle", article.optString("metatitle"));
				responseArticle.put("metakeywords", article.optString("metakeywords"));
				responseArticle.put("source", article.optString("source"));
				responseArticle.put("blogstatus", article.optString("blogstatus"));
				responseArticle.put("sectors", article.optString("sectors"));
				responseArticle.put("cpvs", article.optString("cpvs"));
				responseArticle.put("countries", article.optString("countries"));
				responseArticle.put("regions", article.optString("regions"));
				responseArticle.put("createddate", article.optString("createddate"));
				// Spelt this way in the API payload.
				responseArticle.put("ceratedtime", article.optString("ceratedtime"));
				responseArticle.put("updatedate", article.optString("updatedate"));
				responseArticle.put("updatedtime", article.optString("updatedtime"));

				responseTenderNews.put(responseArticle);
			}

			jsonResponse.put("status", "success");
			jsonResponse.put("news", responseTenderNews);
			jsonResponse.put("last_fetch_id", Long.parseLong(details.get("fetchid").toString()));

		} catch (Exception e) {
			jsonResponse = new JSONObject();
			jsonResponse.put("status", "error");
			jsonResponse.put("msg", e.getMessage());
		}

		return jsonResponse;
	}

	/**
	 * Performs the authenticated GET request and parses the raw envelope.
	 *
	 * @param lastId Fetch id to resume from.
	 * @return The {@code {data, crc}} envelope returned by the API.
	 */
	private JSONObject call(long lastId) throws Exception {

		URL url = new URL(API_URL + "?lastid=" + lastId);

		HttpURLConnection httpCon = (HttpURLConnection) url.openConnection();
		httpCon.setRequestMethod("GET");
		httpCon.setConnectTimeout(API_TIMEOUT);
		httpCon.setReadTimeout(API_TIMEOUT);

		// Bearer authentication
		httpCon.setRequestProperty("Authorization", "Bearer " + accessToken);
		httpCon.setRequestProperty("Content-Type", "application/json");

		int responseCode = httpCon.getResponseCode();

		if (responseCode != 200) {
			// The body carries the reason, e.g. "Invalid Token" on a 401.
			throw new Exception("Could not connect to tenderimpulse.com, error code: " + responseCode
					+ ", response: " + read(httpCon.getErrorStream()));
		}

		return new JSONObject(read(httpCon.getInputStream()));
	}

	/**
	 * Reads a response stream as UTF-8. A null stream yields an empty string,
	 * since {@code getErrorStream} returns null when there is no error body.
	 */
	private String read(InputStream stream) throws Exception {

		if (stream == null) {
			return "";
		}

		StringBuilder response = new StringBuilder();

		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8))) {

			String inputLine;

			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
		}

		return response.toString();
	}

	/**
	 * Decrypts an encrypted payload received from the API.
	 *
	 * Expected format:
	 * encryptedData:iv
	 *
	 * Uses AES/CBC/PKCS5Padding with a 128 bit key.
	 */
	private String decrypt(String data) throws Exception {

		try {
			String[] parts = data.split(":");

			if (parts.length != 2) {
				throw new Exception("Invalid encrypted payload");
			}

			byte[] encryptedData = Base64.getDecoder().decode(parts[0]);
			IvParameterSpec iv = new IvParameterSpec(Base64.getDecoder().decode(parts[1]));

			SecretKeySpec skeySpec = new SecretKeySpec(normalizeKey(), "AES");

			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
			cipher.init(Cipher.DECRYPT_MODE, skeySpec, iv);

			return new String(cipher.doFinal(encryptedData), StandardCharsets.UTF_8);

		} catch (Exception e) {
			throw new Exception("Unable to decrypt");
		}
	}

	/**
	 * Forces the key to the 16 bytes AES-128 requires: shorter keys are padded
	 * with '0', longer ones are truncated.
	 */
	private byte[] normalizeKey() {

		byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

		if (keyBytes.length == 16) {
			return keyBytes;
		}

		byte[] normalized = Arrays.copyOf(keyBytes, 16);

		for (int i = keyBytes.length; i < 16; i++) {
			normalized[i] = '0';
		}

		return normalized;
	}

	/**
	 * Generates the MD5 checksum used for response validation.
	 *
	 * @param s String to hash.
	 * @return MD5 hash in hexadecimal format.
	 */
	private String md5(String s) throws Exception {

		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			byte[] messageDigest = digest.digest(s.getBytes(StandardCharsets.UTF_8));

			StringBuilder hexString = new StringBuilder();

			for (byte aMessageDigest : messageDigest) {
				String h = Integer.toHexString(0xFF & aMessageDigest);

				while (h.length() < 2) {
					h = "0" + h;
				}

				hexString.append(h);
			}

			return hexString.toString();

		} catch (Exception e) {
			throw new Exception("Unable to generate md5");
		}
	}
}
